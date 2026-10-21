import test from 'node:test'
import assert from 'node:assert/strict'
import { parseModelInput } from '../model-config.mjs'
import { modelBillingUnit, modelUsageCost } from '../model-pricing.mjs'

const asr = { provider: 'xiaomi_mimo', model: 'mimo-v2.5-asr', capabilities: ['asr'],
  baseUrl: 'https://api.xiaomimimo.com/v1', audioPriceFenPerHour: 50, inputPriceFen: 900, outputPriceFen: 1200 }

test('ASR costs use audio hours, ignore token counts and retain sub-cent costs', () => {
  assert.equal(modelBillingUnit(asr), 'audio_hour')
  assert.equal(modelUsageCost(asr, { audioSeconds: 3600, inputTokens: 1_000_000 }).costFen, 50)
  assert.equal(modelUsageCost(asr, { audioSeconds: 60 }).costFen, 0.833333)
  assert.equal(modelUsageCost(asr, { audioSeconds: 1 }).costFen, 0.013889)
  assert.equal(modelUsageCost(asr, { audioSeconds: 0.1 }).costFen, 0.001389)
  for (const audioSeconds of [0, -1, NaN, Infinity]) assert.throws(() => modelUsageCost(asr, { audioSeconds }))
})

test('reference-price changes preserve usage price snapshots and legacy token rounding', () => {
  const model = { ...asr }
  const record = modelUsageCost(model, { audioSeconds: 60 })
  model.audioPriceFenPerHour = 100
  assert.equal(record.audioPriceFenPerHour, 50)
  assert.equal(record.costFen, 0.833333)
  assert.equal(modelUsageCost(model, { audioSeconds: 60 }).costFen, 1.666667)
  assert.deepEqual(modelUsageCost({ inputPriceFen: 200, outputPriceFen: 800 }, { inputTokens: 1000, outputTokens: 1000 }), { billingUnit: 'tokens', costFen: 1 })
  assert.equal(modelBillingUnit({ capabilities: ['text', 'vision'] }), 'tokens')
  assert.equal(modelUsageCost({ capabilities: ['asr'] }, { audioSeconds: 10 }).costFen, 0)
})

test('model configuration accepts hourly prices and preserves them for older clients', () => {
  assert.equal(parseModelInput(asr).audioPriceFenPerHour, 50)
  const { audioPriceFenPerHour, ...legacyBody } = asr
  assert.equal(parseModelInput(legacyBody, asr).audioPriceFenPerHour, 50)
  assert.equal(parseModelInput(legacyBody).audioPriceFenPerHour, 0)
  for (const price of [-1, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => parseModelInput({ ...asr, audioPriceFenPerHour: price }), /语音参考价格/)
  }
})
