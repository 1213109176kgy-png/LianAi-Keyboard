export const modelBillingUnit = model => model?.capabilities?.includes('asr') ? 'audio_hour' : 'tokens'

// 成本统计允许小于一分钱，避免短语音逐次取整后全部变为零。
export function modelUsageCost(model, { inputTokens = 0, outputTokens = 0, audioSeconds } = {}) {
  if (audioSeconds !== undefined) {
    if (!Number.isFinite(audioSeconds) || audioSeconds <= 0) throw new Error('音频时长必须大于 0')
    const audioPriceFenPerHour = model?.audioPriceFenPerHour || 0
    return {
      billingUnit: 'audio_hour', audioSeconds, audioPriceFenPerHour,
      costFen: Math.round(audioSeconds / 3600 * audioPriceFenPerHour * 1_000_000) / 1_000_000
    }
  }
  // 保持已有文本、视觉模型的记账口径。
  return {
    billingUnit: 'tokens',
    costFen: Math.round(inputTokens / 1_000_000 * (model?.inputPriceFen || 0)) +
      Math.round(outputTokens / 1_000_000 * (model?.outputPriceFen || 0))
  }
}
