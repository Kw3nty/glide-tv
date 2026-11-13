# Сторонние компоненты

| Компонент | Лицензия | Где используется |
|---|---|---|
| [CleanYourTV](https://github.com/Gathin23/cleanyourtv) — списки `examples/*/debloat.txt` | MIT, © 2026 Gathin | `app/src/main/assets/debloat_db.json` (сгенерирован `tools/gen_debloat_db.py`), полный текст лицензии: `app/src/main/assets/licenses/CleanYourTV-LICENSE.txt` |
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) | GPL-3.0-or-later **или** Apache-2.0 (используем Apache-2.0) | встроенный ADB-клиент |
| [spake2-java](https://github.com/MuntashirAkon/spake2-java) | Apache-2.0 | зависимость libadb (сопряжение) |
| [Bouncy Castle](https://www.bouncycastle.org/) | MIT | сертификат ADB-ключа |
| [ZXing core](https://github.com/zxing/zxing) | Apache-2.0 | QR-код страницы сопряжения |
| [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | Apache-2.0 | доступ к системному Conscrypt для TLS-сопряжения |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | асинхронность |
