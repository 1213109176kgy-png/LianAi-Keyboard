# LianAi Admin — 恋爱键盘升级包管理后台

技术栈：React 18 + Ant Design 5（Vite）+ Express + 阿里云 OSS（ali-oss）。

## 功能

- 登录（默认账号 `admin` / `admin123`，首次登录后请修改密码）
- OSS 配置：Region / Bucket / AccessKey / 存储路径前缀，支持连接测试；**未配置 OSS 时上传会被拒绝并提示先去配置**
- 版本管理：上传 APK（自动计算 SHA-256 与大小并直传 OSS）、编辑更新说明、删除
- 支付配置：当前订阅购买仅开放支付宝手机网站支付，支持密钥保存和参数完整性校验；**密钥只保存在服务端 db.json，接口仅回显「已保存」状态，留空表示沿用**
- 套餐管理：维护订阅套餐（名称、描述、有效期、价格、每日 AI 额度、排序、上/下架），支持新增、编辑、删除
- 模型配置：官方 AI 通道的模型池（DeepSeek / GLM / Kimi / 自定义），含能力标签（文本/视觉）、参考价格与密钥管理；**密钥只存服务端，启用前必须配置 Key**
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
