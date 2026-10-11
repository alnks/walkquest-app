# QA WalkQuest — runbook

Единая точка входа для отладки и проверки работы карты: `_qa/wq.py`.
Проверки рендера по скриншотам: `_qa/check-fog-render.py`.
Оба скрипта — только stdlib Python (+ Pillow для `diff`/рендер-проверок).

## Окружение (проверено 2026-10-05…07)

- AVD: `Medium_Phone_API_37.0` (x86_64, API 37, google_apis_playstore), WHPX.
- Эмулятор запускать **только с `-gpu auto`** (gfxstream). `swiftshader_indirect`
  вешает гостя в adb-состоянии `offline` (~13 мин, лечится перезапуском).
  Загрузка ~98 с.
- JDK 17: `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`
  (JAVA_HOME не выставлен в системе — для gradle-задач экспортировать его;
  JBR Android Studio — Java 25, Gradle его отвергает).
- adb/emulator: `C:\Users\Lenovo\AppData\Local\Android\Sdk\...`
  (`wq.py` читает `sdk.dir` из `local.properties` сам).
- Быстрая проверка всего этого: `python _qa/wq.py doctor`.

## Типовой цикл (эмулятор)

```bash
python _qa/wq.py emu-start            # старт с правильными флагами, ждёт загрузку
python _qa/wq.py install              # gradle assembleDebug + защита от протухшего APK + adb install
python _qa/wq.py clear                # pm clear — сценарий «чистая установка»
python _qa/wq.py seed-package         # подложить пакет из tools/region-pack/out (run-as, с патчем style.json)
python _qa/wq.py launch               # запуск приложения
python _qa/wq.py geo 59.939 30.315    # один geo fix (повтор той же точки автосдвигается ~5 м)
python _qa/wq.py replay _qa/emulator-run/fix10-verify/fix7-walk.track
python _qa/wq.py shot after.png       # скриншот
python _qa/wq.py log                  # logcat -s WalkQuestFog:D TrackingService:D
python _qa/wq.py diff before.png after.png --out diff.png
python _qa/check-fog-render.py after.png --expect-gap-px 43 --expect-veil-max 0.95
```

**Ограничения check-fog-render:** метрика просвета (`--expect-gap-px`) применима
только к кадрам, где тропа пересекает сканлайн по суше (фикстура fix7/fix9).
Кадры с заливом/реками дают «просвет» из воды (векторная вода поверх завесы —
не туман) и низкое покрытие: для s0-launch (вид на губу) завеса = 43,5 % —
это свойство сцены, а не дефект. Для кадров с водой используйте
`--expect-veil-min` с заниженным порогом или не используйте вовсе.

Несколько устройств одновременно — любой команде добавить `--serial <id>`
(после `wq.py`, до подкоманды: `python _qa/wq.py --serial emulator-5554 shot a.png`).

## Телефон по USB (быстрый цикл разработка→тест)

1. На телефоне: режим разработчика → **отладка по USB**; подключить кабелем;
   в диалоге на телефоне разрешить отладку с этого ПК.
2. Проверка: `python _qa/wq.py devices` — телефон виден как `device`
   (serial не `emulator-*`).
3. Одна команда — сборка, установка, запуск:

```bash
python _qa/wq.py phone-install
```

`phone-install` дополнительно настраивает `adb reverse tcp:8777` — телефон
через кабель видит локальный сервер пакетов ПК как `localhost:8777`
(Wi-Fi не нужен). Чтобы debug-сборка качала пакеты с него:

```bash
python _qa/wq.py serve &                    # в отдельном терминале
python _qa/wq.py phone-install --local-server   # reverse + override base-url
```

Снять override: `python _qa/wq.py push-base-url` (без аргумента).

## Грабли, собранные из прогонов fix1–fix10

- **Протухший APK.** `adb install -r` не проверяет свежесть — дважды ставилась
  старая сборка, потому что gradle-вызов в составной команде молча не отработал.
  `wq.py build/install` сверяет mtime APK до/после сборки и падает, если
  файл не обновился. Контроль руками: ширина просвета в px должна совпасть
  с лесенкой радиусов.
- **Зум без root.** `pinch.sh` (sendevent) требует root — недоступен.
  Двойной тап по карте = +1 зум к точке тапа (MapLibre).
- **Повторный geo fix с теми же координатами** съедается слушателем
  (minUpdateDistance 2 м). `wq.py geo` делает автосдвиг ~5 м.
- **GPS-провайдер эмулятора может быть выключен**:
  `wq.py emu-start` сам делает `settings put secure location_providers_enabled +gps`.
- **Темп replay.** adb-фиксы, прилетающие быстрее реального пешего темпа,
  бракуются PointFilter (TooFast). `wq.py replay` сам выдерживает паузы
  по расстоянию между точками (потолок 3,0 м/с < лимита 12 км/ч).
- **accuracy эмулятором не регулируется** — отсечение по accuracy (> 50 м)
  покрыто только JVM-тестами PointFilter; живьём — полевой тест на телефоне.
- **Координаты виджетов** — `python _qa/wq.py uidump ui.xml`, искать по
  content-desc (FAB «К моей точке», кнопка «i» атрибуции).
- **Debug-оверлей (1.14)** — 5 быстрых тапов по карте включают плашку состояния
  (версия пакета в памяти, baseUrl, тайлы/лента, build/upload мс, последний фикс,
  счётчики фильтра). Только debug-сборки. Побочка: карта при этом подзумируется
  (double-tap-зум MapLibre срабатывает на тех же тапах).
- **run-as работает только с debuggable-сборкой** — `seed-package` и
  `push-base-url` против release не работают.

## Локальный контур пакетов (без GitHub)

`python _qa/wq.py serve` отдаёт `tools/region-pack/out` на `:8777`
(manifest.json + zip'ы). Debug-сборка берёт источник из
`files/qa/packages-base-url.txt` (`PackageSource.effectiveBaseUrl`):
эмулятор — `http://10.0.2.2:8777`, телефон после `adb reverse` —
`http://localhost:8777`. Так проверяется полный путь
«манифест → скачивание → sha256 → установка» без публикации.

## Артефакты прогонов

Скриншоты/логи каждой проверки — в `_qa/emulator-run/<имя>-verify/`,
итог — записью в `_bmad-output/initiative-walkquest/epic-map-foundation/deferred-work.md`
(см. скилл `walkquest-qa`).
