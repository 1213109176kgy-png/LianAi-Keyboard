import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import express from 'express'
import { buildPaymentUrl, verifyNotifySign, yuanToFen } from '../alipay.mjs'
import { createPaymentLedger, installSubscriptionPayments } from '../subscription-payments.mjs'

const appKeys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
const platformKeys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
const config = { enabled: true, appId: 'app-test', sellerId: '2088-test', gatewayUrl: 'https://openapi.alipay.com/gateway.do',
  privateKey: appKeys.privateKey.export({ type: 'pkcs8', format: 'pem' }),
  alipayPublicKey: platformKeys.publicKey.export({ type: 'spki', format: 'pem' }) }
const order = () => ({ id: 'LA1', outTradeNo: 'LA1', userId: 'user1', channel: 'alipay', appId: config.appId,
  planId: 'p1', planName: '月度会员', amountFen: 1200, periodDays: 30, dailyQuota: 10, status: 'created', expiresAt: 1 })
const trade = () => ({ app_id: config.appId, seller_id: config.sellerId, out_trade_no: 'LA1', trade_no: 'ali1', trade_status: 'TRADE_SUCCESS', total_amount: '12.00' })
function signNotify(data, includeType = false) {
  const payload = { ...data, sign_type: 'RSA2' }
  const source = Object.keys(payload).filter(k => includeType || k !== 'sign_type').sort().map(k => `${k}=${payload[k]}`).join('&')
  return { ...payload, sign: crypto.sign('RSA-SHA256', Buffer.from(source), platformKeys.privateKey).toString('base64') }
}

test('mobile checkout is signed, has notify/return/quit URLs and one 15-minute order', () => {
  const url = new URL(buildPaymentUrl(config, { outTradeNo: 'LA1', amountFen: 1, subject: '会员',
    notifyUrl: 'https://merchant.test/notify', returnUrl: 'https://merchant.test/return' }))
  assert.equal(url.searchParams.get('method'), 'alipay.trade.wap.pay')
  assert.equal(url.searchParams.get('notify_url'), 'https://merchant.test/notify')
  assert.equal(url.searchParams.get('return_url'), 'https://merchant.test/return')
  const biz = JSON.parse(url.searchParams.get('biz_content'))
  assert.equal(biz.total_amount, '0.01'); assert.equal(biz.timeout_express, '15m')
  assert.equal(biz.quit_url, 'https://merchant.test/return')
  const params = Object.fromEntries(url.searchParams)
  const source = Object.keys(params).filter(k => k !== 'sign' && params[k] !== '').sort().map(k => `${k}=${params[k]}`).join('&')
  assert.equal(crypto.verify('RSA-SHA256', Buffer.from(source), appKeys.publicKey, Buffer.from(params.sign, 'base64')), true)
})

test('official notification verification handles sign_type conventions, percent text and tampering', () => {
  const raw = { ...trade(), subject: '会员100% + 爱' }
  for (const include of [false, true]) {
    const signed = signNotify(raw, include)
    assert.equal(verifyNotifySign(signed, config), true)
    assert.equal(verifyNotifySign({ ...signed, total_amount: '1.00' }, config), false)
  }
})

test('money parsing rejects missing, malformed, floating exponent and excessive decimals', () => {
  assert.equal(yuanToFen('0.01'), 1); assert.equal(yuanToFen('12.0'), 1200)
  for (const v of [undefined, '', '-1', 'NaN', '1e2', '1.001']) assert.equal(yuanToFen(v), null)
})

test('late payment grants exactly once and renewals extend existing membership', () => {
  const db = { orders: [order()], subscriptions: [{ userId: 'user1', expiresAt: 10000 }] }
  let writes = 0; const ledger = createPaymentLedger(db, () => writes++, () => 5000)
  db.orders[0].status = 'expired'
  assert.equal(ledger.settleAlipay(trade(), config.appId, config.sellerId, true).ok, true)
  assert.equal(db.subscriptions[0].expiresAt, 10000 + 30 * 86400000)
  assert.equal(ledger.settleAlipay(trade(), config.appId, config.sellerId, true).ok, true)
  assert.equal(writes, 1)
})

test('settlement rejects wrong app, recipient, channel, amount and altered duplicate transaction', () => {
  for (const altered of [{ app_id: 'wrong' }, { seller_id: 'wrong' }, { total_amount: '1.00' }, { total_amount: undefined }, { trade_no: '' }]) {
    const db = { orders: [order()], subscriptions: [] }
    assert.equal(createPaymentLedger(db, () => {}).settleAlipay({ ...trade(), ...altered }, config.appId, config.sellerId, true).ok, false)
    assert.equal(db.subscriptions.length, 0)
  }
  const db = { orders: [{ ...order(), channel: 'wechat' }], subscriptions: [] }
  assert.equal(createPaymentLedger(db, () => {}).settleAlipay(trade(), config.appId, config.sellerId, true).ok, false)
  db.orders[0] = { ...order(), status: 'paid', channelTradeNo: 'other' }
  assert.equal(createPaymentLedger(db, () => {}).settleAlipay(trade(), config.appId, config.sellerId, true).ok, false)
})

test('HTTP checkout, recovery, ownership, callback and repeat free claims', async () => {
  const db = { payment: { publicBaseUrl: 'https://merchant.test', alipay: config },
    plans: [{ id: 'paid', name: '会员', priceFen: 1200, periodDays: 30, dailyQuota: 10, enabled: true },
      { id: 'free', name: '体验', priceFen: 0, periodDays: 1, enabled: true }], orders: [], subscriptions: [] }
  const app = express(); app.use(express.json()); app.use(express.urlencoded({ extended: false }))
  let queriedTrade = null; let queries = 0
  installSubscriptionPayments(app, { db, save() {}, channelReady: () => true,
    requireAppUser(req, res, next) { req.appUser = { id: req.headers.authorization || 'user1' }; next() },
    wrap: fn => (req, res) => Promise.resolve(fn(req, res)).catch(err => res.status(err.status || 500).json({ message: err.message })),
    provider: { buildPaymentUrl, verifyNotifySign, async queryTrade() { queries++; return queriedTrade } } })
  const server = app.listen(0); await new Promise(r => server.once('listening', r))
  const base = `http://127.0.0.1:${server.address().port}`
  const post = body => fetch(base + '/api/app/orders', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  try {
    assert.deepEqual(await (await fetch(base + '/api/app/payment/channels')).json(), { channels: ['alipay'] })
    assert.equal((await post({ planId: 'paid', channel: 'wechat' })).status, 400)
    const body = { planId: 'paid', requestId: crypto.randomUUID() }
    const created = await (await post(body)).json()
    db.plans[0].enabled = false // response-loss recovery survives a plan being taken off sale
    const repeat = await (await post(body)).json()
    assert.equal(repeat.orderId, created.orderId); assert.equal(db.orders.length, 1)
    assert.equal((await fetch(base + '/api/app/orders/' + created.orderId, { headers: { Authorization: 'another-user' } })).status, 404)
    assert.equal(queries, 0)
    // Spoofing the browser return is insufficient; the provider query is authoritative.
    queriedTrade = { ...trade(), out_trade_no: created.orderId }
    const paid = await (await fetch(base + '/api/app/orders/' + created.orderId)).json()
    assert.equal(paid.status, 'paid'); assert.equal(db.subscriptions.length, 1)
    const expiresAt = db.subscriptions[0].expiresAt
    const callback = await fetch(base + '/api/pay/notify/alipay', { method: 'POST', body: new URLSearchParams(signNotify(queriedTrade)) })
    assert.equal(await callback.text(), 'success'); assert.equal(db.subscriptions[0].expiresAt, expiresAt)
    const bad = await fetch(base + '/api/pay/notify/alipay', { method: 'POST', body: new URLSearchParams({ ...signNotify(queriedTrade), app_id: 'spoof' }) })
    assert.equal(bad.status, 400)
    assert.equal((await post({ planId: 'free' })).status, 200)
    assert.equal((await post({ planId: 'free' })).status, 400)
  } finally { await new Promise(r => server.close(r)) }
})


test('query failure preserves the pending order, callback wins query race, disabled channel rejects new checkout', async () => {
  const db = { payment: { publicBaseUrl: 'https://merchant.test', alipay: { ...config } },
    plans: [{ id: 'paid', name: '会员', priceFen: 1200, periodDays: 30, enabled: true }],
    orders: [order()], subscriptions: [] }
  let mode = 'failure', resolveQuery
  const app = express(); app.use(express.json()); app.use(express.urlencoded({ extended: false }))
  installSubscriptionPayments(app, { db, save() {}, channelReady: () => true,
    requireAppUser(req, res, next) { req.appUser = { id: 'user1' }; next() },
    wrap: fn => (req, res) => Promise.resolve(fn(req, res)).catch(err => res.status(err.status || 500).json({ message: err.message })),
    provider: { buildPaymentUrl, verifyNotifySign, queryTrade() {
      if (mode === 'failure') return Promise.reject(Object.assign(new Error('unavailable'), { status: 502 }))
      return new Promise(resolve => { resolveQuery = resolve })
    } } })
  const server = app.listen(0); await new Promise(r => server.once('listening', r))
  const base = `http://127.0.0.1:${server.address().port}`
  try {
    assert.equal((await fetch(base + '/api/app/orders/LA1')).status, 502)
    assert.equal(db.orders[0].status, 'created'); assert.equal(db.subscriptions.length, 0)
    mode = 'race'
    const polling = fetch(base + '/api/app/orders/LA1')
    while (!resolveQuery) await new Promise(r => setTimeout(r, 5))
    const callback = await fetch(base + '/api/pay/notify/alipay', { method: 'POST', body: new URLSearchParams(signNotify(trade())) })
    assert.equal(await callback.text(), 'success')
    const expiresAt = db.subscriptions[0].expiresAt
    resolveQuery(null)
    assert.equal((await (await polling).json()).status, 'paid')
    assert.equal(db.subscriptions[0].expiresAt, expiresAt)
    db.payment.alipay.enabled = false
    assert.deepEqual(await (await fetch(base + '/api/app/payment/channels')).json(), { channels: [] })
    const checkout = await fetch(base + '/api/app/orders', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ planId: 'paid' }) })
    assert.equal(checkout.status, 400)
  } finally { await new Promise(r => server.close(r)) }
})


test('a failed persistence write rolls back paid state and entitlement so notification retry can recover', () => {
  for (const subscribed of [false, true]) {
    const subscription = { userId: 'user1', planName: '旧套餐', expiresAt: 10000 }
    const db = { orders: [order()], subscriptions: subscribed ? [{ ...subscription }] : [] }
    let broken = true
    const ledger = createPaymentLedger(db, () => { if (broken) throw new Error('disk full') }, () => 5000)
    assert.throws(() => ledger.settleAlipay(trade(), config.appId, config.sellerId, true), /disk full/)
    assert.equal(db.orders[0].status, 'created'); assert.equal(db.orders[0].paidAt, undefined)
    assert.deepEqual(db.subscriptions, subscribed ? [subscription] : [])
    broken = false
    assert.equal(ledger.settleAlipay(trade(), config.appId, config.sellerId, true).ok, true)
    assert.equal(db.subscriptions.length, 1)
  }
})


test('selecting another plan creates a new order without changing the earlier unpaid order', async () => {
  const db = { payment: { publicBaseUrl: 'https://merchant.test', alipay: config },
    plans: [
      { id: 'p1', name: '套餐一', priceFen: 100, periodDays: 1, dailyQuota: 10, enabled: true },
      { id: 'p2', name: '套餐二', priceFen: 200, periodDays: 7, dailyQuota: 20, enabled: true }
    ], orders: [], subscriptions: [] }
  const app = express(); app.use(express.json())
  installSubscriptionPayments(app, { db, save() {}, channelReady: () => true,
    requireAppUser(req, res, next) { req.appUser = { id: 'user1' }; next() },
    wrap: fn => (req, res) => Promise.resolve(fn(req, res)).catch(err => res.status(err.status || 500).json({ message: err.message })),
    provider: { buildPaymentUrl, verifyNotifySign, async queryTrade() { return null } } })
  const server = app.listen(0); await new Promise(r => server.once('listening', r))
  const base = `http://127.0.0.1:${server.address().port}`
  const post = data => fetch(base + '/api/app/orders', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) })
  try {
    const first = await (await post({ planId: 'p1', requestId: crypto.randomUUID() })).json()
    const second = await (await post({ planId: 'p2', requestId: crypto.randomUUID() })).json()
    assert.notEqual(first.orderId, second.orderId)
    assert.equal(db.orders.length, 2)
    assert.deepEqual(db.orders.map(o => [o.planId, o.status]), [['p1', 'created'], ['p2', 'created']])
    assert.equal(JSON.parse(new URL(second.payUrl).searchParams.get('biz_content')).total_amount, '2.00')
  } finally { await new Promise(r => server.close(r)) }
})


test('manual completion credits the order snapshot once, persists its audit and accepts a later real callback without double credit', () => {
  const db = { orders: [{ ...order(), status: 'expired' }], subscriptions: [{ userId: 'user1', expiresAt: 10000 }],
    appUsers: [{ id: 'user1' }], plans: [{ id: 'p1', periodDays: 999, dailyQuota: 999 }] }
  let persisted
  const ledger = createPaymentLedger(db, () => { persisted = structuredClone(db) }, () => 5000)
  const result = ledger.settleManually(db.orders[0], { username: 'admin', reason: '已核实到账' })
  assert.equal(result.ok, true)
  assert.equal(db.subscriptions[0].dailyQuota, 10)
  const expires = 10000 + 30 * 86400000
  assert.equal(db.subscriptions[0].expiresAt, expires)
  assert.equal(db.orders[0].status, 'paid')
  assert.equal(db.orders[0].manualPaidBy, 'admin')
  assert.equal(db.orders[0].manualReason, '已核实到账')
  assert.equal(db.orders[0].manualPaidAt, 5000)
  assert.equal(db.orders[0].channelTradeNo, '')
  const restartLedger = createPaymentLedger(persisted, () => {}, () => 6000)
  assert.equal(restartLedger.settleManually(persisted.orders[0], { username: 'admin2', reason: '重复点击' }).alreadyPaid, true)
  assert.equal(persisted.subscriptions[0].expiresAt, expires)
  assert.equal(persisted.orders[0].manualPaidBy, 'admin')
  assert.equal(restartLedger.settleAlipay(trade(), config.appId, config.sellerId, true).ok, true)
  assert.equal(persisted.orders[0].channelTradeNo, 'ali1')
  assert.equal(persisted.orders[0].providerConfirmedAt, 6000)
  assert.equal(persisted.subscriptions[0].expiresAt, expires)
  assert.equal(restartLedger.settleAlipay(trade(), config.appId, config.sellerId, true).alreadyPaid, true)
  assert.equal(restartLedger.settleAlipay({ ...trade(), trade_no: 'different' }, config.appId, config.sellerId, true).ok, false)
})


test('manual completion rejects missing users/invalid entitlements and rolls back failed persistence', () => {
  for (const variant of [{ userId: 'missing' }, { periodDays: 0 }, { dailyQuota: -1 }, { amountFen: -1 }]) {
    const db = { orders: [{ ...order(), ...variant }], appUsers: [{ id: 'user1' }], subscriptions: [] }
    assert.equal(createPaymentLedger(db, () => {}).settleManually(db.orders[0], { username: 'admin', reason: '补单' }).ok, false)
    assert.equal(db.orders[0].status, 'created'); assert.equal(db.subscriptions.length, 0)
  }
  const db = { orders: [order()], appUsers: [{ id: 'user1' }], subscriptions: [] }
  const ledger = createPaymentLedger(db, () => { throw new Error('disk full') })
  assert.throws(() => ledger.settleManually(db.orders[0], { username: 'admin', reason: '补单' }), /disk full/)
  assert.equal(db.orders[0].status, 'created')
  assert.equal(db.orders[0].manualPaidBy, undefined)
  assert.equal(db.subscriptions.length, 0)
})


test('admin order routes enforce authentication, filter safely, paginate and complete expired orders idempotently', async () => {
  const { installOrderManagement } = await import('../order-management.mjs')
  const db = { orders: [
    { ...order(), payUrl: 'sensitive-checkout-url', requestId: 'private-request-id', createdAt: 300 },
    { ...order(), id: 'LA2', outTradeNo: 'LA2', channel: '', amountFen: 0, createdAt: 200, expiresAt: 2000000 },
    { ...order(), id: 'LA3', outTradeNo: 'LA3', status: 'closed', userId: 'deleted', createdAt: 100 }
  ], appUsers: [{ id: 'user1', phone: '13800000000', passwordHash: 'secret-password', tokens: ['secret-token'] }], subscriptions: [] }
  const app = express(); app.use(express.json())
  installOrderManagement(app, { db, clock: () => 1000000, ledger: createPaymentLedger(db, () => {}, () => 1000000),
    requireAuth(req, res, next) {
      if (req.headers.authorization !== 'Bearer admin-test') return res.status(401).json({ message: '请先登录' })
      req.username = 'admin'; next()
    }, wrap: fn => (req, res) => Promise.resolve(fn(req, res)).catch(err => res.status(err.status || 500).json({ message: err.message })) })
  const server = app.listen(0); await new Promise(r => server.once('listening', r))
  const base = `http://127.0.0.1:${server.address().port}`
  const headers = { Authorization: 'Bearer admin-test', 'Content-Type': 'application/json' }
  try {
    assert.equal((await fetch(base + '/api/orders')).status, 401)
    assert.equal((await fetch(base + '/api/orders/LA1')).status, 401)
    assert.equal((await fetch(base + '/api/orders/LA1/complete', { method: 'POST', headers: { Authorization: 'Bearer app-user' } })).status, 401)
    const list = await (await fetch(base + '/api/orders?pageSize=1', { headers })).json()
    assert.equal(list.total, 3); assert.equal(list.orders.length, 1)
    assert.equal(list.orders[0].status, 'expired'); assert.equal(db.orders[0].status, 'created')
    const raw = JSON.stringify(list)
    for (const secret of ['sensitive-checkout-url', 'private-request-id', 'secret-password', 'secret-token']) assert.equal(raw.includes(secret), false)
    const filtered = await (await fetch(base + '/api/orders?status=expired&channel=alipay&search=13800000000', { headers })).json()
    assert.equal(filtered.total, 1)
    assert.equal((await fetch(base + '/api/orders?status=unknown', { headers })).status, 400)
    assert.equal((await fetch(base + '/api/orders/missing/complete', { method: 'POST', headers })).status, 404)
    assert.equal((await fetch(base + '/api/orders/LA3/complete', { method: 'POST', headers })).status, 400)
    assert.equal((await fetch(base + '/api/orders/LA1/complete', { method: 'POST', headers, body: JSON.stringify({ reason: 'x'.repeat(201) }) })).status, 400)
    const complete = () => fetch(base + '/api/orders/LA1/complete', { method: 'POST', headers, body: JSON.stringify({ reason: '人工核实' }) }).then(r => r.json())
    const results = await Promise.all([complete(), complete()])
    assert.equal(results.filter(r => r.alreadyPaid).length, 1)
    assert.equal(db.subscriptions.length, 1)
    assert.equal(db.subscriptions[0].expiresAt, 1000000 + 30 * 86400000)
    const detail = await (await fetch(base + '/api/orders/LA1', { headers })).json()
    assert.equal(detail.order.manualPaidBy, 'admin'); assert.equal(detail.order.canComplete, false)
    assert.equal(detail.subscription.dailyQuota, 10)
    const paid = await (await fetch(base + '/api/orders?status=paid', { headers })).json()
    assert.equal(paid.summary.manualCount, 1); assert.equal(paid.summary.paidAmountFen, 1200)
  } finally { await new Promise(r => server.close(r)) }
})
