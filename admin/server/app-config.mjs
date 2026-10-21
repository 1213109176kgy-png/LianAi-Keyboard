import crypto from 'node:crypto'
import { configUser } from './model-access.mjs'

const badRequest = message => Object.assign(new Error(message), { status: 400 })
const emptyFeature = () => ({ enabled: false, modelId: '' })
const featureKeys = { visionReply: 'vision', speechRecognition: 'asr', replySuggestions: 'text', superSpeak: 'text' }
const MAX_PCM_BYTES = 16_000 * 2 * 90

export function configuredFeature(db, key) {
  const config = db.business?.[key] || emptyFeature()
  const model = db.aiModels.find(item => item.id === config.modelId)
  const ready = Boolean(model?.enabled && model.apiKey && model.capabilities?.includes(featureKeys[key]))
  return { config, model, ready, enabled: Boolean(config.enabled && ready) }
}

export function publicAppConfig(db, user = null) {
  const features = Object.fromEntries(Object.keys(featureKeys).map(key => {
    const { model, enabled } = configuredFeature(db, key)
    return [key, {
      enabled,
      modelId: enabled ? model.id : '',
      displayName: enabled ? model.displayName : '',
      provider: enabled ? model.provider : '',
      model: enabled ? model.model : '',
      requiresLogin: true,
      requiresVip: key !== 'speechRecognition',
      endpoint: key === 'speechRecognition' ? '/api/app/speech/transcribe' : key === 'replySuggestions' ? '/api/app/text/reply/chat/completions' : key === 'superSpeak' ? '/api/app/text/polish/chat/completions' : '/api/app/vision/reply',
      ...(key === 'speechRecognition' ? { maxAudioSeconds: 90 } : {})
    }]
  }))
  return { schemaVersion: 1, user: configUser(db, user), features }
}

function wavAudio(body) {
  if (body?.format !== 'wav') throw badRequest('语音格式必须为 wav')
  const encoded = String(body?.audioBase64 || '')
  if (!encoded || encoded.length > Math.ceil((MAX_PCM_BYTES + 44) / 3) * 4 ||
      encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) {
    throw badRequest('语音内容为空、格式不正确或超过 90 秒')
  }
  const audio = Buffer.from(encoded, 'base64')
  if (audio.length < 46 || audio.length > MAX_PCM_BYTES + 44 ||
      audio.toString('ascii', 0, 4) !== 'RIFF' || audio.toString('ascii', 8, 12) !== 'WAVE' ||
      audio.toString('ascii', 12, 16) !== 'fmt ' || audio.readUInt32LE(16) !== 16 ||
      audio.readUInt16LE(20) !== 1 || audio.readUInt16LE(22) !== 1 ||
      audio.readUInt32LE(24) !== 16_000 || audio.readUInt16LE(34) !== 16 ||
      audio.toString('ascii', 36, 40) !== 'data' || audio.readUInt32LE(40) !== audio.length - 44 ||
      audio.readUInt32LE(4) !== audio.length - 8 || (audio.length - 44) % 2 !== 0) {
    throw badRequest('请提交 16kHz、单声道、16 位 PCM WAV 语音')
  }
  return { encoded, audioSeconds: (audio.length - 44) / 32_000 }
}

function transcript(payload) {
  const content = payload?.choices?.[0]?.message?.content
  const text = typeof content === 'string' ? content : Array.isArray(content)
    ? content.map(item => typeof item.text === 'string' ? item.text : '').join('') : ''
  return text.replace(/<\/?(chinese|english|mandarin|cantonese|zh|en|transcript|audio)>/gi, '')
    .replace(/(?<=[\u4E00-\u9FFF])\s*null\s*$/i, '').trim()
}

export function installAppConfiguration(app, {
  db, save, requireAuth, requireAppUser, optionalAppUser = (req, res, next) => next(), wrap, recordModelUsage = () => {}, fetchImpl = fetch
}) {
  app.get('/api/business', requireAuth, (req, res) => {
    const result = Object.fromEntries(Object.keys(featureKeys).map(key => {
      const { config, model, ready } = configuredFeature(db, key)
      return [key, { ...config, modelDisplayName: model?.displayName || '', modelReady: ready }]
    }))
    res.json(result)
  })

  app.put('/api/business', requireAuth, wrap(async (req, res) => {
    const body = req.body || {}
    const previous = db.business
    const next = {
      ...previous,
      visionReply: { ...emptyFeature(), ...previous?.visionReply },
      speechRecognition: { ...emptyFeature(), ...previous?.speechRecognition },
      replySuggestions: { ...emptyFeature(), ...previous?.replySuggestions },
      superSpeak: { ...emptyFeature(), ...previous?.superSpeak }
    }
    if (Object.hasOwn(body, 'visionReplyEnabled') || Object.hasOwn(body, 'modelId')) {
      next.visionReply = {
        enabled: Object.hasOwn(body, 'visionReplyEnabled') ? body.visionReplyEnabled === true : next.visionReply.enabled,
        modelId: Object.hasOwn(body, 'modelId') ? String(body.modelId || '') : next.visionReply.modelId
      }
    }
    for (const key of ['speechRecognition', 'replySuggestions', 'superSpeak']) {
      if (!Object.hasOwn(body, key)) continue
      if (!body[key] || typeof body[key].enabled !== 'boolean') throw badRequest('业务配置格式不正确')
      next[key] = { enabled: body[key].enabled, modelId: String(body[key].modelId || '') }
    }
    for (const key of Object.keys(featureKeys)) {
      const config = next[key]
      if (!config.enabled) continue
      const model = db.aiModels.find(item => item.id === config.modelId)
      const label = key === 'speechRecognition' ? '语音识别' : key === 'replySuggestions' ? '粘贴TA的话' : key === 'superSpeak' ? '超会说' : '截图识别回复'
      if (!model) throw badRequest(`请选择${label}使用的模型`)
      if (!model.enabled || !model.apiKey) throw badRequest('所选模型未上架或未配置密钥，请先在「模型配置」完成')
      if (!model.capabilities?.includes(featureKeys[key])) throw badRequest(`所选模型不支持${label}能力`)
    }
    db.business = next
    try { save() } catch (error) { db.business = previous; throw error }
    res.json({ ok: true })
  }))

  app.get('/api/app/config', optionalAppUser, (req, res) => {
    res.vary('Authorization').set('Cache-Control', 'private, no-store').json(publicAppConfig(db, req.appUser))
  })
  // Existing clients still use this flag endpoint.
  app.get('/api/app/business', (req, res) => {
    const config = publicAppConfig(db)
    res.set('Cache-Control', 'no-store').json({
      visionReplyEnabled: config.features.visionReply.enabled,
      speechRecognitionEnabled: config.features.speechRecognition.enabled
    })
  })

  const active = new Map()
  app.post('/api/app/speech/transcribe', requireAppUser, wrap(async (req, res) => {
    const { model, enabled } = configuredFeature(db, 'speechRecognition')
    if (!enabled) return res.status(403).json({ message: '语音识别暂未开放，请稍后再试' })
    const audio = wavAudio(req.body)
    const userId = req.appUser.id
    if ((active.get(userId) || 0) >= 2) return res.status(429).json({ message: '语音识别请求过多，请稍候重试' })
    active.set(userId, (active.get(userId) || 0) + 1)
    const controller = new AbortController()
    const abort = () => controller.abort()
    req.on('aborted', abort)
    res.on('close', abort)
    const timeout = setTimeout(abort, 20_000)
    try {
      const base = model.baseUrl.replace(/\/+$/, '')
      const endpoint = base.endsWith('/chat/completions') ? base : `${base}/chat/completions`
      const host = new URL(endpoint).hostname
      const mimo = host === 'xiaomimimo.com' || host.endsWith('.xiaomimimo.com')
      const response = await fetchImpl(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...(mimo ? { 'api-key': model.apiKey } : { Authorization: `Bearer ${model.apiKey}` }) },
        body: JSON.stringify({ model: model.model, stream: false, messages: [{
          role: 'user', content: [{ type: 'input_audio', input_audio: { data: `data:audio/wav;base64,${audio.encoded}`, format: 'wav' } }]
        }] }),
        signal: controller.signal
      })
      if (!response.ok) {
        await response.body?.cancel()
        return res.status(502).json({ message: '语音服务暂时不可用，请稍后重试' })
      }
      const payload = await response.json()
      const text = transcript(payload)
      if (!text) return res.status(422).json({ message: '没有识别到语音，请重试' })
      const usage = payload.usage || {}
      recordModelUsage({ modelId: model.id, userId, feature: '语音识别',
        audioSeconds: audio.audioSeconds,
        inputTokens: Math.max(0, Number(usage.prompt_tokens) || 0), outputTokens: Math.max(0, Number(usage.completion_tokens) || 0) })
      if (!res.destroyed) res.json({ text, modelId: model.id, displayName: model.displayName, requestId: crypto.randomUUID() })
    } catch (error) {
      if (!res.destroyed && !res.headersSent) res.status(502).json({ message: controller.signal.aborted ? '语音识别超时，请重试' : '语音服务连接失败，请稍后重试' })
    } finally {
      clearTimeout(timeout)
      req.off('aborted', abort)
      res.off('close', abort)
      const count = (active.get(userId) || 1) - 1
      if (count) active.set(userId, count)
      else active.delete(userId)
    }
  }))
}
