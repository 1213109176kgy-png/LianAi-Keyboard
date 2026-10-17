import express from 'express'
import multer from 'multer'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { load, save, hash, hashWithSalt, randomToken, createSession, resolveSession, destroySession } from './store.mjs'
import { installSubscriptionPayments } from './subscription-payments.mjs'
import { installOrderManagement } from './order-management.mjs'

import {
  ossConfigured,
  publicUrl,
  objectKey,
  testConnection,
  uploadFile,
  deleteObject,
  sha256File,
  presignPutUrl
} from './oss.mjs'

const PORT = Number(process.env.PORT || 3210)
const db = load()
const app = express()
app.set('trust proxy', true)
// 微信回调验签需要原始报文字节，必须先于 JSON 解析器注册
app.use('/api/pay/notify/wechat', express.raw({ type: '*/*', limit: '1mb' }))
app.use(express.json({ limit: '10mb' }))
// 支付宝异步通知以 form 表单提交
app.use(express.urlencoded({ extended: false, limit: '1mb' }))

const uploads = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, os.tmpdir()),
    filename: (req, file, cb) => cb(null, `lianai-upload-${Date.now()}-${Math.random().toString(16).slice(2)}.apk`)
  }),
  limits: { fileSize: 400 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    if (path.extname(file.originalname).toLowerCase() !== '.apk') {
      cb(new Error('只能上传 .apk 安装包'))
      return
    }
    cb(null, true)
  }
})

const requireAuth = (req, res, next) => {
  const header = req.headers.authorization || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : ''
  const session = resolveSession(token)
  if (!session) {
    res.status(401).json({ message: '请先登录' })
    return
  }
  req.username = session.username
  next()
}

const wrap = handler => (req, res) => {
  Promise.resolve(handler(req, res)).catch(error => {
    console.error('[api]', error)
    if (!res.headersSent) {
      res.status(error.status || 500).json({ message: error.message || '服务器内部错误' })
    }
  })
}

const badRequest = message => Object.assign(new Error(message), { status: 400 })

// ---- auth ----
app.post('/api/login', wrap(async (req, res) => {
  const { username, password } = req.body || {}
  if (username !== db.user.username || hash(String(password || '')) !== db.user.passwordHash) {
    res.status(401).json({ message: '账号或密码错误' })
    return
  }
  res.json({ token: createSession(db.user.username), username: db.user.username })
}))

app.post('/api/logout', requireAuth, (req, res) => {
  destroySession((req.headers.authorization || '').slice(7))
  res.json({ ok: true })
})

app.get('/api/me', requireAuth, (req, res) => {
  res.json({ username: req.username })
})

app.post('/api/password', requireAuth, wrap(async (req, res) => {
  const { oldPassword, newPassword } = req.body || {}
  if (hash(String(oldPassword || '')) !== db.user.passwordHash) {
    res.status(400).json({ message: '原密码不正确' })
    return
  }
  if (String(newPassword || '').length < 6) {
    res.status(400).json({ message: '新密码至少 6 位' })
    return
  }
  db.user.passwordHash = hash(newPassword)
  save()
  res.json({ ok: true })
}))

// ---- oss config ----
app.get('/api/oss', requireAuth, (req, res) => {
  const config = db.oss
  res.json({
    configured: ossConfigured(config),
    config: config
      ? {
          region: config.region,
          bucket: config.bucket,
          accessKeyId: config.accessKeyId,
          accessKeySecretMasked: config.accessKeySecret
            ? `已保存（尾号 ${config.accessKeySecret.slice(-4)}）`
            : '',
          pathPrefix: config.pathPrefix || '',
          cdnHost: config.cdnHost || ''
        }
      : null
  })
})

app.put('/api/oss', requireAuth, wrap(async (req, res) => {
  const { region, bucket, accessKeyId, accessKeySecret, pathPrefix, cdnHost } = req.body || {}
  if (!region || !bucket || !accessKeyId || (!accessKeySecret && !db.oss)) {
    res.status(400).json({ message: 'Region、Bucket、AccessKey ID 和 AccessKey Secret 均不能为空' })
    return
  }
  db.oss = {
    region: String(region).trim(),
    bucket: String(bucket).trim(),
    accessKeyId: String(accessKeyId).trim(),
    // 留空表示沿用已保存的 Secret
    accessKeySecret: accessKeySecret ? String(accessKeySecret).trim() : db.oss.accessKeySecret,
    pathPrefix: String(pathPrefix || '').trim(),
    // 如 dl.bahuang.tech；安装包下载地址将优先使用该域名（OSS 默认域名禁止分发 APK）
    cdnHost: String(cdnHost || '').replace(/^https?:\/\//, '').replace(/\/+$/, '')
  }
  save()
  res.json({ ok: true })
}))

app.post('/api/oss/test', requireAuth, wrap(async (req, res) => {
  const body = req.body || {}
  const config = body.bucket && body.accessKeyId
    ? {
        ...db.oss,
        ...body,
        accessKeySecret: body.accessKeySecret || db.oss?.accessKeySecret
      }
    : db.oss
  if (!ossConfigured(config)) {
    res.status(400).json({ message: '请先填写并保存 OSS 配置' })
    return
  }
  const location = await testConnection(config)
  res.json({ ok: true, location })
}))

// Pre-signed upload URL: the browser streams the APK straight to OSS,
// bypassing this server's tiny uplink entirely.
app.post('/api/oss/presign', requireAuth, wrap(async (req, res) => {
  if (!ossConfigured(db.oss)) {
    res.status(400).json({ message: '尚未配置阿里云 OSS，请先在「OSS 配置」中完成设置' })
    return
  }
  const raw = String(req.body?.fileName || '').trim()
  if (!raw) {
    res.status(400).json({ message: '缺少文件名' })
    return
  }
  const key = objectKey(db.oss, raw)
  res.json({
    url: presignPutUrl(db.oss, key),
    key,
    apkUrl: publicUrl(db.oss, key)
  })
}))

// ---- payment config ----
const PAYMENT_CHANNELS = ['wechat', 'alipay', 'aggregator']
const PAYMENT_CHANNEL_LABELS = { wechat: '微信支付', alipay: '支付宝', aggregator: '聚合支付' }
const PAYMENT_SECRET_FIELDS = {
  wechat: ['apiV3Key', 'merchantPrivateKey', 'platformPublicKey'],
  alipay: ['privateKey', 'alipayPublicKey'],
  aggregator: ['appSecret']
}
const PAYMENT_REQUIRED_FIELDS = {
  wechat: {
    appId: 'AppID',
    mchId: '商户号',
    merchantSerialNo: '商户证书序列号',
    apiV3Key: 'APIv3 密钥',
    merchantPrivateKey: '商户 API 私钥'
  },
  alipay: {
    appId: 'AppID',
    privateKey: '应用私钥',
    alipayPublicKey: '支付宝公钥',
    gatewayUrl: '网关地址'
  },
  aggregator: {
    provider: '聚合服务商',
    appId: 'AppID',
    appSecret: 'AppSecret',
    gatewayUrl: '网关地址'
  }
}

const isSecretField = (channel, field) => PAYMENT_SECRET_FIELDS[channel].includes(field)

const channelReady = (channel, name) =>
  Object.keys(PAYMENT_REQUIRED_FIELDS[name]).every(field =>
    isSecretField(name, field) ? Boolean(channel[field]) : Boolean(String(channel[field] || '').trim())
  )

// 密钥只返回「是否已保存」，其余字段原样回显，方便核对。
function paymentView(payment) {
  const channels = {}
  for (const name of PAYMENT_CHANNELS) {
    const channel = payment[name]
    const view = { enabled: Boolean(channel.enabled), ready: channelReady(channel, name) }
    for (const [field, value] of Object.entries(channel)) {
      if (field === 'enabled') continue
      if (isSecretField(name, field)) view[`${field}Saved`] = Boolean(value)
      else view[field] = value
    }
    channels[name] = view
  }
  const base = String(payment.publicBaseUrl || '').replace(/\/+$/, '')
  return {
    publicBaseUrl: base,
    enabledChannels: PAYMENT_CHANNELS.filter(name => payment[name].enabled && channels[name].ready),
    notifyUrls: base
      ? {
          wechat: `${base}/api/pay/notify/wechat`,
          alipay: `${base}/api/pay/notify/alipay`,
          aggregator: `${base}/api/pay/notify/aggregator`
        }
      : null,
    channels
  }
}

app.get('/api/pay/config', requireAuth, (req, res) => {
  res.json(paymentView(db.payment))
})

app.put('/api/pay/config', requireAuth, wrap(async (req, res) => {
  const body = req.body || {}
  const base = String(body.publicBaseUrl || '').trim().replace(/\/+$/, '')
  if (base && !base.startsWith('https://')) {
    res.status(400).json({ message: '服务公网地址必须以 https:// 开头（微信/支付宝要求回调地址为 HTTPS）' })
    return
  }
  // 先在副本上合并并校验，失败时绝不落盘，也避免半套配置驻留内存。
  const next = JSON.parse(JSON.stringify(db.payment))
  next.publicBaseUrl = base
  for (const name of PAYMENT_CHANNELS) {
    const incoming = body[name]
    if (!incoming || typeof incoming !== 'object') continue
    const target = next[name]
    for (const field of Object.keys(target)) {
      if (field === 'enabled') continue
      const value = incoming[field]
      if (value === undefined) continue
      if (isSecretField(name, field)) {
        // 密钥留空表示沿用已保存的值
        if (String(value).trim() !== '') target[field] = String(value).trim()
      } else {
        target[field] = String(value).trim()
      }
    }
    target.enabled = Boolean(incoming.enabled)
  }
  const problems = []
  for (const name of PAYMENT_CHANNELS) {
    if (!next[name].enabled) continue
    const missing = Object.entries(PAYMENT_REQUIRED_FIELDS[name])
      .filter(([field]) => (isSecretField(name, field) ? !next[name][field] : !String(next[name][field] || '').trim()))
      .map(([, label]) => label)
    if (missing.length) {
      problems.push(`「${PAYMENT_CHANNEL_LABELS[name]}」已启用但缺少：${missing.join('、')}`)
    }
  }
  if (problems.length) {
    res.status(400).json({ message: `${problems.join('；')}。请补全参数，或先关闭该通道的启用开关再保存。` })
    return
  }
  db.payment = next
  save()
  res.json(paymentView(db.payment))
}))

// ---- plans（订阅套餐） ----
const sortedPlans = () =>
  [...db.plans].sort((a, b) => a.sortOrder - b.sortOrder || a.createdAt - b.createdAt)

// 校验并归一化套餐输入；价格单位为分，0 表示免费套餐。
function parsePlan(body) {
  const name = String(body?.name || '').trim()
  const description = String(body?.description || '').trim()
  const periodDays = Number(body?.periodDays)
  const priceFen = Number(body?.priceFen)
  const dailyQuota = Number(body?.dailyQuota ?? 0)
  const sortOrder = Number(body?.sortOrder ?? 0)
  if (!name) throw badRequest('请填写套餐名称')
  if (name.length > 30) throw badRequest('套餐名称不能超过 30 字')
  if (description.length > 200) throw badRequest('套餐描述不能超过 200 字')
  if (!Number.isInteger(periodDays) || periodDays < 1 || periodDays > 3650) {
    throw badRequest('有效期必须是 1~3650 的整数天数')
  }
  if (!Number.isInteger(priceFen) || priceFen < 0 || priceFen > 100_000_000) {
    throw badRequest('价格必须是 0~100 万元的金额（免费套餐填 0）')
  }
  if (!Number.isInteger(dailyQuota) || dailyQuota < 0) {
    throw badRequest('每日 AI 额度必须是不小于 0 的整数（0 表示不限）')
  }
  if (!Number.isInteger(sortOrder)) throw badRequest('排序必须是整数')
  return {
    name,
    description,
    periodDays,
    priceFen,
    dailyQuota,
    sortOrder,
    enabled: body?.enabled !== false
  }
}

app.get('/api/plans', requireAuth, (req, res) => {
  res.json({ plans: sortedPlans() })
})

app.post('/api/plans', requireAuth, wrap(async (req, res) => {
  const values = parsePlan(req.body)
  if (db.plans.some(plan => plan.name === values.name)) {
    throw badRequest(`套餐「${values.name}」已存在`)
  }
  const record = {
    id: `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
    ...values,
    createdAt: Date.now(),
    updatedAt: Date.now()
  }
  db.plans.push(record)
  save()
  res.json({ ok: true, plan: record })
}))

app.put('/api/plans/:id', requireAuth, wrap(async (req, res) => {
  const record = db.plans.find(plan => plan.id === req.params.id)
  if (!record) throw badRequest('套餐不存在')
  const values = parsePlan(req.body)
  if (db.plans.some(plan => plan.name === values.name && plan.id !== record.id)) {
    throw badRequest(`套餐「${values.name}」已存在`)
  }
  Object.assign(record, values, { updatedAt: Date.now() })
  save()
  res.json({ ok: true, plan: record })
}))

app.delete('/api/plans/:id', requireAuth, wrap(async (req, res) => {
  const index = db.plans.findIndex(plan => plan.id === req.params.id)
  if (index < 0) throw badRequest('套餐不存在')
  db.plans.splice(index, 1)
  save()
  res.json({ ok: true })
}))

// ---- versions ----
app.get('/api/versions', requireAuth, (req, res) => {
  const list = [...db.versions].sort((a, b) => b.versionCode - a.versionCode)
  res.json({ versions: list, ossConfigured: ossConfigured(db.oss) })
})

app.post('/api/versions', requireAuth, uploads.single('apk'), wrap(async (req, res) => {
  const tempPath = req.file?.path
  try {
    if (!ossConfigured(db.oss)) {
      res.status(400).json({ message: '尚未配置阿里云 OSS，请先在「OSS 配置」中完成设置' })
      return
    }
    const versionCode = Number(req.body.versionCode)
    const versionName = String(req.body.versionName || '').trim()
    if (!req.file || !versionName || !Number.isInteger(versionCode) || versionCode <= 0) {
      res.status(400).json({ message: '请填写版本号、versionCode 并选择 APK 文件' })
      return
    }
    if (db.versions.some(item => item.versionCode === versionCode)) {
      res.status(400).json({ message: `versionCode ${versionCode} 已存在，请递增后再上传` })
      return
    }
    const sha256 = await sha256File(tempPath)
    const sizeBytes = fs.statSync(tempPath).size
    const key = objectKey(db.oss, `lianai-${versionName}.apk`)
    await uploadFile(db.oss, key, tempPath)
    const record = {
      id: `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
      versionCode,
      versionName,
      notes: String(req.body.notes || '').trim(),
      apkUrl: publicUrl(db.oss, key),
      ossKey: key,
      sha256,
      sizeBytes,
      createdAt: Date.now(),
      createdBy: req.username
    }
    db.versions.push(record)
    save()
    res.json({ ok: true, version: record })
  } finally {
    if (tempPath) fs.promises.unlink(tempPath).catch(() => {})
  }
}))

// Register an APK already present on OSS (uploaded directly from a fast link,
// bypassing this server's small uplink). Same record shape as a normal upload.
app.post('/api/versions/register', requireAuth, wrap(async (req, res) => {
  const { versionCode, versionName, notes, apkUrl, sha256, sizeBytes } = req.body || {}
  const code = Number(versionCode)
  const name = String(versionName || '').trim()
  const url = String(apkUrl || '').trim()
  const hash = String(sha256 || '').trim()
  const size = Number(sizeBytes)
  if (!Number.isInteger(code) || code <= 0 || !name) {
    res.status(400).json({ message: '请提供有效的版本号与 versionCode' })
    return
  }
  if (!url.startsWith('https://')) {
    res.status(400).json({ message: '安装包地址必须是 HTTPS 的 OSS 链接' })
    return
  }
  if (!hash.match(/^[a-fA-F0-9]{64}$/)) {
    res.status(400).json({ message: '请提供 64 位十六进制 SHA-256' })
    return
  }
  if (!Number.isFinite(size) || size <= 0) {
    res.status(400).json({ message: '请提供安装包大小（字节）' })
    return
  }
  if (db.versions.some(item => item.versionCode === code)) {
    res.status(400).json({ message: `versionCode ${code} 已存在` })
    return
  }
  let ossKey = ''
  try {
    ossKey = new URL(url).pathname.slice(1)
  } catch {
    ossKey = ''
  }
  const record = {
    id: `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
    versionCode: code,
    versionName: name,
    notes: String(notes || '').trim(),
    apkUrl: url,
    ossKey,
    sha256: hash,
    sizeBytes: size,
    createdAt: Date.now(),
    createdBy: req.username
  }
  db.versions.push(record)
  save()
  res.json({ ok: true, version: record })
}))

app.put('/api/versions/:id', requireAuth, wrap(async (req, res) => {
  const record = db.versions.find(item => item.id === req.params.id)
  if (!record) {
    res.status(404).json({ message: '版本不存在' })
    return
  }
  record.notes = String(req.body?.notes || '').trim()
  save()
  res.json({ ok: true, version: record })
}))

app.delete('/api/versions/:id', requireAuth, wrap(async (req, res) => {
  const index = db.versions.findIndex(item => item.id === req.params.id)
  if (index < 0) {
    res.status(404).json({ message: '版本不存在' })
    return
  }
  const [record] = db.versions.splice(index, 1)
  save()
  if (db.oss && record.ossKey) {
    await deleteObject(db.oss, record.ossKey).catch(error => {
      console.warn('[oss] 删除远端对象失败（忽略）:', error.message)
    })
  }
  res.json({ ok: true })
}))

// ---- public endpoints consumed by the app ----
app.get('/api/app/latest', (req, res) => {
  const latest = [...db.versions].sort((a, b) => b.versionCode - a.versionCode)[0]
  if (!latest) {
    res.status(404).json({ message: '暂无已发布的版本' })
    return
  }
  res.json({
    versionCode: latest.versionCode,
    versionName: latest.versionName,
    apkUrl: latest.apkUrl,
    sha256: latest.sha256,
    sizeBytes: latest.sizeBytes,
    notes: latest.notes
  })
})

// 已上架套餐按排序返回；App 订阅页据此渲染价格与权益。
app.get('/api/app/plans', (req, res) => {
  const plans = sortedPlans()
    .filter(plan => plan.enabled)
    .map(({ id, name, description, periodDays, priceFen, dailyQuota, sortOrder }) => ({
      id, name, description, periodDays, priceFen, dailyQuota, sortOrder
    }))
  res.json({ plans })
})

// ---- app user auth（App 端登录注册；正常使用不需要，购买订阅时才引导登录） ----
const PHONE_PATTERN = /^1\d{10}$/
const AUTH_TOKEN_TTL_MS = 180 * 24 * 60 * 60 * 1000
const AUTH_MAX_TOKENS_PER_USER = 5
const authAttempts = new Map() // key -> { count, resetAt }

function authRateLimited(key) {
  const now = Date.now()
  const entry = authAttempts.get(key)
  if (!entry || now > entry.resetAt) {
    authAttempts.set(key, { count: 1, resetAt: now + 60_000 })
    return false
  }
  entry.count += 1
  return entry.count > 20
}

const tooManyAttempts = () => Object.assign(new Error('尝试次数过多，请 1 分钟后再试'), { status: 429 })

function appUserByToken(token) {
  if (!token) return null
  for (const user of db.appUsers) {
    const record = user.tokens.find(item => item.token === token)
    if (record) {
      if (Date.now() > record.expiresAt) {
        user.tokens = user.tokens.filter(item => item !== record)
        save()
        return null
      }
      return user
    }
  }
  return null
}

const dayKeyOf = ts => new Date(ts).toLocaleDateString('sv-SE')

// 每日活跃埋点：每用户每天最多一条，只有当天首次活跃才写盘
function touchActive(userId) {
  const d = dayKeyOf(Date.now())
  if (!db.activity.some(item => item.u === userId && item.d === d)) {
    db.activity.push({ u: userId, d })
    save()
  }
}

const requireAppUser = (req, res, next) => {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '')
  const user = appUserByToken(token)
  if (!user) {
    res.status(401).json({ message: '请先登录' })
    return
  }
  touchActive(user.id)
  req.appUser = user
  req.appToken = token
  next()
}

const publicAppUser = user => ({
  id: user.id,
  phone: user.phone,
  createdAt: user.createdAt,
  lastLoginAt: user.lastLoginAt
})

app.post('/api/app/auth/register', wrap(async (req, res) => {
  if (authRateLimited(`reg:${req.ip}`)) throw tooManyAttempts()
  const phone = String(req.body?.phone || '').trim()
  const password = String(req.body?.password || '')
  if (!PHONE_PATTERN.test(phone)) throw badRequest('请输入正确的 11 位手机号')
  if (password.length < 8 || password.length > 64) throw badRequest('密码需为 8~64 位')
  if (db.appUsers.some(user => user.phone === phone)) {
    throw badRequest('该手机号已注册，请直接登录')
  }
  const token = randomToken()
  const user = {
    id: `u_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
    phone,
    salt: randomToken(),
    passwordHash: '',
    tokens: [{ token, expiresAt: Date.now() + AUTH_TOKEN_TTL_MS }],
    createdAt: Date.now(),
    lastLoginAt: Date.now()
  }
  user.passwordHash = hashWithSalt(password, user.salt)
  db.appUsers.push(user)
  touchActive(user.id)
  save()
  res.json({ token, user: publicAppUser(user) })
}))

app.post('/api/app/auth/login', wrap(async (req, res) => {
  if (authRateLimited(`login:${req.ip}`)) throw tooManyAttempts()
  const phone = String(req.body?.phone || '').trim()
  const password = String(req.body?.password || '')
  const user = db.appUsers.find(item => item.phone === phone)
  if (!user || hashWithSalt(password, user.salt) !== user.passwordHash) {
    res.status(401).json({ message: '手机号或密码错误' })
    return
  }
  const token = randomToken()
  user.tokens = user.tokens
    .filter(item => item.expiresAt > Date.now())
    .concat({ token, expiresAt: Date.now() + AUTH_TOKEN_TTL_MS })
    .slice(-AUTH_MAX_TOKENS_PER_USER)
  user.lastLoginAt = Date.now()
  touchActive(user.id)
  save()
  res.json({ token, user: publicAppUser(user) })
}))

app.get('/api/app/auth/profile', requireAppUser, (req, res) => {
  res.json({ user: publicAppUser(req.appUser) })
})

app.post('/api/app/auth/logout', requireAppUser, (req, res) => {
  req.appUser.tokens = req.appUser.tokens.filter(item => item.token !== req.appToken)
  save()
  res.json({ ok: true })
})

// App checkout is currently Alipay mobile website payment only.
const paymentLedger = installSubscriptionPayments(app, { db, save, requireAppUser, wrap, channelReady })
installOrderManagement(app, { db, requireAuth, wrap, ledger: paymentLedger })

// ---- ai models（官方 AI 通道的模型配置：密钥留在服务端，价格供计费展示） ----
const MODEL_CAPABILITIES = ['text', 'vision']

function parseModelInput(body, existing) {
  const provider = String(body?.provider || '').trim()
  const model = String(body?.model || '').trim()
  const displayName = String(body?.displayName || '').trim() || model
  const baseUrl = String(body?.baseUrl || '').trim().replace(/\/+$/, '')
  const apiKey = String(body?.apiKey || '').trim()
  const capabilities = Array.isArray(body?.capabilities)
    ? [...new Set(body.capabilities.map(String).filter(item => MODEL_CAPABILITIES.includes(item)))]
    : []
  const inputPriceFen = Number(body?.inputPriceFen ?? 0)
  const outputPriceFen = Number(body?.outputPriceFen ?? 0)
  if (!provider) throw badRequest('请选择服务商')
  if (!model || model.length > 100) throw badRequest('请填写模型 ID（100 字以内）')
  if (!displayName || displayName.length > 60) throw badRequest('展示名不能超过 60 字')
  if (!/^https:\/\//.test(baseUrl)) throw badRequest('接口地址必须以 https:// 开头')
  if (!capabilities.length) throw badRequest('至少选择一种能力（文本 / 视觉）')
  if (!Number.isInteger(inputPriceFen) || inputPriceFen < 0 || !Number.isInteger(outputPriceFen) || outputPriceFen < 0) {
    throw badRequest('价格必须是不小于 0 的数字（元，每百万 tokens）')
  }
  return {
    provider,
    model,
    displayName,
    baseUrl,
    apiKey: apiKey || existing?.apiKey || '',
    capabilities,
    inputPriceFen,
    outputPriceFen,
    enabled: body?.enabled !== false
  }
}

const publicModelView = item => ({
  id: item.id,
  provider: item.provider,
  displayName: item.displayName,
  capabilities: item.capabilities,
  inputPriceFen: item.inputPriceFen,
  outputPriceFen: item.outputPriceFen
})

const modelAdminView = item => ({
  ...publicModelView(item),
  model: item.model,
  baseUrl: item.baseUrl,
  apiKeySaved: Boolean(item.apiKey),
  ready: Boolean(item.apiKey),
  enabled: item.enabled,
  createdAt: item.createdAt,
  updatedAt: item.updatedAt
})

const sortedModels = () =>
  [...db.aiModels].sort((a, b) => (a.provider === b.provider ? a.createdAt - b.createdAt : a.provider.localeCompare(b.provider)))

app.get('/api/models', requireAuth, (req, res) => {
  res.json({ models: sortedModels().map(modelAdminView) })
})

app.post('/api/models', requireAuth, wrap(async (req, res) => {
  const values = parseModelInput(req.body)
  if (db.aiModels.some(item => item.provider === values.provider && item.model === values.model)) {
    throw badRequest(`「${values.provider}/${values.model}」已存在`)
  }
  if (values.enabled && !values.apiKey) {
    throw badRequest('已启用的模型必须填写 API Key，请先填 Key 再启用')
  }
  const record = {
    id: `m_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
    ...values,
    createdAt: Date.now(),
    updatedAt: Date.now()
  }
  db.aiModels.push(record)
  save()
  res.json({ ok: true, model: modelAdminView(record) })
}))

app.put('/api/models/:id', requireAuth, wrap(async (req, res) => {
  const record = db.aiModels.find(item => item.id === req.params.id)
  if (!record) throw badRequest('模型不存在')
  const values = parseModelInput(req.body, record)
  if (db.aiModels.some(item => item.id !== record.id && item.provider === values.provider && item.model === values.model)) {
    throw badRequest(`「${values.provider}/${values.model}」已存在`)
  }
  if (values.enabled && !values.apiKey) {
    throw badRequest('已启用的模型必须填写 API Key，请先填 Key 再启用')
  }
  Object.assign(record, values, { updatedAt: Date.now() })
  save()
  res.json({ ok: true, model: modelAdminView(record) })
}))

app.delete('/api/models/:id', requireAuth, wrap(async (req, res) => {
  const index = db.aiModels.findIndex(item => item.id === req.params.id)
  if (index < 0) throw badRequest('模型不存在')
  db.aiModels.splice(index, 1)
  save()
  res.json({ ok: true })
}))

// 对 App 暴露的可用模型列表（官方 AI 通道 / 图片识别将消费此契约；不含密钥与内网字段）
app.get('/api/app/models', (req, res) => {
  const models = sortedModels()
    .filter(item => item.enabled && item.apiKey)
    .map(publicModelView)
  res.json({ models })
})

// ---- ai usage & cost stats（模型调用记账与经营统计） ----
/**
 * AI 代理转发成功后调用记账。costFen 未提供时按模型配置价计算：
 * 输入 tokens × 输入价 + 输出 tokens × 输出价（每百万 tokens，单位分）。
 */
function recordModelUsage({ modelId, userId, feature, inputTokens = 0, outputTokens = 0, costFen }) {
  const model = db.aiModels.find(item => item.id === modelId)
  const resolved = Number.isFinite(costFen) && costFen >= 0
    ? Math.round(costFen)
    : Math.round((inputTokens / 1_000_000) * (model?.inputPriceFen || 0)) +
      Math.round((outputTokens / 1_000_000) * (model?.outputPriceFen || 0))
  const user = db.appUsers.find(item => item.id === userId)
  db.aiUsage.push({
    id: `u_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
    modelId,
    displayName: model?.displayName || '未知模型',
    provider: model?.provider || '',
    userId: userId || '',
    userPhone: user?.phone || '',
    feature: String(feature || ''),
    inputTokens,
    outputTokens,
    costFen: resolved,
    createdAt: Date.now()
  })
  if (db.aiUsage.length > 50_000) db.aiUsage.splice(0, db.aiUsage.length - 50_000)
  save()
  return db.aiUsage[db.aiUsage.length - 1]
}

app.get('/api/stats/costs', requireAuth, (req, res) => {
  const usage = db.aiUsage
  const totalCostFen = usage.reduce((sum, item) => sum + item.costFen, 0)
  const paidOrders = db.orders.filter(order => order.status === 'paid')
  const vipIncomeFen = paidOrders.reduce((sum, order) => sum + order.amountFen, 0)
  const byModelMap = new Map()
  usage.forEach(item => {
    const entry = byModelMap.get(item.modelId) || {
      modelId: item.modelId, displayName: item.displayName, calls: 0, costFen: 0, inputTokens: 0, outputTokens: 0
    }
    entry.calls += 1
    entry.costFen += item.costFen
    entry.inputTokens += item.inputTokens
    entry.outputTokens += item.outputTokens
    byModelMap.set(item.modelId, entry)
  })
  const byFeatureMap = new Map()
  usage.forEach(item => {
    const key = item.feature || '未标注'
    const entry = byFeatureMap.get(key) || { feature: key, calls: 0, costFen: 0 }
    entry.calls += 1
    entry.costFen += item.costFen
    byFeatureMap.set(key, entry)
  })
  res.json({
    totalCalls: usage.length,
    totalCostFen,
    vipIncomeFen,
    vipPaidOrders: paidOrders.length,
    profitFen: vipIncomeFen - totalCostFen,
    byModel: [...byModelMap.values()].sort((a, b) => b.costFen - a.costFen),
    byFeature: [...byFeatureMap.values()].sort((a, b) => b.costFen - a.costFen)
  })
})

app.get('/api/usage', requireAuth, (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 200, 1000)
  const list = db.aiUsage.slice(-limit).reverse()
  res.json({ usage: list })
})

// ---- business config & 视觉回复（截图识别 → 模型池转发） ----
const VISION_SYSTEM_PROMPT = [
  '你是「恋爱键盘」的聊天截图分析助手，擅长根据聊天截图帮用户高情商回复。',
  '用户会发来一张聊天截图（微信、短信、交友App等）。',
  '你的任务：',
  '1. 识别对话内容与聊天场景，场景只能是：恋爱、朋友、商务、家人、其他。',
  '2. 找到「对方」最新一条需要回应的消息。通常右侧气泡是用户自己、左侧是对方；无法分辨时以最后一条非用户消息为准。',
  '3. 站在用户本人（消息接收者）的第一人称角度，生成 3 条可直接发送的回复：',
  '   第1条「稳妥」：自然真诚、符合语境、不会出错；',
  '   第2条「进阶」：推进关系或目标——恋爱场景推进暧昧升温，商务场景推进成交，朋友场景增进关系；',
  '   第3条「巧思」：带巧劲或幽默，让人眼前一亮。',
  '4. 场景要求：恋爱/暧昧要教用户怎么接话、怎么开话头，自然不油腻、不过度承诺；商务/生意要专业得体、要点清晰、提高这一单谈成的概率；家人要温暖体贴。',
  '只输出如下 JSON，不要输出任何其他内容：',
  '{"scenario":"恋爱|朋友|商务|家人|其他","analysis":"不超过20字的场景判断","replies":["第1条","第2条","第3条"]}'
].join('\n')

app.get('/api/business', requireAuth, (req, res) => {
  const visionReply = db.business.visionReply
  const model = db.aiModels.find(item => item.id === visionReply.modelId)
  res.json({
    visionReply: {
      enabled: visionReply.enabled,
      modelId: visionReply.modelId,
      modelDisplayName: model?.displayName || '',
      modelReady: Boolean(model && model.enabled && model.apiKey && model.capabilities.includes('vision'))
    }
  })
})

app.put('/api/business', requireAuth, wrap(async (req, res) => {
  const enabled = Boolean(req.body?.visionReplyEnabled)
  const modelId = String(req.body?.modelId || '')
  if (enabled) {
    const model = db.aiModels.find(item => item.id === modelId)
    if (!model) throw badRequest('请选择截图识别回复使用的模型')
    if (!model.enabled || !model.apiKey) throw badRequest('所选模型未上架或未配置密钥，请先在「模型配置」完成')
    if (!model.capabilities.includes('vision')) throw badRequest('所选模型不支持视觉能力，请选择带「视觉」标签的模型')
  }
  db.business.visionReply = { enabled, modelId: enabled ? modelId : modelId }
  save()
  res.json({ ok: true, business: db.business })
}))

// App 查询功能开关（公开）
app.get('/api/app/business', (req, res) => {
  const visionReply = db.business.visionReply
  const model = db.aiModels.find(item => item.id === visionReply.modelId)
  res.json({
    visionReplyEnabled: Boolean(
      visionReply.enabled && model && model.enabled && model.apiKey && model.capabilities.includes('vision')
    )
  })
})

function extractJson(text) {
  const trimmed = text.replace(/```json|```/g, '').trim()
  const start = trimmed.indexOf('{')
  const end = trimmed.lastIndexOf('}')
  if (start >= 0 && end > start) {
    try {
      return JSON.parse(trimmed.slice(start, end + 1))
    } catch {
      return null
    }
  }
  return null
}

app.post('/api/app/vision/reply', requireAppUser, wrap(async (req, res) => {
  const config = db.business.visionReply
  const model = db.aiModels.find(item => item.id === config.modelId)
  if (!config.enabled || !model || !model.enabled || !model.apiKey || !model.capabilities.includes('vision')) {
    res.status(403).json({ message: '截图识别回复功能未开放，敬请期待' })
    return
  }
  const imageBase64 = String(req.body?.imageBase64 || '').replace(/^data:[^,]+,/, '')
  const mime = String(req.body?.mime || 'image/jpeg')
  if (imageBase64.length < 100) throw badRequest('图片内容为空')
  if (imageBase64.length > 8 * 1024 * 1024) throw badRequest('图片过大，请换个截图试试')

  const providerBody = {
    model: model.model,
    // 思考型模型（如 kimi-k2.6）会先输出较长的思考过程，预算不足时正文为空，
    // 之前 1000 会被思考耗尽导致把思考文本当回复返回，这里给足预算。
    max_tokens: 4096,
    messages: [
      { role: 'system', content: VISION_SYSTEM_PROMPT },
      {
        role: 'user',
        content: [
          { type: 'image_url', image_url: { url: `data:${mime};base64,${imageBase64}` } },
          { type: 'text', text: '请分析这张聊天截图，并按系统指令只输出 JSON。' }
        ]
      }
    ]
  }
  const response = await fetch(`${model.baseUrl}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${model.apiKey}`
    },
    body: JSON.stringify(providerBody),
    signal: AbortSignal.timeout(80_000)
  })
  const providerText = await response.text()
  if (!response.ok) {
    console.error('[vision] provider error', response.status, providerText.slice(0, 300))
    throw badRequest('模型服务暂不可用，请稍后重试')
  }
  let content = ''
  let usage = null
  try {
    const json = JSON.parse(providerText)
    const message = json.choices?.[0]?.message || {}
    // 只取正文；思考型模型的 reasoning_content 是思考过程，绝不能当回复内容
    content = message.content || ''
    usage = json.usage || null
  } catch {
    throw badRequest('模型返回异常，请稍后重试')
  }
  if (!content.trim()) {
    console.error('[vision] empty content (thinking exhausted budget?); raw =', providerText.slice(0, 500))
    throw badRequest('模型思考超时没有给出回复，请再试一次')
  }
  let scenario = '其他'
  let analysis = ''
  let replies = []
  const parsed = extractJson(content)
  if (parsed && Array.isArray(parsed.replies)) {
    scenario = String(parsed.scenario || scenario).trim().slice(0, 30) || scenario
    analysis = String(parsed.analysis || '').trim().slice(0, 120)
    replies = parsed.replies.map(String).map(item => item.trim())
      .filter(item => item.length >= 2 && item.length <= 200 && !item.includes('\n'))
  } else {
    // 正文里没有合法 JSON：一律视为异常，绝不把思考/散文按行拆成“回复”
    console.error('[vision] no JSON in content; content =', content.slice(0, 500))
    throw badRequest('这张截图没识别出可回复的内容，换一张试试')
  }
  replies = replies.slice(0, 3)
  if (!replies.length) throw badRequest('这张截图没识别出可回复的内容，换一张试试')

  recordModelUsage({
    modelId: model.id,
    userId: req.appUser.id,
    feature: 'vision',
    inputTokens: usage?.prompt_tokens || 0,
    outputTokens: usage?.completion_tokens || 0
  })
  res.json({ scenario, analysis, replies })
}))

// ---- 用户管理与日活统计 ----
const activeSubscriptionOf = userId =>
  db.subscriptions
    .filter(item => item.userId === userId)
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0] || null

const userStats = user => {
  const orders = db.orders.filter(order => order.userId === user.id)
  const paid = orders.filter(order => order.status === 'paid')
  const usage = db.aiUsage.filter(item => item.userId === user.id)
  const activityDays = db.activity
    .filter(item => item.u === user.id)
    .map(item => item.d)
  // 埋点上线前的历史：用最近一次 AI 调用 / 下单时间回溯
  const lastUsage = usage.reduce((latest, item) => Math.max(latest, item.createdAt), 0)
  const lastOrder = orders.reduce((latest, order) => Math.max(latest, order.createdAt), 0)
  const lastActiveAt = [
    ...activityDays,
    lastUsage ? dayKeyOf(lastUsage) : null,
    lastOrder ? dayKeyOf(lastOrder) : null
  ]
    .filter(Boolean)
    .sort()
    .pop() || null
  return {
    orderCount: orders.length,
    paidCount: paid.length,
    spentFen: paid.reduce((sum, order) => sum + order.amountFen, 0),
    usageCount: usage.length,
    usageCostFen: usage.reduce((sum, item) => sum + item.costFen, 0),
    activeDays: activityDays.length,
    lastActiveAt
  }
}

app.get('/api/users', requireAuth, (req, res) => {
  const list = db.appUsers
    .map(user => ({
      id: user.id,
      phone: user.phone,
      createdAt: user.createdAt,
      lastLoginAt: user.lastLoginAt,
      tokenCount: (user.tokens || []).filter(item => item.expiresAt > Date.now()).length,
      subscription: activeSubscriptionOf(user.id),
      ...userStats(user)
    }))
    .sort((a, b) => (b.lastLoginAt || 0) - (a.lastLoginAt || 0))
  res.json({ users: list, total: list.length })
})

app.get('/api/users/:id', requireAuth, (req, res) => {
  const user = db.appUsers.find(item => item.id === req.params.id)
  if (!user) {
    res.status(404).json({ message: '用户不存在' })
    return
  }
  const orders = db.orders
    .filter(order => order.userId === user.id)
    .sort((a, b) => b.createdAt - a.createdAt)
    .map(({ payUrl, ...rest }) => rest)
  const usage = db.aiUsage
    .filter(item => item.userId === user.id)
    .sort((a, b) => b.createdAt - a.createdAt)
    .slice(0, 100)
  res.json({
    user: {
      id: user.id,
      phone: user.phone,
      createdAt: user.createdAt,
      lastLoginAt: user.lastLoginAt,
      tokenCount: (user.tokens || []).filter(item => item.expiresAt > Date.now()).length
    },
    subscription: activeSubscriptionOf(user.id),
    orders,
    usage,
    activityDays: db.activity
      .filter(item => item.u === user.id)
      .map(item => item.d)
      .sort(),
    stats: userStats(user)
  })
})

app.get('/api/stats/overview', requireAuth, (req, res) => {
  const dayKey = dayKeyOf(Date.now())
  const days = []
  for (let i = 29; i >= 0; i -= 1) days.push(dayKeyOf(Date.now() - i * 86_400_000))

  // 活跃集合 = 显式埋点 ∪ 历史调用记录 ∪ 历史订单 ∪ 注册事件
  const activeByDay = new Map(days.map(d => [d, new Set()]))
  const markActive = (userId, ts) => {
    const set = activeByDay.get(dayKeyOf(ts))
    if (set && userId) set.add(userId)
  }
  db.activity.forEach(item => {
    const set = activeByDay.get(item.d)
    if (set) set.add(item.u)
  })
  db.aiUsage.forEach(item => markActive(item.userId, item.createdAt))
  db.orders.forEach(order => markActive(order.userId, order.createdAt))
  db.appUsers.forEach(user => markActive(user.id, user.createdAt))

  const newByDay = new Map(days.map(d => [d, 0]))
  db.appUsers.forEach(user => {
    const d = dayKeyOf(user.createdAt)
    if (newByDay.has(d)) newByDay.set(d, newByDay.get(d) + 1)
  })

  const activeSet7 = new Set()
  const activeSet30 = new Set()
  days.forEach((d, index) => {
    activeByDay.get(d).forEach(userId => {
      if (index >= 23) activeSet7.add(userId)
      activeSet30.add(userId)
    })
  })

  const recentActive = db.appUsers
    .map(user => {
      const dates = db.activity.filter(item => item.u === user.id).map(item => item.d)
      const lastUsage = db.aiUsage
        .filter(item => item.userId === user.id)
        .reduce((latest, item) => Math.max(latest, item.createdAt), 0)
      const lastActive = dates.length ? dates.sort()[dates.length - 1] : lastUsage ? dayKeyOf(lastUsage) : null
      return {
        id: user.id,
        phone: user.phone,
        lastActive,
        activeDays30: dates.filter(d => activeByDay.has(d)).length
      }
    })
    .filter(item => item.lastActive)
    .sort((a, b) => (a.lastActive < b.lastActive ? 1 : a.lastActive > b.lastActive ? -1 : 0))
    .slice(0, 20)

  const paidOrders = db.orders.filter(order => order.status === 'paid')
  res.json({
    totalUsers: db.appUsers.length,
    todayNew: newByDay.get(dayKey) || 0,
    todayActive: activeByDay.get(dayKey)?.size || 0,
    weekActive: activeSet7.size,
    monthActive: activeSet30.size,
    totalOrders: db.orders.length,
    paidOrders: paidOrders.length,
    revenueFen: paidOrders.reduce((sum, order) => sum + order.amountFen, 0),
    totalCalls: db.aiUsage.length,
    totalCostFen: db.aiUsage.reduce((sum, item) => sum + item.costFen, 0),
    daily: days.map(d => ({ date: d, active: activeByDay.get(d).size, newUsers: newByDay.get(d) })),
    recentActive
  })
})

app.use('/api', (req, res) => res.status(404).json({ message: '接口不存在' }))

// ---- static frontend ----
// 兼容两种布局：本仓库（server/ 与 web/dist）与服务器扁平部署（index.mjs 与 dist 同级）。
const here = path.dirname(fileURLToPath(import.meta.url))
const distDir = [
  path.join(here, '..', 'web', 'dist'),
  path.join(here, 'dist')
].find(dir => fs.existsSync(dir))
if (fs.existsSync(distDir)) {
  app.use(express.static(distDir))
  app.get(/^(?!\/api).*/, (req, res) => {
    res.sendFile(path.join(distDir, 'index.html'))
  })
}

app.listen(PORT, () => {
  console.log(`LianAi admin listening on http://localhost:${PORT}`)
})
