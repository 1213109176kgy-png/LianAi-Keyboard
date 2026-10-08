# Changelog

## 1.5.3 - 2026-10-08

- 重整九宫格拼音与数字键盘布局，支持「123 / 拼音」往返切换。
- 完善英文 QWERTY 与两页常用符号，英文页左下角使用返回按钮回到九宫格拼音。
- 点击标点键输入逗号，并显示可选的常用标点。
- 统一普通键盘字母、数字和符号的 18sp 字号，以及 7dp 按键间距。
- 统一底部地球、返回、麦克风图标为 24dp，保留独立点击区域，修复按钮遮挡与触摸冲突。
- 隐藏支持设备上的系统 IME 底部重复按钮，保留系统手势导航。
- 缺少麦克风权限时显示明确提示；AI 本地建议按实际错误区分配置、鉴权、网络等原因。


## 1.5.2 - 2026-09-10

### 简体中文

- 管理 App 页面字号规范化：页面标题、区块标题和卡片主标题统一为 14sp，说明文字与副标题统一为 12sp，降低大模型配置页和设置页的视觉压迫感。
- 优化设置管理页结构与图标呈现，补齐权限管理图标，统一深浅色下的底栏和卡片配色。
- 修复点击输入框外空白区域无法收起软键盘的问题，避免键盘遮挡确认按钮等操作。
- 修复英文大写状态导致按键被拉高变形的问题，并调整 Shift 图标显示。
- 大模型配置页支持保存后回读提示词内容，避免提示词修改后看起来未生效。
- 中文主键盘默认改为九宫格拼音，未保存过设置的新用户首次打开即使用九宫格。

### English

- Standardized management-app typography: page titles, section titles and card titles now use 14sp; helper and subtitle text uses 12sp.
- Refined the settings hub structure and icons, including the permissions tile, with consistent light/dark colors.
- Dismisses the soft keyboard when tapping outside focused inputs so covered action buttons can be reached.
- Fixed distorted key height when switching to uppercase and adjusted the Shift icon.
- Model prompt fields now reload saved content after saving, making prompt changes visibly persistent.
- The default Chinese keyboard layout is now nine-key Pinyin for users without a saved preference.

## 1.5.1 - 2026-09-05

### 简体中文

- 修复部分机型（Android 15 16KB 内存页内核，如新版 HyperOS）安装报错「安装失败 -2」的问题：改为压缩打包 native 库，不再依赖 4KB 页对齐。
- 签名方案调整为 v1 文件 + v2 签名块，兼容更多国产 ROM 安装器。

### English

- Fixed install failure (error -2) on devices with 16KB page kernels (Android 15): native libraries are now compressed, removing the 4KB page-alignment requirement.
- Signature scheme adjusted to v1 files + v2 block for broader ROM installer compatibility.

## 1.5.0 - 2026-09-05

### 简体中文

- Emoji 面板全面重做：新增常用/笑脸/心情/手势/动物/食物/符号 7 个分类、分页浏览（约 300 个表情）、面板内删除键，告别 20 个表情的单页时代。
- 符号页新增第二页：补齐《》〈〉「」『』【】〔〕……——·～￥%‰℃ 等中文聊天常用符号，底栏新增 1/2 翻页键。
- 英文 Shift 键改为标准行为：单击只大写下一个字母，双击锁定大写（锁定时高亮显示），再按解锁。
- 新增按键预览气泡：按下字母/数字/符号键时在键位上方显示放大字符，打字反馈更清晰。
- 回车键跟随输入框语义：搜索框显示「搜索」并执行搜索、登录页显示「下一步」、多行输入显示「换行」直接换行，不再一律强制「发送」（修复在搜索框/登录页回车无效的问题）。

### English

- Rebuilt the emoji panel: 7 categories (recent, faces, hearts, gestures, animals, food, symbols), paging with ~300 emojis, and an in-panel delete key.
- Added a second symbols page with CJK brackets and typographic marks (《》【】…… —— ￥ ℃ etc.) plus a 1/2 page toggle.
- English shift now behaves like stock keyboards: tap once for a single capital, double-tap to lock caps (highlighted), tap again to unlock.
- Added key-press preview bubbles above letter, number and symbol keys.
- The enter key now follows the focused field's IME action (Search / Next / Done / newline) instead of always forcing Send, fixing dead enter presses in search bars and login forms.

## 1.4.9 - 2026-09-05

### 简体中文

- 修复长按退格连删时误清整个拼音组合串的问题，长按现在只做连续删除。
- 修复英文模式切换到其他键盘时未上屏字母丢失的问题，切换前会自动提交已输入内容。
- 修复从语音/帮你回/剪贴板进入符号页后返回被强制切到英文键盘的问题，现在恢复原模式。
- 九键拼音字表改为引擎加载时在后台线程预热，首次九键输入不再卡顿。
- 深色模式全面适配：帮你回/AI优化等卡片、管理 App 底栏、功能卡、导航高亮不再出现白底浅字。
- 管理App 修复「优化输入」页标题渲染在内容之后的问题；移除 uiMode 配置避免切深色不刷新。
- 横屏键盘高度按屏幕自适应（260-340dp），按键恢复可触控尺寸。
- 按键文字跟随系统字体大小（sp，上限 1.3 倍）；按键触摸增加 6dp 容差，快速滑动点击不再丢字。
- 键盘视图脱离窗口时清理长按/连删回调，避免残留触发。

### English

- Fixed long-press backspace accidentally clearing the whole composition; it now only repeats deletion.
- Fixed English letters being lost when switching keyboard modes; unfinished input is committed first.
- Fixed symbol page returning to English instead of the original mode (voice / ask / clipboard).
- Pre-warmed the nine-key character table on the Rime worker thread to remove first-tap jank.
- Full dark-mode pass for reply/polish cards and the management app (bottom bar, feature cards, nav highlight).
- Fixed the Optimize Input page header order; theme changes now properly recreate the activity.
- Landscape keyboard height adapts to the screen (260-340dp) for tappable keys.
- Key labels respect system font scale (sp, capped at 1.3x); added 6dp touch slop to reduce dropped taps.
- Cleaned up pending long-press/repeat callbacks on detach.

## 1.4.8 - 2026-08-27

### 简体中文

- 完成恋爱键盘管理 App 的首页、键盘、个人中心与设置结构，增加快速上手、版本管理、开发者微信和随缘打赏页面。
- 完成“帮你回”和“超会说”三条候选交互，候选仅在用户点击关闭按钮后消失，并支持关系类型独立提示词。
- 修复拼音原始组合串丢失、候选提交与光标编辑问题，完善 26 键、九宫格、英文、符号和 Emoji 基础输入能力。
- 统一 26 键五排按键的高度、间距、圆角和文字垂直居中；普通键盘与“超会说”共用一致布局。
- 文本模型支持多厂商与自定义 OpenAI 兼容接口；ASR 移到配置页底部并默认折叠。
- 更新应用 Logo、桌面图标、关于页面及开源发布文档。

### English

- Reworked the management app and completed the reply/polish three-option workflows.
- Fixed Pinyin composition, candidate commit and cursor-editing issues across full and nine-key layouts.
- Standardized full-keyboard geometry and added multi-provider/custom model configuration.
- Updated branding, developer contact, donation and public-release documentation.

## 1.4.3 - 2026-07-22

### English

- Made key feedback more immediate and stable: keys now acknowledge press before release while preserving slide-out cancellation, multi-touch input, and release-to-commit behavior. Pinyin work is batched on a dedicated native dispatcher to reduce fast-typing latency without dropping input.
- Added the Vertick Know workflow agent. It routes voice commands to answer, exact replacement, rewrite, polish, continue, summarize, expand, translate, proofread, format, extract, and reply tasks. Text-changing operations use a guarded editor snapshot and never alter sensitive fields.
- Added named text ranges for commands such as “replace the content in parentheses with …”, “replace quoted content with …”, and “replace selected text with …”.
- Added provider-aware ASR routing for MiMo-compatible, OpenAI Audio Transcriptions-compatible, and DashScope realtime services, with clear unsupported-provider feedback.
- Added About & Help, an in-app usage guide with real Lucide icons, and GitHub Releases update checks that direct downloads to vertick.cn. The Vertick Know mode now uses the Lucide `wand-sparkles` icon.
- Tightened candidate, deletion, sound, haptic, and rendering paths to avoid stale UI, delayed feedback, trailing audio, and invalid grapheme deletion.

### 简体中文

- 优化按键跟手性：按下即提供视觉、音效与触感反馈，仍保持松手上屏、滑出取消与多指独立输入。拼音输入改由专用原生队列按帧合并，快打不丢字。
- 新增“维刻知道”工作流智能体：支持问答、精确替换、修改、润色、续写、总结、扩写、翻译、改错、整理格式、信息提取和生成回复。文本修改使用会话校验，敏感输入框不会被读取或修改。
- 支持自然语言范围替换，例如“把括号里的内容换成……”“把引号里的内容改成……”和“把选中的内容改成……”。
- ASR 新增按厂商协议路由，支持 MiMo 兼容、OpenAI Audio Transcriptions 兼容和 DashScope 实时服务，并对暂不支持的配置给出明确提示。
- “关于”升级为“关于与帮助”，新增带真实 Lucide 图标的使用指引；测试页新增 GitHub Releases 检查更新，发现新版本后引导前往 vertick.cn 下载。维刻知道模式图标改为 `wand-sparkles`。
- 收紧候选词、删除、按键音、触感与渲染链路，减少过期界面、反馈延迟、音效拖尾和 Emoji 等组合字符误删。

## 1.4.2 - 2026-07-21

### English

- Fixed a native Rime composition leak after tapping a Pinyin candidate. The decoder now clears its internal raw composition as part of every native candidate commit, so subsequent input never appends to an earlier Pinyin string.
- Preserved the 1.4.1 atomic editor-side candidate commit, ensuring the selected text is committed once while both the editor and decoder sessions are reset together.

### 简体中文

- 修复点选拼音候选词后 librime 内部原始拼音组合态残留的问题。现在每次原生候选提交都会同步清空引擎组合态，后续输入不会拼接到此前的拼音字符串。
- 保留 1.4.1 的编辑器端原子候选提交逻辑，确保候选词只上屏一次，并让编辑器与解码器会话同步重置。

## 1.4.1 - 2026-07-21

### English

- Fixed a candidate-selection race in some editors. Tapping a Pinyin candidate now replaces and commits the active composing text in one batch, preventing the selected text from disappearing or being duplicated after Enter.
- Added keyboard-height and bottom-offset controls, plus an optional full-width cursor slider that works consistently across text, voice, answer, and clipboard surfaces.
- Added an optional recent-clipboard quick-paste capsule. It is memory-only, appears briefly after a recent copy, shows the copied content with a one-way smooth scroll, and never overlaps the mode selector.
- Added configurable comma/period shortcut keys for Pinyin and English layouts, refined their alignment, and improved dark-theme pressed-key feedback.
- Refined voice controls when the cursor slider is enabled: send, delete, @, and the optional input-method picker now use a stable two-column layout.

### 简体中文

- 修复部分应用中点选拼音候选词后内容暂时消失、回车后重复上屏的问题。现在候选词会在同一批处理中替换并提交组合态。
- 新增键盘高度、底部间距调节，以及可选的全宽底部光标滑块；文字、听写、问答和剪贴板界面保持一致避让。
- 新增可选的最近复制快速粘贴胶囊。内容仅保存在内存中，短暂展示复制文本并单向平滑滚动，不会遮挡右上角模式选择。
- 新增拼音和英文键盘的逗号、句号快捷键，并优化标点对齐与暗色模式按键按下反馈。
- 开启光标滑块时，听写/润色控制区改为稳定的双列布局，发送、删除、@ 与可选输入法切换按钮不再互相遮挡。

## 1.4.0 - 2026-07-21

### English

- Rebuilt offline Chinese input around librime and precompiled Rime-Ice tables. Full Pinyin and nine-key input now share the native candidate engine, verified base package, and local user learning path.
- Added optional checksum-verified enhanced dictionary downloads plus an independent Wanxiang long-sentence grammar package. A failed download or validation never replaces the active offline package.
- Added fully offline next-word suggestions and bounded local word-pair learning. Predictions are derived from redistributable dictionary data and are never sent to a cloud service or stored as a browsable input history.
- Consolidated the management experience, expanded cloud model configuration, and removed unreachable legacy pages and unused release resources.
- Hardened release documentation, dependency notices, dictionary provenance, and privacy boundaries for public distribution.

### 简体中文

- 以 librime 与预编译 Rime-Ice 表重构离线中文输入。全键盘拼音和九宫格共用原生候选引擎、已校验的基础词典包和本机学习链路。
- 新增带校验的增强词典下载，以及独立的万象长句语法包。下载或校验失败不会替换当前可用的离线词典。
- 新增完全离线的联想词和有上限的本机词对学习。预测索引来自可再分发词典数据，不会请求云端，也不会保存可浏览的完整输入历史。
- 整合管理页与云端模型配置，删除不可达旧页面和未使用的发布资源。
- 补强公开发布所需的文档、第三方声明、词典来源说明与隐私边界。

## 1.3.2 - 2026-07-20

### English

- Reworked offline Pinyin ranking with a larger prebuilt Rime-Ice frequency overlay, per-reading candidate weights, stronger whole-phrase ranking, conservative typo recovery, and on-device confirmed-candidate learning.
- Added deterministic structured-expression validation after polishing. Spoken lists such as “first, second, third” now remain numbered, line-separated output even when a cloud model returns a single paragraph.
- Added real keyboard previews for appearance and candidate-size settings, a dedicated input test page, and refined theme-aware segmented controls, switches, sliders, and guidance styling.
- Rebuilt cloud configuration around searchable provider selection. ASR and text models can independently use Xiaomi MiMo, Xiaomi MiMo Plan, or a custom OpenAI-compatible endpoint; model IDs can be loaded from `/v1/models` and selected in-app.

### 简体中文

- 重构离线拼音候选排序：引入更大的 Rime-Ice 词频覆盖、按读音保存候选权重、整词优先排序、保守纠错与本机确认候选学习。
- 新增润色后的确定性结构化校验。“第一、第二、第三”等口述清单即使被云端模型返回为一段文字，也会稳定整理为带换行的编号列表。
- 外观与候选词大小设置新增真实键盘预览；新增测试页，并统一优化分段控件、开关、滑块和操作指引的主题样式。
- 云端连接改为可搜索的服务商选择。ASR 与文本模型可分别使用 Xiaomi MiMo、Xiaomi MiMo Plan 或自定义 OpenAI 兼容接口；支持读取 `/v1/models` 并在应用内选择模型。

## 1.3.1 - 2026-07-18

### English

- Rebuilt the in-app management experience around a single Management landing page. Settings are now grouped into focused secondary pages for layout, key effects, auxiliary input, toolbar customization, keyboard management, clipboard, local data, input optimization, permissions, cloud connections, and About.
- Redesigned secondary-page options as large rounded cards with Vertick-blue segmented controls, sliders, and animated toggles. Keyboard-mode and nine-key-symbol ordering remain drag-and-drop.
- Replaced all screenshot-based setting instructions with native, lightweight operation guides. Each guide is rendered above its setting title and control, uses the current app theme, and does not ship external image assets.
- Returning to a management page now restores its previous scroll position instead of resetting the page to the top.

### 简体中文

- 重构应用内管理页：以统一的“管理”首页作为入口，将设置拆分为布局和显示、按键效果、辅助输入、定制工具栏、键盘管理、剪贴板、本机数据、优化输入、权限管理、云端连接和关于等独立二级页。
- 二级页选项统一改为大圆角卡片，使用维刻主题蓝色的分段控件、滑块和带动画的开关；键盘模式与九宫格符号仍支持拖动排序。
- 删除设置页中的截图式操作说明，全部替换为原生轻量操作指引。指引位于每个选项的标题与控件上方，自动适配应用主题，也不再携带外部图片资源。
- 从二级页返回时会恢复此前的阅读进度，不再自动回到页面顶部。

## 1.3.0 - 2026-07-18

### English

- Fixed unsolicited landscape keyboard opening during rotation, notification-shade changes, and IME view recreation. The floating keyboard now opens only from a fresh editor show request.
- Refined the landscape header with a compact mode selector, an aligned `chevron-down` hide control, centered nine-key letters, and no numeric labels in landscape nine-key mode.
- Added a shared portrait/landscape Hide-key setting, stronger nine-key T9 recovery, and candidate fallback protection.
- Added seven candidate-text size levels, English sentence-initial auto-capitalization, and optional double-space period entry.
- Replaced the keyboard key-click audio.

### 简体中文

- 修复横屏旋转、下拉状态栏和输入法视图重建时自动调出键盘的问题；悬浮键盘仅在新的文本输入请求时显示。
- 优化横屏顶部模式切换与 `chevron-down` 收起键布局；九宫格横屏隐藏数字编码并居中显示字母。
- 新增横竖屏共用的收起键开关，强化九宫格 T9 恢复与候选兜底。
- 新增候选词七档大小、英文句首自动大写和双击空格输入句号设置。
- 替换按键音效。

## 1.2.9 - 2026-07-17

### English

- Rebuilt the landscape keyboard as an independent top-level floating window. It no longer reserves IME bottom insets or moves the focused editor upward.
- The landscape surface now uses a fixed `1.7:1` width-to-height ratio, remains within 60% of the display height, and renders at 80% opacity.
- The keyboard can be dragged freely across the landscape screen from the logo area. Dragging is disabled while candidates are visible so the first candidates remain directly tappable.
- Reduced all landscape letter-key labels to 80% of their portrait size for the compact floating layout.
- Fixed reopening the floating keyboard after it is dismissed: input-view and show requests are now scheduled from the service main loop, independent of a detached keyboard view.

### 简体中文

- 横屏键盘改为独立的顶层悬浮窗口，不再占用系统输入法底部空间，也不会顶起当前文本框。
- 横屏悬浮键盘固定为 `1.7:1` 宽高比，高度不超过屏幕的 60%，整体透明度为 80%。
- 可从 Logo 区域在横屏内任意拖动键盘；候选栏显示时自动关闭拖动热区，前几个候选词可以正常点选。
- 横屏文字键盘的字母字号缩小至竖屏的 80%，适配紧凑悬浮布局。
- 修复关闭横屏键盘后再次点击文本框无法调起的问题：输入视图和显示请求统一由输入法服务主线程重新创建悬浮窗口。

## 1.2.4 - 2026-07-17

### English

- Fixed IME state loss during landscape rotation. Recreating the input view now preserves the active keyboard surface and restores unfinished Pinyin or English composition instead of resetting the keyboard.
- Fixed the last composing letter being accidentally committed during rotation. This also preserves the internal nine-key code so candidates remain correct after restoring the view.
- Voice recording is safely cancelled during a rotation; no stale recording or network result can be inserted into the newly recreated input connection.
- Added a lightweight mode restore for Xiaomi system cases that recreate the IME service itself during rotation. It stores only the last keyboard mode, never typed text, audio, candidates, or cloud credentials.

### 简体中文

- 修复横屏切换时输入法状态丢失：输入视图重建后会保留当前键盘界面，并恢复未上屏的拼音或英文组合态，不再回到初始状态。
- 修复旋转过程中最后一个组合字母意外上屏；九宫格会保留原始数字编码，恢复后候选词保持正确。
- 横竖屏切换会安全停止录音，不会把失效录音或旧网络结果写入新输入框。
- 针对小米系统旋转时可能重建输入法服务的情况，新增轻量模式恢复。仅保存上次键盘模式，绝不保存输入文本、录音、候选词或云端凭据。

## 1.2.3 - 2026-07-16

### English

- The complete Rime-Ice Pinyin table is now precompiled and bundled with the APK. New installations unpack and load it directly instead of compiling 1.8 million dictionary entries on the phone.
- First-run typing is available immediately after the bundled table is copied; dictionary preparation no longer blocks the keyboard or consumes substantial memory.
- Fixed the Rime readiness check so it validates the loaded prebuilt schema once without repeatedly reopening or rebuilding the dictionary.
- Added a candidate safety fallback: an incompatible prebuilt table cannot leave the candidate strip empty; typing remains usable while the dictionary is recovered.

### 简体中文

- 完整 Rime-Ice 拼音词典表现已预编译并随 APK 提供。新安装仅解压、加载词典，不再在手机上编译约 188 万词条。
- 首次进入即可使用手打键盘；词典准备不再阻塞输入或占用大量内存。
- 修复 Rime 就绪检测：仅验证一次已加载的预编译方案，不再反复重载或重建词典。
- 增加候选保护：预编译表异常时不会留下空的“正在匹配中”候选栏，手打仍可继续使用。

## 1.2.2 - 2026-07-16

### English

- Hardened Rime-Ice deployment: the health check now allows a realistic first-build window and automatically removes an incomplete compiled table before rebuilding once.
- Polishing and translation long-press UI no longer overlaps the listening capsule. Dragging into translation now swaps the visual emphasis between the polish area and translation capsule; drag to the top-right close control to cancel recording.
- Long-press Delete now accelerates smoothly with one matching haptic pulse per deleted character.
- Direct dictation no longer inserts streaming ASR deltas. Text is cleaned locally, including punctuation preferences, before it is committed once.
- Reordered the nine-key keypad with the separator key first, and moved sidebar punctuation 3dp right for optical centering.

### 简体中文

- 强化 Rime-Ice 词典部署：首次完整编译获得更合理的健康检查时长；失败时会自动清理不完整编译表并重建一次。
- 长按润色/翻译界面不再与拾音胶囊重叠；拖入翻译区时润色区与翻译胶囊深浅互换，拖到右上角关闭按钮可直接取消录音。
- 所有删除键长按连续删除时会平滑加速，并保持每删除一个字符对应一次触感反馈。
- 听写不再流式直写；识别完成后先执行本地清洗和标点习惯处理，再一次性提交文本。
- 九宫格将分隔符键调整到首位并重排数字，左侧符号整体右移 3dp 以获得更好的视觉居中。

## 1.2.1 - 2026-07-16

### English

- First-run Pinyin is now usable immediately while the complete Rime-Ice dictionary is verified in the background. Typing, deletion, custom-word matching, and explicit raw-Pinyin commit remain available instead of blocking on “Preparing dictionary”.
- Redesigned the nine-key Chinese layout with a vertically scrollable punctuation sidebar, three-column keypad, dedicated delete key, two-cell newline area, full-width spacebar, and the existing `123` symbol page.
- Added Settings controls to add, edit, delete, and drag-sort nine-key sidebar punctuation. The default set is `， 。 ？ ！ … ： 、 ～`.
- Fixed MiMo ASR JSON `null` values and trailing `null` artifacts appearing in dictation output.

### 简体中文

- 首次使用时，完整 Rime-Ice 词典仍在后台校验和编译，但拼音键盘已可立即输入、删除、命中自定义词并提交原始拼音，不再被“词典准备中”阻塞。
- 重做九宫格中文布局：左侧可上下滑动的符号栏、三列九键、独立删除键、两格高换行区、三格空格键和原有 `123` 数字符号页。
- 设置页新增九宫格侧边符号管理，可添加、编辑、删除和拖动排序；默认符号为 `， 。 ？ ！ … ： 、 ～`。
- 修复 MiMo ASR 返回 JSON `null` 或末尾 `null` 被写入听写结果的问题。

## 1.2.0 - 2026-07-16

### English

- Redesigned the long-press polish gesture with a rising lower semicircle, a floating irregular translation capsule, and smooth selection feedback.
- Long press the voice button to begin a polish recording. Release in the lower area to polish as before, or slide upward to **English (US)** and release to translate after ASR.
- Added a dedicated American English translation prompt to the configured text model. The original ASR text is preserved if translation fails or times out.

### 简体中文

- 重做长按润色手势界面：下方半圆遮罩平滑升起，并显示包裹遮罩的异形翻译胶囊。
- 长按录音键开始润色录音，在下方区域松开保持润色；向上滑入“英语（美国）”区域后松开，则 ASR 识别后调用文本模型翻译。
- 文本模型新增美式英语翻译提示词；翻译失败或超时时自动保留原始 ASR 转写。

## 0.1.3 - 2026-07-16

### English

- English keyboard symbols now use English punctuation instead of Chinese punctuation.
- Added independent multi-touch key handling, allowing simultaneous two-hand typing without dropping a key.
- Added OpenAI-compatible SSE streaming for direct ASR dictation. Text is inserted as ASR deltas arrive; providers without streaming support automatically fall back to the standard request.
- Added a Chinese primary-keyboard setting: full 26-key Pinyin or offline Rime T9 nine-key Pinyin.

### 简体中文

- 英文键盘的符号页改为英文标点，不再统一显示中文符号。
- 增加独立多点触控按键处理，双手同时输入时不会丢失按键。
- 听写支持 OpenAI 兼容 SSE 流式 ASR，识别增量到达时立即写入；服务端不支持流式时自动回退普通请求。
- 设置页新增“中文主键盘”，可选 26 键全键盘拼音或离线 Rime 九宫格拼音。

## 0.1.2 - 2026-07-16

### English

- Fixed the final uncommitted Pinyin initial being confirmed when composition is cleared. Deleting an unfinished composition now removes every letter without requiring a second delete.
- Fixed keyboard touch handling: sliding away from a pressed key cancels it instead of inserting the key under the finger when released.
- Removed Send, Delete, and `@` controls from Smart Q&A mode.
- The Smart Q&A processing capsule now displays `Vertick Knows` (维刻知道) with the same progress animation used by dictation polishing.
- Custom-term updates now stage their Rime data in the background for the next deployment and never restart the active decoder, eliminating the stall when returning to the keyboard after editing the dictionary.

### 简体中文

- 修复拼音组合态清空时末位字母被确认上屏的问题；未上屏拼音可以一次删完，不再需要多按一次删除。
- 修复按键点选逻辑：按住按键后滑出原按键会取消输入，松开时不会误输入滑动位置的字母。
- 智能问答模式移除发送、删除和 `@` 按钮。
- 智能问答处理胶囊改为显示“维刻知道”，并沿用听写润色的进度动画。
- 词典更新只在后台写入下次 Rime 部署所需数据，不再重启正在使用的解码器，修复新增词典后切回键盘卡顿。

## 0.1.1 - 2026-07-16

### English

- Fixed IME state loss after landscape rotation by handling configuration changes in the input-method service and preserving the active keyboard mode.
- Added a compact landscape layout for the keyboard and voice panel.
- Pre-warms and keeps the offline Rime engine alive while the IME service is running, avoiding repeated dictionary preparation when returning to text input.
- Prevented unfinished Pinyin letters from being committed while typing or deleting. Deleting `nihao` down to `n` no longer inserts an extra `n`.
- Pressing Send with a Pinyin composition now commits the literal composition first, for example `dfsh`; press Send again to submit it to the target app.
- Added immediate local matching for custom terms, including full Pinyin and initial abbreviations such as `weike` and `wk` for `维刻`.
- Deferred Rime custom-dictionary rebuilds so adding professional terms does not interrupt active typing.

### 简体中文

- 修复横屏切换后输入法重置或不可用：输入法服务自行处理配置变化，并保留当前键盘模式。
- 增加横屏下更紧凑的键盘与语音面板布局。
- 输入法服务启动后预热并保持 Rime 引擎，避免每次进入手打键盘重复出现“词典准备中”。
- 修复拼音输入、退格时未完成字母意外上屏；`nihao` 删除到 `n` 不会再额外上屏一个 `n`。
- 拼音组合态按发送会先提交原始组合，例如 `dfsh`；再次按发送才向目标应用提交。
- 自定义词支持完整拼音和首字母缩写即时匹配，例如“维刻”可由 `weike` 或 `wk` 命中。
- 专业词与打字词典的 Rime 重编译改为延后执行，新增词条不会中断当前输入。
