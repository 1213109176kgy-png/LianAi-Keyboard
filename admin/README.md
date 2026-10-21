# LianAi Admin — 恋爱键盘升级包管理后台

技术栈：React 18 + Ant Design 5（Vite）+ Express + 阿里云 OSS（ali-oss）。

## 功能

- 登录（默认账号 `admin` / `admin123`，首次登录后请修改密码）
- OSS 配置：Region / Bucket / AccessKey / 存储路径前缀，支持连接测试；**未配置 OSS 时上传会被拒绝并提示先去配置**
- 版本管理：上传 APK（自动计算 SHA-256 与大小并直传 OSS）、编辑更新说明、删除
- 支付配置：当前订阅购买仅开放支付宝手机网站支付，支持密钥保存和参数完整性校验；**密钥只保存在服务端 db.json，接口仅回显「已保存」状态，留空表示沿用**
- 套餐管理：维护订阅套餐（名称、描述、有效期、价格、每日 AI 额度、排序、上/下架），支持新增、编辑、删除
- 模型配置：官方 AI 通道的模型池（DeepSeek / GLM / Kimi / 自定义），含能力标签（文本/视觉）、参考价格与密钥管理；**密钥只存服务端，启用前必须配置 Key**
- 小米 MiMo：预设 `mimo-v2.5-asr`、`mimo-v2.5`、`mimo-v2.5-pro`，支持「语音识别」能力；业务配置可选择上架且有密钥的语音模型。
- 模型成本：模型调用记账明细 + 经营统计（VIP 总收入、模型调用成本、利润、按模型/功能汇总）；AI 代理上线后每次转发自动记账
- 对 App 暴露的升级接口：`GET /api/app/latest`，返回 `{versionCode, versionName, apkUrl, sha256, sizeBytes, notes}`
- 对 App 暴露的套餐接口：`GET /api/app/plans`，返回已上架套餐 `[{id, name, description, periodDays, priceFen, dailyQuota, sortOrder}]`
- 对 App 暴露的模型接口：`GET /api/app/models`，返回已上架且已配置密钥的模型 `[{id, provider, displayName, capabilities, inputPriceFen, outputPriceFen}]`

## 使用

```bash
npm install          # 安装全部依赖（workspaces）
npm run dev          # 同时启动后端 :3210 与前端 :5173（/api 已代理）
npm run build        # 构建前端到 web/dist
npm start            # 生产模式：Express 同时提供 API 与静态页面（:3210）
```

## 部署到服务器

后台常驻在阿里云服务器（lianai.bahuang.tech → nginx → 127.0.0.1:3210，PM2 进程名 `lianai-admin`）。
本机已配置 SSH 免密登录，每次改完代码执行一条命令即可发布：

```bash
bash scripts/deploy.sh
```

脚本流程：本地构建前端 → rsync 服务端代码与 `web/dist` 到 `/www/wwwroot/lianai-admin`（扁平布局）→
服务器 `npm install --omit=dev` → `pm2 restart lianai-admin` → 验证线上接口。
线上 `data/db.json`（账号、OSS、支付密钥、版本记录）不会被部署覆盖。

数据（账号、OSS 配置、支付密钥、版本记录）持久化在 `server/data/db.json`，请勿提交到仓库。
OSS AccessKey 与支付密钥只保存在服务端，前端仅回显脱敏后的状态。

## 支付宝订阅支付

当前 App 只开放支付宝手机网站支付（`alipay.trade.wap.pay`），不探测或降级到电脑网站支付。支付宝开放平台的应用需审核上线，并绑定「手机网站支付」产品。后台填写 AppID、应用私钥、支付宝公钥和服务公网 HTTPS 地址；收款方用户 ID 可选，用于额外核对收款方。

- `GET /api/app/payment/channels`：当前可用支付方式。
- `POST /api/app/orders`：传入 `planId`、`channel: "alipay"`、可选的幂等 `requestId`，返回订单和支付链接；订单有效期 15 分钟。免费套餐每账号每套餐只能领取一次。
- `GET /api/app/orders/:id`：仅订单所属用户可访问；后端主动向支付宝查单并验证响应签名，成功后开通或顺延订阅。
- `POST /api/pay/notify/alipay`：验签并核对应用、订单、金额及已配置的收款方；重复通知不会重复开通，延迟到达的真实付款仍可入账。
- `/h5/alipay-return.html`：付款或退出后返回 App 的页面，本身不作为付款凭证。

App 持久化最近一笔待付款订单及请求标识，支持返回 App 后自动查单、重启后恢复、继续付款和手动查询。重新选择套餐会创建新订单，不受旧的未支付订单阻拦；旧订单保留在后台，后续收到真实付款仍可正常入账。只有继续重试同一次下单网络请求时才复用请求标识。

验证：`npm --workspace server test` 运行隔离的签名、订单、查单和通知测试，不读写业务数据库、不产生实际支付。部署使用 `server/package-lock.json` 安装生产依赖；线上 `data/` 不会被同步覆盖。

2026-10-14 验收：8 项后端支付测试和 48 项 Android 单元测试通过，后台前端与调试 APK 构建通过；后端已部署，新 APK 已安装至 Android 10 真机。真机确认了套餐读取、支付宝单一入口、0.01 元订单创建、支付宝网关打开、返回页唤回 App、进程重启后恢复订单及手动查询。测试订单未付款，原有免费体验订阅保持不变。支付宝应用仍在审核中，网关返回 `isv.not-online-app`；实际扣款、正式异步回调与到账开通仍需应用上线且开通手机网站支付后验证。

## 订单管理与人工补单

管理后台新增「订单管理」（`/orders`），支持订单号、手机号、套餐、平台交易号搜索，按支付平台及支付状态筛选和分页。订单详情展示金额、套餐有效期、每日额度、支付时间、平台交易号、权益入账后的到期时间和补单记录。超时状态按付款截止时间展示，不会仅因查看列表改写业务数据。

- `GET /api/orders`、`GET /api/orders/:id`：管理员鉴权后查询订单及用户当前权益，付款链接与登录凭据不回显。
- `POST /api/orders/:id/complete`：管理员补单，可传入选填的 `reason`（最多 200 字）。将订单改为支付成功，按订单创建时保存的有效期和每日额度发放权益，有效期从现有未到期时间顺延。记录操作人、时间、备注及人工补单来源。
- 重复点击或重启后重试补单不会重复发放；补单后再收到支付宝真实回调，只补充经验证的平台交易号，不会再次发放权益。写盘失败会回滚订单和权益，便于安全重试。

2026-10-17：12 项后端测试通过，覆盖换套餐生成独立订单、补单鉴权与筛选、重复补单、订单快照发放、延迟真实回调、异常回滚；后台已构建并部署，新调试 APK 已安装至真机。人工补单界面与权益入账在隔离测试数据中验证。

## App 配置与语音识别

- `GET /api/app/config`：公开的版本化配置接口（`schemaVersion: 1`），`features.visionReply` 和 `features.speechRecognition` 返回开关、模型 ID、展示名、厂商、模型名和服务端代理路径。仅在已启用、模型上架、具有对应能力且密钥已配置时返回可用。响应不含 API Key 或厂商接口地址，并禁止缓存。
- `GET /api/app/business`：保留旧版功能开关契约。
- `GET /api/business`、`PUT /api/business`：管理员读取/更新业务配置。语音字段为 `speechRecognition: { enabled, modelId }`，未传入的功能保持原配置；保存时检查能力、上架状态及密钥。
- `POST /api/app/speech/transcribe`：需要 App 登录，提交 `{ format: "wav", audioBase64 }`，音频为 16kHz、单声道、16 位 PCM WAV，最长 90 秒。服务端使用当前选定的模型及密钥调用小米兼容接口，返回 `{ text, modelId, displayName, requestId }`，并记入「模型成本」。客户端无法覆盖模型或 API Key。

App 在调出输入法时刷新配置，听写、普通语音及语音润色共用此默认通道；本地已配置的自定义 ASR 接口仍优先使用。后台关闭功能、下架模型或移除密钥后，服务端立即停止处理该官方通道的请求。语音和识别文本不写入业务数据库。

语音识别参考价格以「元/小时」配置，字段 `audioPriceFenPerHour` 存储每小时的分数，MiMo V2.5 ASR 预设为 50（0.50 元/小时）。成功识别后，按已校验 PCM 的实际音频秒数 ÷ 3600 × 每小时单价记账，与上游 Token 数无关；单次记录保存时长、单价快照和计价单位，成本分数保留六位小数。文本/视觉继续使用已有 Token 计价，历史记录不重算。模型成本和用户用量页展示音频时长及小额成本。

小米协议依据：[MiMo 语音识别官方文档](https://mimo.mi.com/docs/zh-CN/api/audio/Speech-Recognition)。

## VIP 与用户自配模型

`GET /api/app/config` 可匿名读取功能元数据，`user` 为 null；携带 App Bearer token 时，返回当前用户基本信息及 `user.vip`（是否有效、套餐、到期时间、剩余天数、每日额度）。无效或过期 token 返回 401，响应为 `private, no-store` 并按 Authorization 区分缓存。不会返回其他用户或服务商密钥。

业务增加 `replySuggestions`（粘贴TA的话/帮你回）和 `superSpeak`（超会说），字段均为 `{ enabled, modelId }`，只允许绑定已启用、已配置密钥、支持文本的模型。与截图、语音业务分别保存，未提供的配置保持原值。

- `POST /api/app/text/reply/chat/completions`、`POST /api/app/text/polish/chat/completions`：OpenAI 文本消息格式，支持普通响应和 SSE。必须登录且 VIP 未到期，服务端选择对应业务模型，忽略客户端 model/密钥/地址覆盖，成功调用记账。
- 截图识别服务端接口同样强制校验 VIP；非会员/过期会员返回 403 `VIP_REQUIRED`，不请求上游。
- 基础语音识别保持原有登录要求，不参与 VIP 控制，也不要求用户配置文本模型。
- App「设置 → AI与数据 → 大模型配置」维护本机模型库，帮你回、截图识别回复、超会说分别绑定其中一个模型。VIP 且业务就绪时优先官方服务；否则直连所选自配模型。无可用模型时弹窗提供开通 VIP、去配置模型入口。
- 旧文本模型迁移为模型库记录并保留原有帮你回、超会说选择；本机模型密钥继续使用 Keystore 加密保存。账号切换、过期或配置刷新失败时不沿用旧会员权限，会员身份不写入磁盘配置缓存。

验证：`npm --prefix server test` 包含配置脱敏、旧数据缺省、模型校验、鉴权、切换模型、音频格式、上游异常和并发限制等测试；`npm run build` 验证管理页面。
