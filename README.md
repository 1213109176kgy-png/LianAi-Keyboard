# LianAi Keyboard

<p align="center"><img src="docs/images/logo.webp" alt="LianAi Keyboard" width="128" /></p>

An open-source AI-assisted Android keyboard for everyday typing, reply suggestions and message polishing.

[简体中文](README_CN.md) · [Download](https://github.com/1213109176kgy-png/LianAi-Keyboard/releases/latest) · [Privacy](PRIVACY.md) · [GPL-3.0-or-later](LICENSE)

Current release: **v1.4.8**. Requires Android 13 or later and an arm64-v8a device.

## Features

- Offline full-Pinyin and nine-key Chinese input, English, symbols, Emoji and candidates.
- “Help Me Reply”: analyze pasted messages and return three selectable replies.
- “Super Say”: polish a draft into three alternatives before inserting it into the host app.
- Configurable prompts for partners, friends, family, colleagues, customers and other relationships.
- DeepSeek, Kimi, Qwen, Doubao and other presets, plus custom OpenAI-compatible endpoints.
- User-supplied API credentials protected by Android Keystore.

<p align="center">
  <img src="docs/images/keyboard-26.png" alt="26-key Pinyin" width="31%" />
  <img src="docs/images/keyboard-9.png" alt="Nine-key Pinyin" width="31%" />
  <img src="docs/images/model-settings.png" alt="Model settings" width="31%" />
</p>

## Install

Download the APK from [GitHub Releases](https://github.com/1213109176kgy-png/LianAi-Keyboard/releases/latest), enable LianAi Keyboard in Android input-method settings, then configure your own text-model endpoint under **Profile → Settings → Model Configuration**. ASR is optional and collapsed by default.

## Build

JDK 17, Android SDK 35, NDK 26.1 and Gradle 8.7 are required.

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

## License and attribution

LianAi Keyboard is a derivative of Vertick IME and uses Trime/librime, Rime-Ice, cppjieba, Lucide and other open-source components. It is distributed under [GPL-3.0-or-later](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for provenance and third-party licenses.

## Developer and support

Developer: **Kuang Guangyuan, Shenzhen, China**. Contact and voluntary-donation QR codes are available in the [Chinese README](README_CN.md). Donations are optional and do not affect features, updates or licensing.
