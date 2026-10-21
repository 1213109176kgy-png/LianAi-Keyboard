import { configuredFeature } from './app-config.mjs'
import { requireVip } from './model-access.mjs'

const badRequest = message => Object.assign(new Error(message), { status: 400 })
export function providerHeaders(model) {
  const host = new URL(model.baseUrl).hostname
  const mimo = host === 'xiaomimimo.com' || host.endsWith('.xiaomimimo.com')
  return { 'Content-Type': 'application/json', ...(mimo ? { 'api-key': model.apiKey } : { Authorization: `Bearer ${model.apiKey}` }) }
}

function messagesOf(body) {
  if (!Array.isArray(body?.messages) || !body.messages.length || body.messages.length > 32) throw badRequest('文本请求格式不正确')
  let length = 0
  return body.messages.map(item => {
    if (!['system', 'user', 'assistant'].includes(item?.role) || typeof item.content !== 'string') throw badRequest('仅支持文本消息')
    length += item.content.length
    if (length > 50_000) throw badRequest('文本过长，请缩短后重试')
    return { role: item.role, content: item.content }
  })
}

export function installTextGeneration(app, { db, requireAppUser, wrap, recordModelUsage, fetchImpl = fetch }) {
  const active = new Map()
  for (const [path, key, feature] of [
    ['reply', 'replySuggestions', '粘贴TA的话（帮你回）'],
    ['polish', 'superSpeak', '超会说']
  ]) {
    app.post(`/api/app/text/${path}/chat/completions`, requireAppUser, requireVip(db), wrap(async (req, res) => {
      const { model, enabled } = configuredFeature(db, key)
      if (!enabled) return res.status(403).json({ code: 'MODEL_NOT_CONFIGURED', message: '该业务未配置官方模型，请在 App「模型配置」中配置自己的文本模型' })
      const messages = messagesOf(req.body)
      const userId = req.appUser.id
      if ((active.get(userId) || 0) >= 3) return res.status(429).json({ message: '请求过多，请稍后重试' })
      active.set(userId, (active.get(userId) || 0) + 1)
      const controller = new AbortController()
      const abort = () => controller.abort()
      res.on('close', abort)
      const timer = setTimeout(abort, 80_000)
      let usage = {}; let content = ''; let completed = false
      const stream = req.body?.stream === true
      try {
        const base = model.baseUrl.replace(/\/+$/, '')
        const response = await fetchImpl(base.endsWith('/chat/completions') ? base : `${base}/chat/completions`, {
          method: 'POST', headers: providerHeaders(model), signal: controller.signal,
          body: JSON.stringify({ model: model.model, messages, stream, max_tokens: 4096,
            temperature: Number.isFinite(req.body.temperature) ? Math.max(0, Math.min(2, req.body.temperature)) : 0.2,
            ...(stream ? { stream_options: { include_usage: true } } : {}) })
        })
        if (!response.ok) {
          await response.body?.cancel()
          return res.status(502).json({ message: '文本模型服务暂不可用，请稍后重试' })
        }
        if (stream) {
          if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('Invalid stream')
          res.set({ 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' })
          res.flushHeaders()
          const decoder = new TextDecoder(); let buffer = ''
          const line = value => {
            if (!value.startsWith('data:')) return
            const data = value.slice(5).trim()
            if (!data || data === '[DONE]') return
            const json = JSON.parse(data)
            if (json.error) throw new Error('Provider stream error')
            if (json.usage) usage = json.usage
            const delta = json.choices?.[0]?.delta?.content
            if (typeof delta === 'string' && delta) {
              content += delta
              if (content.length > 100_000) throw new Error('Response too large')
              if (!res.destroyed) res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: delta } }] })}\n\n`)
            }
          }
          for await (const chunk of response.body) {
            buffer += decoder.decode(chunk, { stream: true })
            let end
            while ((end = buffer.indexOf('\n')) >= 0) { line(buffer.slice(0, end).trimEnd()); buffer = buffer.slice(end + 1) }
            if (buffer.length > 200_000) throw new Error('Invalid stream')
          }
          buffer += decoder.decode()
          if (buffer.trim()) line(buffer.trim())
        } else {
          const payload = await response.json()
          content = payload.choices?.[0]?.message?.content
          usage = payload.usage || {}
        }
        if (typeof content !== 'string' || !content.trim()) throw new Error('Empty response')
        recordModelUsage({ modelId: model.id, userId, feature,
          inputTokens: Math.max(0, Number(usage.prompt_tokens) || 0), outputTokens: Math.max(0, Number(usage.completion_tokens) || 0) })
        completed = true
        if (!res.destroyed) {
          if (stream) res.end('data: [DONE]\n\n')
          else res.json({ choices: [{ message: { role: 'assistant', content } }], usage })
        }
      } catch {
        if (!res.destroyed) {
          if (res.headersSent) res.destroy()
          else res.status(502).json({ message: controller.signal.aborted ? '文本模型请求超时，请重试' : '文本模型请求失败，请稍后重试' })
        }
      } finally {
        clearTimeout(timer); res.off('close', abort)
        if (!completed) controller.abort()
        const count = (active.get(userId) || 1) - 1
        if (count) active.set(userId, count); else active.delete(userId)
      }
    }))
  }
}
