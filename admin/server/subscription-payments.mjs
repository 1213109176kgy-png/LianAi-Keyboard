import crypto from 'node:crypto'
import { buildPaymentUrl, verifyNotifySign, queryTrade, yuanToFen } from './alipay.mjs'

export const ORDER_TTL_MS = 15 * 60 * 1000
const DAY_MS = 86400000

// Shared by signed callbacks and signed active queries. Browser return URLs
// never grant membership. Order snapshots survive plan edits/deletion.
export function createPaymentLedger(db, save, clock = Date.now) {
  const markOrderPaid = (order, channelTradeNo = '', metadata = {}) => {
    if (order.status === 'paid') return { ok: true, order, alreadyPaid: true }
    const previousOrder = { ...order }
    const now = clock()
    const existing = db.subscriptions.find(item => item.userId === order.userId)
    const previousSubscription = existing ? { ...existing } : null
    const expiresAt = Math.max(now, existing?.expiresAt || 0) + order.periodDays * DAY_MS
    const value = {
      userId: order.userId, planId: order.planId, planName: order.planName,
      dailyQuota: order.dailyQuota ?? 0, expiresAt, updatedAt: now
    }
    if (existing) Object.assign(existing, value)
    else db.subscriptions.push(value)
    Object.assign(order, { status: 'paid', paidAt: now, channelTradeNo,
      entitlementGrantedAt: now, entitlementExpiresAt: expiresAt, ...metadata })
    try { save() } catch (error) {
      // A failed disk write must not turn a later retry into an in-memory
      // success that never persisted. Restore both sides before retrying.
      for (const key of Object.keys(order)) delete order[key]
      Object.assign(order, previousOrder)
      if (existing) {
        for (const key of Object.keys(existing)) delete existing[key]
        Object.assign(existing, previousSubscription)
      } else db.subscriptions.splice(db.subscriptions.indexOf(value), 1)
      throw error
    }
    return { ok: true, order }
  }

  return {
    activateFree(order) {
      if (order.amountFen !== 0 || order.channel) return { ok: false, reason: '不是免费订单' }
      return markOrderPaid(order, '', { settlementSource: 'free' })
    },
    settleManually(order, { username, reason }) {
      if (!username) return { ok: false, reason: '缺少操作人' }
      if (!db.appUsers.some(user => user.id === order.userId)) return { ok: false, reason: '订单用户不存在' }
      if (order.status === 'paid') return { ok: true, order, alreadyPaid: true }
      if (!Number.isSafeInteger(order.amountFen) || order.amountFen < 0 ||
          !Number.isInteger(order.periodDays) || order.periodDays < 1 || order.periodDays > 3650 ||
          !Number.isInteger(order.dailyQuota ?? 0) || (order.dailyQuota ?? 0) < 0) {
        return { ok: false, reason: '订单的套餐权益数据无效' }
      }
      return markOrderPaid(order, '', { settlementSource: 'manual',
        manualPaidAt: clock(), manualPaidBy: username, manualReason: reason })
    },
    settleAlipay(trade, appId, sellerId = '', notification = false) {
      const order = db.orders.find(item => item.outTradeNo === trade.out_trade_no)
      if (!order || order.channel !== 'alipay') return { ok: false, reason: '订单不存在或支付渠道不匹配' }
      if (notification && trade.app_id !== (order.appId || appId)) return { ok: false, reason: '应用不匹配' }
      if (!notification && (order.appId || appId) !== appId) return { ok: false, reason: '订单所属应用已变更' }
      const expectedSeller = order.sellerId || sellerId
      if (expectedSeller && trade.seller_id !== expectedSeller) return { ok: false, reason: '收款方不匹配' }
      if (!['TRADE_SUCCESS', 'TRADE_FINISHED'].includes(trade.trade_status)) return { ok: false, reason: '交易尚未支付' }
      if (yuanToFen(trade.total_amount) !== order.amountFen || order.amountFen <= 0) return { ok: false, reason: '金额不匹配' }
      if (!trade.trade_no) return { ok: false, reason: '缺少支付宝交易号' }
      if (order.status === 'paid') {
        if (order.channelTradeNo === trade.trade_no) return { ok: true, order, alreadyPaid: true }
        if (order.channelTradeNo || order.settlementSource !== 'manual') return { ok: false, reason: '交易号不匹配' }
        // A real callback after manual completion only attaches its verified
        // provider reference; the subscription is never credited twice.
        const previous = { ...order }
        Object.assign(order, { channelTradeNo: trade.trade_no, providerConfirmedAt: clock() })
        try { save() } catch (error) {
          for (const key of Object.keys(order)) delete order[key]
          Object.assign(order, previous)
          throw error
        }
        return { ok: true, order, alreadyPaid: true }
      }
      // A genuine payment can be delivered after the local deadline. It must
      // still grant the purchased membership exactly once.
      return markOrderPaid(order, trade.trade_no, { settlementSource: 'alipay' })
    }
  }
}

export function installSubscriptionPayments(app, { db, save, requireAppUser, wrap, channelReady,
  provider = { buildPaymentUrl, verifyNotifySign, queryTrade }, clock = Date.now }) {
  const ledger = createPaymentLedger(db, save, clock)
  const fail = (message, status = 400) => Object.assign(new Error(message), { status })
  const config = () => db.payment.alipay
  const ready = () => Boolean(config().enabled && channelReady(config(), 'alipay'))
  const pendingQueries = new Map()
  const lastQueries = new Map()

  async function reconcile(order) {
    if (order.channel !== 'alipay' || order.status === 'paid' || order.status === 'closed') return
    if ((order.appId || config().appId) !== config().appId) throw fail('订单所属应用已变更，请联系管理员', 409)
    if (pendingQueries.has(order.id)) return pendingQueries.get(order.id)
    if (lastQueries.has(order.id) && clock() - lastQueries.get(order.id) < 5000) return
    const promise = (async () => {
      const trade = await provider.queryTrade(config(), order.outTradeNo)
      lastQueries.set(order.id, clock())
      // Keep the throttle cache bounded; only recent active orders need it.
      if (lastQueries.size > 1000) lastQueries.delete(lastQueries.keys().next().value)
      if (order.status === 'paid') return // callback won the race
      if (trade && trade.out_trade_no !== order.outTradeNo) throw fail('支付宝返回订单不匹配', 502)
      if (trade && ['TRADE_SUCCESS', 'TRADE_FINISHED'].includes(trade.trade_status)) {
        const settled = ledger.settleAlipay(trade, config().appId, config().sellerId)
        if (!settled.ok) throw fail('支付信息校验失败：' + settled.reason, 502)
      } else if (trade?.trade_status === 'TRADE_CLOSED') {
        order.status = 'closed'; save()
      } else if (clock() > order.expiresAt && order.status === 'created') {
        order.status = 'expired'; save()
      }
    })()
    pendingQueries.set(order.id, promise)
    try { await promise } finally { pendingQueries.delete(order.id) }
  }

  const orderView = order => ({
    orderId: order.outTradeNo, status: order.status, amountFen: order.amountFen,
    planName: order.planName, createdAt: order.createdAt, expiresAt: order.expiresAt,
    paidAt: order.paidAt || null,
    payUrl: order.status === 'created' && clock() < order.expiresAt ? order.payUrl || null : null,
    free: order.amountFen === 0
  })

  app.get('/api/app/payment/channels', (req, res) => res.json({ channels: ready() ? ['alipay'] : [] }))

  app.post('/api/app/orders', requireAppUser, wrap(async (req, res) => {
    const planId = String(req.body?.planId || '')
    if (req.body?.channel && req.body.channel !== 'alipay') throw fail('当前仅支持支付宝支付')
    const requestId = String(req.body?.requestId || '')
    if (requestId && !/^[\w-]{16,80}$/.test(requestId)) throw fail('请求标识无效')
    if (requestId) {
      const existing = db.orders.find(item => item.userId === req.appUser.id && item.requestId === requestId)
      if (existing) {
        if (existing.planId !== planId) throw fail('请求标识已被其他套餐使用')
        if (existing.amountFen === 0 && existing.status !== 'paid') ledger.activateFree(existing)
        res.json(orderView(existing)); return
      }
    }
    const plan = db.plans.find(item => item.id === planId && item.enabled)
    if (!plan) throw fail('套餐不存在或已下架')
    if (plan.priceFen === 0 && db.orders.some(item => item.userId === req.appUser.id && item.planId === plan.id && item.status === 'paid' && item.amountFen === 0)) {
      throw fail('该免费套餐已领取过')
    }
    if (plan.priceFen > 0) {
      if (!ready()) throw fail('支付宝通道未启用，请稍后再试')
      if (!/^https:\/\//.test(db.payment.publicBaseUrl)) throw fail('支付回调域名未配置')
    }
    const now = clock()
    const id = `LA${now}${crypto.randomBytes(6).toString('hex').toUpperCase()}`
    const order = {
      id, outTradeNo: id, requestId, userId: req.appUser.id, planId: plan.id,
      planName: plan.name, periodDays: plan.periodDays, dailyQuota: plan.dailyQuota,
      amountFen: plan.priceFen, channel: plan.priceFen > 0 ? 'alipay' : '',
      appId: plan.priceFen > 0 ? config().appId : '', sellerId: config().sellerId || '',
      status: 'created', createdAt: now, expiresAt: now + ORDER_TTL_MS
    }
    if (order.amountFen > 0) {
      const base = db.payment.publicBaseUrl.replace(/\/+$/, '')
      order.payUrl = await provider.buildPaymentUrl(config(), {
        outTradeNo: id, amountFen: plan.priceFen, subject: `恋爱键盘·${plan.name}`,
        notifyUrl: `${base}/api/pay/notify/alipay`,
        returnUrl: `${base}/h5/alipay-return.html?orderId=${encodeURIComponent(id)}`
      })
    }
    db.orders.push(order)
    try { save() } catch (error) { db.orders.splice(db.orders.indexOf(order), 1); throw error }
    if (order.amountFen === 0) ledger.activateFree(order)
    res.json(orderView(order))
  }))

  app.get('/api/app/orders/:id', requireAppUser, wrap(async (req, res) => {
    const order = db.orders.find(item => item.outTradeNo === req.params.id && item.userId === req.appUser.id)
    if (!order) throw fail('订单不存在', 404)
    await reconcile(order)
    res.json(orderView(order))
  }))

  app.get('/api/app/me/subscription', requireAppUser, (req, res) => {
    const item = db.subscriptions.find(entry => entry.userId === req.appUser.id)
    const now = clock()
    res.json({ active: Boolean(item && item.expiresAt > now), planName: item?.planName || '',
      planId: item?.planId || '', dailyQuota: item?.dailyQuota ?? 0,
      expiresAt: item?.expiresAt || 0,
      daysLeft: item && item.expiresAt > now ? Math.ceil((item.expiresAt - now) / DAY_MS) : 0 })
  })

  app.post('/api/pay/notify/alipay', wrap(async (req, res) => {
    const params = req.body || {}
    if (!provider.verifyNotifySign(params, config())) { res.status(400).send('fail'); return }
    if (!['TRADE_SUCCESS', 'TRADE_FINISHED'].includes(params.trade_status)) { res.send('success'); return }
    const result = ledger.settleAlipay(params, config().appId, config().sellerId, true)
    res.status(result.ok ? 200 : 400).send(result.ok ? 'success' : 'fail')
  }))
  return ledger
}
