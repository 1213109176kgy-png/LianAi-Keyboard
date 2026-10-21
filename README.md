# LianAi Keyboard

<p align="center"><img src="docs/images/logo.webp" alt="LianAi Keyboard" width="128" /></p>

An open-source AI-assisted Android keyboard for everyday typing, reply suggestions and message polishing.

[简体中文](README_CN.md) · [Download](https://github.com/1213109176kgy-png/LianAi-Keyboard/releases/latest) · [Privacy](PRIVACY.md) · [GPL-3.0-or-later](LICENSE)

Current release: **v1.5.3**. Requires Android 13 or later and an arm64-v8a device.

## Features

- Offline full-Pinyin and nine-key Chinese input, English, symbols, Emoji and candidates.
- “Help Me Reply”: analyze pasted messages and return three selectable replies.
- “Super Say”: polish a draft into three alternatives before inserting it into the host app.
- Configurable prompts for partners, friends, family, colleagues, customers and other relationships.
- DeepSeek, Kimi, Qwen, Doubao and other presets, plus custom OpenAI-compatible endpoints.
- User-supplied API credentials protected by Android Keystore.
# 恋爱键盘（LianAi Keyboard）

<p align="center"><img src="docs/images/logo.webp" alt="恋爱键盘 Logo" width="128" /></p>

<p align="center">一款面向 Android 的开源 AI 恋爱输入法：正常打字，也能帮你回、帮你说。</p>

<p align="center">
  <a href="https://github.com/1213109176kgy-png/LianAi-Keyboard/releases/latest">下载最新版</a> ·
  <a href="README.md">English</a> ·
  <a href="LICENSE">GPL-3.0-or-later</a> ·
  <a href="PRIVACY.md">隐私说明</a>
</p>

> 当前版本：**v1.5.3**。最低支持 Android 13（API 33），当前提供 arm64-v8a 安装包。

## 项目简介

恋爱键盘由“键盘管理 App + Android 输入法”组成。用户可以使用 26 键或九宫格进行离线中文输入，也可以在聊天时调用自己配置的大模型生成三条回复建议、分析对方情绪，或优化自己准备发送的话。

项目不绑定固定模型厂商。文本模型支持 DeepSeek、Kimi、通义千问、豆包、硅基流动等预设，也支持用户填写 OpenAI Chat Completions 兼容接口地址、模型名和 API Key。API Key 使用 Android Keystore 加密保存，不会提交到本仓库。

## 主要功能

- **基础输入法**：26 键拼音、九宫格拼音、英文、数字、符号、Emoji、候选词、删除与光标编辑。
- **帮你回**：粘贴对方的话，分析情绪并生成三条可点击使用的回复；支持重新分析。
- **超会说**：先在键盘内部输入草稿，一键优化为三条备选话术，再选择写入聊天输入框。
- **关系提示词**：通用、心动对象、恋人、朋友、家人、同事、客户、上司等关系可分别配置系统提示词。
- **多模型配置**：选择厂商、接口类型、API 地址、Key 和模型；支持自定义兼容接口。
- **本地优先**：拼音与基础候选离线运行；剪贴板历史、听写历史默认关闭。

## 界面截图

<p align="center">
  <img src="docs/images/keyboard-26.png" alt="26 键拼音键盘" width="31%" />
  <img src="docs/images/keyboard-9.png" alt="九宫格拼音键盘" width="31%" />
  <img src="docs/images/model-settings.png" alt="大模型与提示词配置" width="31%" />
</p>

## 安装与使用

1. 前往 [GitHub Releases](https://github.com/1213109176kgy-png/LianAi-Keyboard/releases/latest) 下载 v1.5.3 APK。
2. 安装后打开“恋爱键盘”，按照首页四步引导启用并切换输入法。
3. 进入“我的 → 设置 → 大模型配置”，选择文本模型厂商并填写接口地址、模型和 API Key。
4. 在微信、短信或其他应用中点击输入框，切换到恋爱键盘即可使用。

ASR 语音接口当前不是必填项，默认折叠；只使用文字键盘、“帮你回”和“超会说”时无需配置。

## 大模型配置示例

以 DeepSeek 为例：选择 DeepSeek 厂商，使用应用预设的官方地址或填写兼容接口地址，模型可填写账号支持的 `deepseek-chat`，最后填写自己的 API Key。

请勿在 Issue、截图或聊天记录中公开 API Key。调用费用和内容政策由你所选择的模型服务商决定。

## 源码范围

本仓库提交 Android App（`app/`）及配套资源。管理后台与支付服务作为本地工程维护，`admin/` 不纳入版本控制。

品牌原图位于 `branding/logo-source.png`，运行 `python3 scripts/generate_brand_assets.py` 可重新导出 App、后台和文档使用的图标资源。

## Android 本地构建

环境要求：JDK 17、Android SDK 35、NDK 26.1、Gradle 8.7。

默认语音识别通过 `GET /api/app/config` 获取后台选定的语音模型，并通过登录后的 `POST /api/app/speech/transcribe` 识别。MiMo 密钥仅保存在后台，不再注入 APK。用户仍可在 App 内配置自己的 ASR 接口。

```powershell
$env:JAVA_HOME='C:\path\to\jdk17'
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

公开发布前运行 `gradle verifyOpenSourceRelease lintDebug --no-daemon`。

## 隐私与安全

- 密钥由 Android Keystore 保护，不写入源码、构建参数或日志。
- 拼音词典和基础候选在本机运行。
- 只有主动点击 AI 功能时，相关文本才会发送到用户配置的模型接口。
- 请勿在密码、验证码、银行卡等敏感输入框中使用 AI 功能。
- 完整数据边界见 [PRIVACY.md](PRIVACY.md)，安全问题请参照 [SECURITY.md](SECURITY.md)。

## 开源许可与致谢

本项目基于 Vertick IME 进行二次开发，并使用 Trime/librime、Rime-Ice、cppjieba、Lucide 等开源项目。项目依据 [GNU GPL v3 or later](LICENSE) 开源；第三方项目、版本及许可证详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

欢迎提交 Issue、功能建议和 Pull Request。二次分发或修改时，请继续遵守 GPL 及第三方组件许可证。

## 开发者与交流

开发者：**邝广元（广东 · 深圳）**

欢迎扫码添加微信好友，交流使用体验、功能建议或参与开发。

<p align="center"><img src="docs/images/developer-wechat.jpg" alt="开发者微信二维码" width="360" /></p>
<p align="center"><img width="360" height="544" alt="image" src="https://github.com/user-attachments/assets/91576b94-a225-43b8-b2d7-36209bd71b50" /></p>


## 随缘打赏

如果这个项目对你有帮助，欢迎随缘打赏。打赏完全自愿，不影响软件功能、更新或开源许可。

<p align="center"><img src="docs/images/donation-wechat.jpg" alt="微信支付打赏二维码" width="360" /></p>

---

请只从本项目官方 GitHub/Gitee 仓库下载 APK，并在安装前核对 Release 页面公布的 SHA-256。


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
