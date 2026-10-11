# Публикация пакета региона (история 1.4, HITL)

Чек-лист для человека: ~15–20 минут, всё через браузер на github.com, git и gh CLI не нужны.
Публикуем `out/region-spb-city-v1.zip` (28,9 МБ) на **GitHub Releases** (основной хостинг,
spine AD-6, решение от 2026-10-04). Cloudflare R2 — альтернатива на будущее (раздел 4).

Пакет к моменту публикации уже верифицирован: `verify.py` → PASS, sha256 архива
`0e453f155d99e052f13319fbed4e8d84a8dd9fd595c79ffbb241a54cfef1d42d` (28 929 684 байта).
Эти значения уже подставлены в `manifest.json` ниже — ничего пересчитывать не нужно.

## 1. Аккаунт и репозиторий (≈5 мин)

1. Зайти на https://github.com. Если аккаунта нет — зарегистрироваться (бесплатно,
   банковская карта не требуется): кнопка **Sign up**, e-mail, пароль, имя пользователя.
   Имя пользователя дальше обозначаем `<user>`.
2. Создать репозиторий: https://github.com/new → **Repository name**: `walkquest-packages`
   → тип **Public** (важно: Private не даст публичных ссылок) → галочку
   «Add a README» ставить не обязательно → **Create repository**.
3. Записать себе адрес репозитория: `https://github.com/<user>/walkquest-packages`.
   От него строится `<BASE>`:

   ```
   <BASE> = https://github.com/<user>/walkquest-packages/releases/download/packages-v1
   ```

## 2. Подготовка manifest.json (≈2 мин)

4. На своём компьютере создать файл `manifest.json` (Блокнот → Сохранить как → тип
   «Все файлы», имя `manifest.json`, кодировка UTF-8) с содержимым ниже, заменив
   `<user>` на своё имя пользователя GitHub:

```json
{
  "baseUrl": "https://github.com/<user>/walkquest-packages/releases/download/packages-v1",
  "packages": [
    {
      "regionId": "spb-city",
      "packageVersion": 1,
      "formatVersion": 1,
      "file": "region-spb-city-v1.zip",
      "sizeBytes": 28929684,
      "sha256": "0e453f155d99e052f13319fbed4e8d84a8dd9fd595c79ffbb241a54cfef1d42d",
      "bbox": [30.042, 59.796, 30.63, 60.071],
      "name": "Санкт-Петербург"
    }
  ]
}
```

   Если репозиторий назван иначе (не `walkquest-packages`) — подставить его имя вместо
   `walkquest-packages`. Значения `sizeBytes` и `sha256` не трогать: они реальные.

   Опциональные поля записи (история 1.10, дефект D3 прогона 2026-10-05):
   - `name` — человекочитаемое имя региона в UI (вкладка «Регионы», карточки
     предложения). Без него приложение показывает сырой `regionId` («spb-city») —
     недружелюбно; при следующей публикации манифеста поле добавить.
   - `bbox` ([minLon, minLat, maxLon, maxLat], значения — из `meta.json` пакета) —
     нужен детекту «локация в неустановленном регионе» и предложению домашнего
     региона по геолокации без скачивания пакета.
   Оба поля не обязательны: старые манифесты без них остаются валидными.

## 3. Релиз и загрузка файлов (≈5 мин)

5. В репозитории открыть вкладку **Releases** (справа на главной странице репозитория
   или адрес `https://github.com/<user>/walkquest-packages/releases`) →
   **Draft a new release** (или **Create a new release**).
6. Поле **Choose a tag** → ввести `packages-v1` → нажать **Create new tag: packages-v1
   on publish** (ветку-target не менять, останется main/master).
7. **Release title**: `Пакеты регионов v1`. Описание (необязательно):
   `region-spb-city v1, formatVersion 1`.
8. В блоке **Attach binaries** перетащить два файла:
   - `tools/region-pack/out/region-spb-city-v1.zip` — **имя файла не менять**,
     оно часть URL-схемы (`region-<regionId>-v<N>.zip`);
   - `manifest.json`, созданный на шаге 4.
   Дождаться окончания загрузки обоих (зелёные галочки у имён файлов).
9. Нажать **Publish release** (не «Save draft»).

## 4. Проверка публичного доступа (≈3 мин)

10. В браузере в режиме «Инкогнито» (чтобы точно без авторизации) открыть:

    ```
    https://github.com/<user>/walkquest-packages/releases/download/packages-v1/manifest.json
    ```

    Ожидание: отображается JSON из шага 4, никакой страницы входа. Затем открыть

    ```
    https://github.com/<user>/walkquest-packages/releases/download/packages-v1/region-spb-city-v1.zip
    ```

    Ожидание: начинается скачивание файла ~28,9 МБ.

    Альтернатива в терминале: `curl -I <URL zip>` → `HTTP/2 200` (GitHub может ответить
    `302` на objects.githubusercontent.com — это нормально, файл качается).

11. Сверить контрольную сумму скачанного файла (Windows PowerShell):

    ```
    certutil -hashfile "$env:USERPROFILE\Downloads\region-spb-city-v1.zip" SHA256
    ```

    Ожидание: `0e453f155d99e052f13319fbed4e8d84a8dd9fd595c79ffbb241a54cfef1d42d`.

## 5. Финальный шаг — сообщить оркестратору

12. Сообщить оркестратору адрес репозитория (`https://github.com/<user>/walkquest-packages`).
    По нему будет заполнена константа `DEFAULT_BASE_URL` в
    `app/src/main/java/app/walkquest/data/packages/PackageSource.kt` (история 1.5) —
    это единственная правка в коде приложения.

Готово: пакет публичен, URL-схема 1.4 (`<BASE>/manifest.json`, `<BASE>/region-<id>-v<N>.zip`)
работает, 1.5 может потреблять манифест.

## 6. Альтернатива на будущее: Cloudflare R2

Изначально (2026-10-04, утро) основным выбирался Cloudflare R2 (10 ГБ free tier, бесплатный
egress, CDN). Отложено: Cloudflare требует привязку банковской карты даже для free tier —
неприемлемо. Вернуться к R2 стоит только если понадобится CDN/свой домен: меняется
только `baseUrl` в манифесте и `DEFAULT_BASE_URL`, URL-схема та же.

## 7. Правила на будущее

- Новая версия пакета = **новый файл** `region-spb-city-v2.zip` (старые версии не затираем)
  + обновлённый `manifest.json`. Загрузка — в тот же релиз `packages-v1`: Edit release →
  удалить старый ассет `manifest.json` → загрузить новый (zip новой версии добавить рядом).
- Манифест обновляется при каждой публикации; приложение (1.5) качает сначала его.
- Лимиты GitHub Releases: ассет до 2 ГиБ, до 1000 ассетов на релиз, без лимита на суммарный
  размер и трафик — сотни версий пакетов влезают.
- Если понадобится скорость/CDN — см. раздел 6: меняется только `<BASE>`.
