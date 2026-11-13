# Glide TV

Оптимизатор для Android TV, Google TV, Fire TV и ТВ-приставок на Android: очистка кеша и мусора,
освобождение памяти, отключение встроенного мусорного софта, ускорение запуска приложений.

* **Без компьютера и без Shizuku.** Режим «Турбо» использует встроенный ADB-клиент: пользователь
  один раз включает отладку на ТВ и подтверждает запрос.
* **Android 5.0+** (Fire OS 5 и старые приставки), RU/EN.
* **Честные цифры:** показывается только реально освобождённое место и RAM.
* **Ничего не удаляется:** системные приложения только отключаются (`pm disable-user`), кнопка «Вернуть всё».

## Возможности по уровням

| | Без настройки | Турбо (встроенный ADB) | Root |
|---|---|---|---|
| RAM / хранилище | ✅ | ✅ | ✅ |
| Освободить RAM | ✅ Android ≤ 13 | ✅ все версии (`am kill-all`) | ✅ |
| Кеш всех приложений | ✅ только Android 5.x | ✅ `pm trim-caches` | ✅ |
| Мусорные файлы (APK, логи, миниатюры) | ✅ | ✅ (+ выдаёт себе «Доступ ко всем файлам») | ✅ |
| Отключение лишних приложений | — | ✅ (кроме Fire OS 5) | ✅ |
| Анимации, ускорение запуска (`cmd package compile -m speed`) | — | ✅ | ✅ |

## Как включается «Турбо»

| Устройство | Что включить | Как подключается Glide TV |
|---|---|---|
| Fire TV (Fire OS 5–8) | Developer options → ADB debugging | `127.0.0.1:5555`, один раз «Разрешить» |
| Android TV ≤ 13 | Для разработчиков → Отладка по USB (+ по сети) | `127.0.0.1:5555` (или найденный порт), «Разрешить» |
| Android TV 14+ / Google TV Streamer | Отладка по Wi-Fi → «Подключить по коду» | QR → страница в локальной сети → ввод 6 цифр с телефона → TLS |

После подключения приложение выдаёт **только себе** `WRITE_SECURE_SETTINGS`, `GET_USAGE_STATS`,
`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW` и после перезагрузки само включает отладку
по Wi-Fi и переподключается. Все shell-команды типизированы (`engine/ShellCmd.kt`), произвольных строк нет.

Не поддерживаются (APK там не ставится): Samsung Tizen, LG webOS, Roku, Hisense VIDAA, Fire TV на Vega OS.

## Сборка

Нужны JDK 17 и Android SDK 35 (или просто Android Studio).

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug              # проверка NewApi (вызовы новых API без проверки версии)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Репозиторий для автообновлений задаётся свойством `glide.updateRepo` (в CI подставляется автоматически):

```bash
./gradlew :app:assembleRelease -Pglide.updateRepo=<owner>/<repo>
```

## Windows: заливка и релизы в один клик

| Файл (в корне проекта) | Что делает |
|---|---|
| `push_to_github.bat` | Первый раз: `git init`, коммит, создаёт репозиторий (через GitHub CLI) или спрашивает его адрес, `push`. Дальше — коммит и `push` изменений |
| `setup_release_keys.bat` | Один раз: создаёт ключ подписи в `%USERPROFILE%\GlideTV-keys` (вне проекта) и записывает 4 секрета в GitHub (нужен GitHub CLI) |
| `make_release.bat` | Поднимает версию, коммитит, ставит тег `vX.Y.Z` и отправляет — GitHub Actions соберёт и опубликует релиз |

Нужны Git for Windows (скрипт предложит поставить через winget) и, желательно, GitHub CLI:
`winget install GitHub.cli`, затем `gh auth login`. Репозиторий должен быть **публичным** — иначе
приложение не увидит релизы для автообновления.

## Релизы через GitHub Actions

1. Один раз создайте ключ подписи (**храните его и пароли в надёжном месте**: без него нельзя будет выпустить обновление):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias glide -keyalg RSA -keysize 4096 -validity 36500
   base64 -w0 release.jks > release.jks.b64
   ```
2. В репозитории: Settings → Secrets and variables → Actions → добавьте
   `GLIDE_KEYSTORE_BASE64` (содержимое `release.jks.b64`), `GLIDE_KEYSTORE_PASSWORD`, `GLIDE_KEY_ALIAS`, `GLIDE_KEY_PASSWORD`.
3. Поднимите `versionCode`/`versionName` в `app/build.gradle.kts`, затем:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
   Workflow соберёт подписанный APK и опубликует GitHub Release с `SHA256SUMS.txt`.
   Приложение находит релиз через GitHub API, проверяет SHA-256 и подпись и предлагает установить.

Каждый push в `main` и каждый PR собирает debug-APK (артефакт `glide-tv-debug`).

## Обновление базы лишних приложений

```bash
git clone --depth 1 https://github.com/Gathin23/cleanyourtv /tmp/cyt
python3 tools/gen_debloat_db.py /tmp/cyt     # → app/src/main/assets/debloat_db.json
```

Логика галочек (`debloat/DebloatPlanner.kt`):

* никогда не показываются: ядро Android и ТВ-middleware (тот же список, что в CleanYourTV `disable.sh`),
  всё, что может быть Home, обработчики входов HDMI/каналов, настройки, клавиатуры, пакеты с `TV_INPUT_HARDWARE`,
  `setupwraith`, Play/GMS/WebView, сервисы пульта;
* приложения с иконкой показываются, но **не отмечаются** — это выбор пользователя;
* отмечаются фоновые пакеты, отключённые на этой модели или на ≥ 80 % из ≥ 3 других ТВ;
* «осторожно» (голос, Chromecast, заставка, спец. возможности) — показываются с предупреждением, не отмечаются.

## Структура

```
app/src/main/java/app/glidetv/
├── GlideApp.kt, AppGraph.kt          # Application, ручной DI
├── engine/                           # ShellCmd (белый список команд), EngineManager, TurboSetup
│   ├── adb/                          # GlideAdbManager (libadb), AdbEngine, PairingServer/Discovery, BootReceiver
│   └── root/RootEngine.kt
├── system/                           # PlatformInfo, RAM, хранилище, приложения, спец. доступы
├── clean/                            # Optimizer, LegacyCacheCleaner (5.x), RamCleaner, JunkScanner
├── debloat/                          # DebloatDb, CoreGuard, Planner, Executor, Journal
├── update/                           # UpdateChecker (GitHub Releases), ApkProvider
└── ui/                               # MainActivity + экраны на классических View (без AndroidX)
```

UI сделан на классических View без AndroidX/Compose: Compose 1.10+ и свежие AndroidX требуют minSdk 23,
а приложению нужен Android 5 и минимальное потребление памяти на ТВ с 1 ГБ RAM.

## Лицензии

См. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
