# LianAi Keyboard

<p align="center"><img src="docs/images/logo.webp" alt="LianAi Keyboard" width="128" /></p>

An open-source Android keyboard for everyday typing, reply suggestions and message polishing.

[简体中文](README_CN.md) · [Download](https://lianai.bahuang.tech/h5/) · [Changelog](CHANGELOG.md) · [Privacy](PRIVACY.md) · [GPL-3.0-or-later](LICENSE)

Current source version: **v1.6.12** (versionCode **52**). Requires Android 10 or later and an arm64-v8a device. Public download channels may publish builds separately from source updates.

## Features

- Offline full-Pinyin and nine-key Chinese input, English, symbols, Emoji and candidates.
- **Help Me Reply**: generate three selectable replies from a pasted message or a selected chat screenshot.
- **Super Say**: polish a draft into three alternatives before inserting it into the host app.
- Voice dictation: hold the mic key to transcribe speech into the input field.
- Relationship-specific prompts for partners, friends, family, colleagues, customers and other relationships.
- Official subscription routes or user-configured model endpoints, including provider presets and supported OpenAI, Anthropic and Gemini protocols.
- API credentials protected by Android Keystore.

## Local knowledge documents

The APK includes two system documents: **狗头军师知识库** and **童锦程知识库**. Reading them requires no download or network access. AI generation still uses the configured model service.

- System documents can be read, enabled or disabled, and set as the default. They cannot be edited or deleted.
- The list overflow menu includes **Set as default (设为默认)**. This also selects that document for Help Me Reply and Super Say; a disabled target is enabled. Later manual keyboard selections, including no knowledge, remain authoritative.
- Personal documents can be created, imported from Markdown/plain text, edited and deleted. A personal document with a system document's title remains personal.
- Markdown reading supports headings, emphasis, lists, quotes, code, tables and task lists. Detail pages hide the main navigation; system detail has no editing or save controls.
- Parsed Markdown is cached and the bundled documents are prepared from the list, avoiding repeated full parsing on each open.
- System document updates preserve enable states, personal documents and existing selection.

Knowledge selection appears at the bottom of the reply panel and between the language and microphone controls in Super Say. New installations initially select 狗头军师知识库. Selecting **未选择知识库** omits reference material.

AI requests include bounded excerpts from the selected document (up to about 4,800 characters of reference material), rather than the entire library. Retrieval preserves Markdown chapter context and ranks phrases alongside common communication intents such as comfort, invitations and conflict repair. If nothing matches, the document's opening guidance is used. Read failures are reported instead of silently skipping the selected document. Screenshot replies first obtain visual context, then use the reply text route with knowledge to produce the final suggestions; this can involve two model calls. Source metadata and license notices are kept outside the reference text.

## Install and use

1. Download a published APK from the [official download page](https://lianai.bahuang.tech/h5/).
2. Open the app, enable LianAi Keyboard in Android settings, and select it as the input method.
3. Use an available official subscription route or configure your own models under **Profile → Settings → Model Configuration**.
4. Open **Knowledge (知识库)** to read documents or set a default, then use the keyboard in your chat app.

ASR configuration is optional for typing and text AI features. Supported model-specific thinking settings apply to the relevant configured routes. Model calls require access to the selected service; offline typing and document reading do not.

## Build and validation

Use JDK 17, Android SDK 35, NDK 26.1 and Gradle 8.7.

```powershell
$env:JAVA_HOME='C:\path\to\jdk17'
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Device regressions use a separate application ID to preserve the signed installation and account/model settings:

```powershell
gradle :app:connectedDebugAndroidTest -PtouchRegression=true --no-daemon
```

Before public distribution, also run `gradle verifyOpenSourceRelease lintDebug --no-daemon`. See [CONTRIBUTING.md](CONTRIBUTING.md) for real-editor and sensitive-field checks.

The repository contains the Android app and its resources. The local administration/payment project under `admin/`, credentials, signing material, APKs and private device screenshots are excluded from Git.

## Privacy

Knowledge documents remain in app-private storage; they are not automatically synced. Only active AI actions send relevant text and selected excerpts to the configured route. Sensitive/password fields disable AI features. Ordinary typing does not send knowledge material. See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## License and attribution

LianAi Keyboard derives from Vertick IME and uses Trime/librime, Rime-Ice, cppjieba, Lucide, Markwon and other components. It is distributed under [GPL-3.0-or-later](LICENSE). The two adapted knowledge documents have MIT notices bundled separately from their prompt text. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for licenses and provenance.

## Developer and support

Developer: **Kuang Guangyuan, Shenzhen, China**. Contact and voluntary-donation QR codes are available in the [Chinese README](README_CN.md). Donations are optional and do not affect features, updates or licensing.
