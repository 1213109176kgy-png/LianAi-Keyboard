import test from 'node:test'
import assert from 'node:assert/strict'
import express from 'express'
import { userVipInfo, requireVip } from '../model-access.mjs'
import { installTextGeneration } from '../text-generation.mjs'

async function fixture(t, fetchImpl) {
  const db = { aiModels: ['reply', 'polish'].map(id => ({ id, model: `${id}-upstream`, enabled: true,
    capabilities: ['text'], apiKey: 'mock-provider-secret', baseUrl: 'https://provider.example/v1' })),
    subscriptions: [{ userId: 'vip', expiresAt: Date.now() + 60000 }],
    business: { replySuggestions: { enabled: true, modelId: 'reply' }, superSpeak: { enabled: true, modelId: 'polish' } } }
  const calls = []; const usage = []
  const auth = (req, res, next) => {
    const id = req.headers.authorization?.replace('Bearer ', '')
    if (!['vip', 'normal'].includes(id)) return res.sendStatus(401)
    req.appUser = { id }; next()
  }
  const app = express(); app.use(express.json())
  installTextGeneration(app, { db, requireAppUser: auth,
    wrap: fn => (req, res) => Promise.resolve(fn(req, res)).catch(e => res.status(e.status || 500).json({ message: e.message })),
    recordModelUsage: item => usage.push(item), fetchImpl: async (url, options) => {
      calls.push({ url, headers: options.headers, body: JSON.parse(options.body) })
      return fetchImpl ? fetchImpl() : Response.json({ choices: [{ message: { content: '模型回复' } }], usage: { prompt_tokens: 5000, completion_tokens: 50 } })
    } })
  // Same middleware installed on the production vision handler.
  app.post('/vision', auth, requireVip(db), (req, res) => res.json({ ok: true }))
  const server = app.listen(0, '127.0.0.1'); await new Promise(resolve => server.once('listening', resolve))
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)) })
  const request = async (path = 'reply', token = 'vip', body = {}) => {
    const response = await fetch(`http://127.0.0.1:${server.address().port}${path === 'vision' ? '/vision' : `/api/app/text/${path}/chat/completions`}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ messages: [{ role: 'user', content: '测试' }], ...body }) })
    return { status: response.status, text: await response.text() }
  }
  return { db, calls, usage, request }
}

test('non-members, anonymous and expired members never reach configured AI providers', async t => {
  const f = await fixture(t)
  assert.equal((await f.request('reply', 'normal')).status, 403)
  assert.equal((await f.request('polish', '')).status, 401)
  assert.equal((await f.request('vision', 'normal')).status, 403)
  f.db.subscriptions[0].expiresAt = Date.now() - 1
  assert.equal((await f.request()).status, 403)
  assert.equal(f.calls.length, 0)
  assert.equal(userVipInfo(f.db, 'vip').active, false)
})

test('text businesses have independent models, ignore client credentials and record each cost once', async t => {
  const f = await fixture(t)
  assert.equal((await f.request('reply', 'vip', { model: 'client-override', apiKey: 'client-credential' })).status, 200)
  assert.equal((await f.request('polish')).status, 200)
  assert.deepEqual(f.calls.map(item => item.body.model), ['reply-upstream', 'polish-upstream'])
  assert.deepEqual(f.usage.map(item => item.feature), ['粘贴TA的话（帮你回）', '超会说'])
  assert.equal(f.calls[0].headers.Authorization, 'Bearer mock-provider-secret')
  assert.equal(JSON.stringify(f.calls[0].body).includes('client-credential'), false)
  f.db.business.replySuggestions.enabled = false
  assert.equal((await f.request()).status, 403)
  f.db.aiModels[1].apiKey = ''
  assert.equal((await f.request('polish')).status, 403)
  assert.equal(f.calls.length, 2)
})

test('SSE content is relayed without reasoning or credentials and usage is recorded', async t => {
  const events = [{ choices: [{ delta: { reasoning_content: 'private reasoning' } }] },
    { choices: [{ delta: { content: '你好' } }] }, { usage: { prompt_tokens: 90, completion_tokens: 5 } }]
  const f = await fixture(t, () => new Response(events.map(item => `data: ${JSON.stringify(item)}\n\n`).join('') + 'data: [DONE]\n\n', { headers: { 'Content-Type': 'text/event-stream' } }))
  const response = await f.request('reply', 'vip', { stream: true })
  assert.equal(response.status, 200)
  assert.match(response.text, /你好/)
  assert.match(response.text, /\[DONE\]/)
  assert.doesNotMatch(response.text, /private reasoning|mock-provider-secret/)
  assert.equal(f.usage.length, 1)
  assert.equal(f.usage[0].inputTokens, 90)
})

test('malformed text is rejected before upstream and upstream errors never expose keys', async t => {
  const f = await fixture(t, () => new Response('mock-provider-secret', { status: 401 }))
  assert.equal((await f.request('reply', 'vip', { messages: [{ role: 'user', content: [{ type: 'image_url' }] }] })).status, 400)
  assert.equal(f.calls.length, 0)
  const response = await f.request()
  assert.equal(response.status, 502)
  assert.doesNotMatch(response.text, /mock-provider-secret/)
  assert.equal(f.usage.length, 0)
})
