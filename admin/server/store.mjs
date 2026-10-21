import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dataDir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'data')
const dbFile = path.join(dataDir, 'db.json')

const defaults = () => ({
  user: { username: 'admin', passwordHash: hash('admin123') },
  oss: null,
  versions: [],
  plans: [],
  appUsers: [],
  orders: [],
  subscriptions: [],
  aiModels: [],
  aiUsage: [],
  // 每日活跃埋点：{ u: userId, d: 'YYYY-MM-DD' }，每用户每天最多一条
  activity: [],
  business: defaultBusiness(),
  payment: defaultPayment()
})
// 支付通道参数模板。密钥类字段只保存在服务端 db.json，接口层永不回显原文。
export function defaultPayment() {
  return {
    // 服务公网地址（如 https://lianai.bahuang.tech），用于拼接支付回调 URL
    publicBaseUrl: '',
    wechat: {
      enabled: false,
      appId: '',
      mchId: '',
      apiV3Key: '',
      merchantSerialNo: '',
      merchantPrivateKey: '',
      // 微信支付平台公钥（公钥模式），用于校验回调签名
      platformPublicKey: ''
    },
    alipay: {
      enabled: false,
      appId: '',
      sellerId: '',
      privateKey: '',
      alipayPublicKey: '',
      gatewayUrl: 'https://openapi.alipay.com/gateway.do'
    },
    aggregator: { enabled: false, provider: 'xunhupay', appId: '', appSecret: '', gatewayUrl: '' }
  }
}

// 业务功能配置模板（App 功能开关与所用模型）
export function defaultBusiness() {
  return {
    visionReply: { enabled: false, modelId: '' },
    speechRecognition: { enabled: false, modelId: '' },
    replySuggestions: { enabled: false, modelId: '' },
    superSpeak: { enabled: false, modelId: '' }
  }
}

function mergeBusiness(stored) {
  const base = defaultBusiness()
  if (!stored || typeof stored !== 'object') return base
  return {
    visionReply: { ...base.visionReply, ...(stored.visionReply || {}) },
    speechRecognition: { ...base.speechRecognition, ...(stored.speechRecognition || {}) },
    replySuggestions: { ...base.replySuggestions, ...(stored.replySuggestions || {}) },
    superSpeak: { ...base.superSpeak, ...(stored.superSpeak || {}) }
  }
}

// 旧版 db.json 没有 payment 字段；逐通道补齐缺省值，避免接口读到 undefined。
function mergePayment(stored) {
  const base = defaultPayment()
  if (!stored || typeof stored !== 'object') return base
  const merged = { ...base, ...stored }
  for (const channel of ['wechat', 'alipay', 'aggregator']) {
    merged[channel] = { ...base[channel], ...(stored[channel] || {}) }
  }
  return merged
}

export function hash(value) {
  const salt = 'lianai-admin-static-salt'
  return crypto.scryptSync(value, salt, 32).toString('hex')
}

// App 端用户密码：每用户独立盐，避免同密码哈希相同
export function hashWithSalt(value, salt) {
  return crypto.scryptSync(String(value), String(salt), 32).toString('hex')
}

export function randomToken() {
  return crypto.randomBytes(24).toString('hex')
}

let cache = null

export function load() {
  if (cache) return cache
  fs.mkdirSync(dataDir, { recursive: true })
  if (fs.existsSync(dbFile)) {
    cache = JSON.parse(fs.readFileSync(dbFile, 'utf8'))
    cache.payment = mergePayment(cache.payment)
    cache.business = mergeBusiness(cache.business)
    if (!Array.isArray(cache.plans)) cache.plans = []
    if (!Array.isArray(cache.appUsers)) cache.appUsers = []
    if (!Array.isArray(cache.orders)) cache.orders = []
    if (!Array.isArray(cache.subscriptions)) cache.subscriptions = []
    if (!Array.isArray(cache.aiModels)) cache.aiModels = []
    if (!Array.isArray(cache.aiUsage)) cache.aiUsage = []
    if (!Array.isArray(cache.activity)) cache.activity = []
  } else {
    cache = defaults()
    save()
  }
  return cache
}

export function save() {
  fs.mkdirSync(dataDir, { recursive: true })
  const next = JSON.stringify(cache, null, 2)
  const temporary = dbFile + '.tmp'
  fs.writeFileSync(temporary, next, { mode: 0o600 })
  fs.chmodSync(temporary, 0o600)
  fs.renameSync(temporary, dbFile)
}

// Simple in-memory sessions; a server restart simply asks for a new login.
const sessions = new Map()
const SESSION_TTL_MS = 12 * 60 * 60 * 1000

export function createSession(username) {
  const token = crypto.randomBytes(24).toString('hex')
  sessions.set(token, { username, expiresAt: Date.now() + SESSION_TTL_MS })
  return token
}

export function resolveSession(token) {
  const session = sessions.get(token)
  if (!session) return null
  if (Date.now() > session.expiresAt) {
    sessions.delete(token)
    return null
  }
  return session
}

export function destroySession(token) {
  sessions.delete(token)
}
