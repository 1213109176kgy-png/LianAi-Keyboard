import test from 'node:test'
import assert from 'node:assert/strict'
import express from 'express'
import { installAppConfiguration, publicAppConfig } from '../app-config.mjs'
import { parseModelInput } from '../model-config.mjs'
import { defaultBusiness } from '../store.mjs'

const model = (id, capability) => ({ id, provider: 'xiaomi_mimo', model: id, displayName: id,
  enabled: true, apiKey: 'test-provider-credential', baseUrl: 'https://api.xiaomimimo.com/v1', capabilities: [capability] })
const database = () => ({ aiModels: [model('asr1', 'asr'), model('asr2', 'asr'), model('vision1', 'vision')],
  business: { visionReply: { enabled: true, modelId: 'vision1' }, speechRecognition: { enabled: true, modelId: 'asr1' } } })
const wav = () => {
  const b = Buffer.alloc(44 + 3200)
  b.write('RIFF'); b.writeUInt32LE(b.length - 8, 4); b.write('WAVE', 8); b.write('fmt ', 12)
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(16000, 24)
  b.writeUInt32LE(32000, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(3200, 40)
  return b.toString('base64')
}
async function fixture(t, options = {}) {
  const db = database(); const usage = []; let failSave = false; let calls = []
  const app = express(); app.use(express.json({ limit: '5mb' }))
  const auth = token => (req, res, next) => {
    if (req.headers.authorization !== `Bearer ${token}`) return res.status(401).json({ message: '请先登录' })
    req.appUser = { id: 'user1' }; next()
  }
  installAppConfiguration(app, { db, save: () => { if (failSave) throw new Error('storage failed') },
    requireAuth: auth('admin-test'), requireAppUser: auth('app-test'),
    optionalAppUser: (req, res, next) => req.headers.authorization
      ? auth('app-test')(req, res, next) : next(),
    wrap: handler => (req, res) => Promise.resolve(handler(req, res)).catch(e => res.status(e.status || 500).json({ message: e.message })),
    recordModelUsage: record => usage.push(record),
    fetchImpl: async (url, init) => {
      calls.push({ url, init, body: JSON.parse(init.body) })
      if (options.fetchImpl) return options.fetchImpl(url, init)
      return Response.json({ choices: [{ message: { content: '<chinese>后台语音识别成功null</chinese>' } }], usage: { prompt_tokens: 12, completion_tokens: 8 } })
    }
  })
  const server = app.listen(0, '127.0.0.1'); await new Promise(resolve => server.once('listening', resolve))
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)) })
  const request = async (path, body, token = 'app-test', method = body ? 'POST' : 'GET') => {
    const response = await fetch(`http://127.0.0.1:${server.address().port}${path}`, {
      method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {})
    })
    return { status: response.status, json: await response.json() }
  }
  return { db, usage, calls, request, failSave: () => { failSave = true } }
}

test('MiMo ASR capability is accepted and legacy business data defaults safely', () => {
  assert.deepEqual(parseModelInput({ ...model('mimo-v2.5-asr', 'asr'), inputPriceFen: 0, outputPriceFen: 0 }).capabilities, ['asr'])
  assert.throws(() => parseModelInput({ ...model('bad', 'other'), inputPriceFen: 0, outputPriceFen: 0 }), /能力/)
  assert.deepEqual(defaultBusiness().speechRecognition, { enabled: false, modelId: '' })
  const db = database(); delete db.business.speechRecognition
  assert.equal(publicAppConfig(db).features.speechRecognition.enabled, false)
})

test('public app configuration whitelists model metadata without provider credentials', async t => {
  const f = await fixture(t)
  const result = await f.request('/api/app/config', null, '')
  assert.equal(result.status, 200)
  assert.equal(result.json.schemaVersion, 1)
  assert.equal(result.json.user, null)
  assert.equal(result.json.features.speechRecognition.requiresVip, false)
  assert.equal(result.json.features.visionReply.requiresVip, true)
  assert.equal(result.json.features.speechRecognition.modelId, 'asr1')
  assert.equal(result.json.features.speechRecognition.endpoint, '/api/app/speech/transcribe')
  const text = JSON.stringify(result.json)
  for (const forbidden of ['test-provider-credential', 'apiKey', 'baseUrl', 'https://api.xiaomimimo.com']) assert.equal(text.includes(forbidden), false)
  f.db.aiModels[0].enabled = false
  assert.equal((await f.request('/api/app/config')).json.features.speechRecognition.enabled, false)
  assert.equal((await f.request('/api/app/business')).json.visionReplyEnabled, true)
})

test('authenticated config returns only the requesting user and current VIP, stale tokens are rejected', async t => {
  const f = await fixture(t)
  f.db.subscriptions = [{ userId: 'user1', planId: 'vip', planName: 'VIP', expiresAt: Date.now() + 86400000, dailyQuota: 100 }]
  const own = await f.request('/api/app/config')
  assert.equal(own.json.user.id, 'user1')
  assert.equal(own.json.user.vip.active, true)
  assert.equal(own.json.user.vip.dailyQuota, 100)
  f.db.subscriptions[0].expiresAt = Date.now() - 1
  assert.equal((await f.request('/api/app/config')).json.user.vip.active, false)
  assert.equal((await f.request('/api/app/config', null, 'expired')).status, 401)
  assert.equal((await f.request('/api/app/config', null, '')).json.user, null)
})

test('business changes require admin, validate model readiness, preserve independent features and roll back failed saves', async t => {
  const f = await fixture(t)
  const update = speechRecognition => f.request('/api/business', { speechRecognition }, 'admin-test', 'PUT')
  assert.equal((await f.request('/api/business', { speechRecognition: { enabled: false } }, 'app-test', 'PUT')).status, 401)
  assert.equal((await update({ enabled: true, modelId: 'vision1' })).status, 400)
  f.db.aiModels[1].apiKey = ''
  assert.equal((await update({ enabled: true, modelId: 'asr2' })).status, 400)
  f.db.aiModels[1].apiKey = 'test-provider-credential'
  assert.equal((await update({ enabled: true, modelId: 'asr2' })).status, 200)
  assert.deepEqual(f.db.business.visionReply, { enabled: true, modelId: 'vision1' })
  assert.equal((await f.request('/api/business', null, 'admin-test')).json.speechRecognition.modelReady, true)
  f.failSave()
  assert.equal((await update({ enabled: false, modelId: 'asr2' })).status, 500)
  assert.equal(f.db.business.speechRecognition.enabled, true)
})

test('basic ASR is available to logged-in non-members, uses selected model and records duration', async t => {
  const f = await fixture(t)
  const body = { format: 'wav', audioBase64: wav(), model: 'untrusted-client-model', apiKey: 'untrusted-client-key' }
  assert.equal((await f.request('/api/app/speech/transcribe', body, '')).status, 401)
  assert.equal(f.calls.length, 0)
  const result = await f.request('/api/app/speech/transcribe', body)
  assert.equal(result.status, 200)
  assert.equal(result.json.text, '后台语音识别成功')
  assert.equal(f.calls[0].url, 'https://api.xiaomimimo.com/v1/chat/completions')
  assert.equal(f.calls[0].init.headers['api-key'], 'test-provider-credential')
  assert.equal(f.calls[0].body.model, 'asr1')
  assert.deepEqual(f.usage[0], { modelId: 'asr1', userId: 'user1', feature: '语音识别', audioSeconds: 0.1, inputTokens: 12, outputTokens: 8 })
  f.db.business.speechRecognition.modelId = 'asr2'
  assert.equal((await f.request('/api/app/speech/transcribe', body)).json.modelId, 'asr2')
  assert.equal(f.calls[1].body.model, 'asr2')
  f.db.business.speechRecognition.enabled = false
  assert.equal((await f.request('/api/app/speech/transcribe', body)).status, 403)
  assert.equal(f.calls.length, 2)
})

test('invalid audio never reaches provider and provider failures do not expose credentials', async t => {
  const f = await fixture(t, { fetchImpl: () => new Response('test-provider-credential upstream error', { status: 401 }) })
  for (const body of [{ format: 'mp3', audioBase64: wav() }, { format: 'wav', audioBase64: 'bad' }, { format: 'wav', audioBase64: Buffer.from('not wav').toString('base64') }]) {
    assert.equal((await f.request('/api/app/speech/transcribe', body)).status, 400)
  }
  assert.equal(f.calls.length, 0)
  const result = await f.request('/api/app/speech/transcribe', { format: 'wav', audioBase64: wav() })
  assert.equal(result.status, 502)
  assert.equal(JSON.stringify(result).includes('test-provider-credential'), false)
  assert.equal(f.usage.length, 0)
})

test('per-user concurrent ASR limit releases slots after requests finish', async t => {
  const releases = []; let notify; const twoStarted = new Promise(resolve => { notify = resolve })
  const f = await fixture(t, { fetchImpl: () => new Promise(resolve => {
    releases.push(() => resolve(Response.json({ choices: [{ message: { content: '成功' } }] })))
    if (releases.length === 2) notify()
  }) })
  const body = { format: 'wav', audioBase64: wav() }
  const first = f.request('/api/app/speech/transcribe', body)
  const second = f.request('/api/app/speech/transcribe', body)
  await twoStarted
  assert.equal((await f.request('/api/app/speech/transcribe', body)).status, 429)
  releases.forEach(release => release())
  assert.deepEqual((await Promise.all([first, second])).map(r => r.status), [200, 200])
})
