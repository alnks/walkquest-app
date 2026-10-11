# Подпись release-сборки WalkQuest (история 6.4)

Google Play требует, чтобы APK/AAB подписывались своим ключом (debug-ключ принят не будет).
Сборка читает параметры из `local.properties` (файл не коммитится).

## 1. Сгенерировать keystore (один раз)

В корне проекта:

```bash
keytool -genkeypair -v -keystore walkquest-release.keystore -alias walkquest \
  -keyalg RSA -keysize 2048 -validity 10950
```

Ключ спросит пароли хранилища и ключа и данные владельца. **Сохраните пароли
и файл keystore в надёжное место** — потеря ключа = невозможность обновлять
приложение в Play.

## 2. Прописать в local.properties

```properties
release.keystore=walkquest-release.keystore
release.keystore.password=<пароль хранилища>
release.key.alias=walkquest
release.key.password=<пароль ключа>
```

Путь `release.keystore` относительно корня проекта (можно абсолютный).

Пока эти ключи не заданы или файл не существует, release подписывается
debug-ключом — сборка и установка на устройство работают, в Play публиковать нельзя.

## 3. Собрать и проверить

```bash
gradlew :app:assembleRelease
```

```bash
"%ANDROID_HOME%\build-tools\<версия>\apksigner.bat" verify --print-certs ^
  app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

В выводе должен быть сертификат с вашим alias `walkquest`, а не `Android Debug`.
