const badRequest = message => Object.assign(new Error(message), { status: 400 })

export const MODEL_CAPABILITIES = ['text', 'vision', 'asr']

export function parseModelInput(body, existing) {
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
  const audioPriceFenPerHour = Number(body?.audioPriceFenPerHour ?? existing?.audioPriceFenPerHour ?? 0)
  if (!provider) throw badRequest('请选择服务商')
  if (!model || model.length > 100) throw badRequest('请填写模型 ID（100 字以内）')
  if (!displayName || displayName.length > 60) throw badRequest('展示名不能超过 60 字')
  if (!/^https:\/\//.test(baseUrl)) throw badRequest('接口地址必须以 https:// 开头')
  if (!capabilities.length) throw badRequest('至少选择一种能力（文本 / 视觉 / 语音识别）')
  if (!Number.isInteger(inputPriceFen) || inputPriceFen < 0 || !Number.isInteger(outputPriceFen) || outputPriceFen < 0) {
    throw badRequest('价格必须是不小于 0 的数字（元，每百万 tokens）')
  }
  if (!Number.isSafeInteger(audioPriceFenPerHour) || audioPriceFenPerHour < 0) {
    throw badRequest('语音参考价格必须是不小于 0 的数字（元/小时，最多两位小数）')
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
    audioPriceFenPerHour,
    enabled: body?.enabled !== false
  }
}
