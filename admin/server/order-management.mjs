const ORDER_STATUSES = new Set(['created', 'paid', 'expired', 'closed'])
const CHANNELS = new Set(['alipay', 'wechat', 'aggregator', 'free'])

export function installOrderManagement(app, { db, requireAuth, wrap, ledger, clock = Date.now }) {
  const fail = (message, status = 400) => Object.assign(new Error(message), { status })
  const userOf = order => db.appUsers.find(user => user.id === order.userId)
  const subscriptionOf = order => {
    const item = db.subscriptions.find(sub => sub.userId === order.userId)
    return item ? { planId: item.planId, planName: item.planName, dailyQuota: item.dailyQuota,
      expiresAt: item.expiresAt, active: item.expiresAt > clock() } : null
  }
  const view = order => {
    const user = userOf(order)
    const status = order.status === 'created' && order.expiresAt && order.expiresAt <= clock() ? 'expired' : order.status
    return {
      id: order.id, outTradeNo: order.outTradeNo, userId: order.userId,
      userPhone: user?.phone || '', userExists: Boolean(user), planId: order.planId,
      planName: order.planName, periodDays: order.periodDays, dailyQuota: order.dailyQuota ?? 0,
      channel: order.channel || 'free', amountFen: order.amountFen, status,
      createdAt: order.createdAt, expiresAt: order.expiresAt, paidAt: order.paidAt || null,
      channelTradeNo: order.channelTradeNo || '', appId: order.appId || '',
      settlementSource: order.settlementSource || (order.status === 'paid' ? order.channel || 'free' : ''),
      manualPaidBy: order.manualPaidBy || '', manualPaidAt: order.manualPaidAt || null,
      manualReason: order.manualReason || '', providerConfirmedAt: order.providerConfirmedAt || null,
      entitlementGrantedAt: order.entitlementGrantedAt || null, entitlementExpiresAt: order.entitlementExpiresAt || null,
      canComplete: Boolean(user && order.status !== 'paid')
    }
  }
  const findOrder = id => {
    const order = db.orders.find(item => item.id === id)
    if (!order) throw fail('订单不存在', 404)
    return order
  }

  app.get('/api/orders', requireAuth, wrap(async (req, res) => {
    const search = String(req.query.search || '').trim().toLowerCase().slice(0, 100)
    const status = String(req.query.status || '')
    const channel = String(req.query.channel || '')
    if (status && !ORDER_STATUSES.has(status)) throw fail('支付状态无效')
    if (channel && !CHANNELS.has(channel)) throw fail('支付平台无效')
    const page = Math.max(1, Number.parseInt(req.query.page, 10) || 1)
    const pageSize = Math.min(100, Math.max(1, Number.parseInt(req.query.pageSize, 10) || 20))
    const orders = db.orders.map(view)
      .filter(item => (!status || item.status === status) && (!channel || item.channel === channel) &&
        (!search || [item.outTradeNo, item.userPhone, item.planName, item.channelTradeNo]
          .some(value => String(value || '').toLowerCase().includes(search))))
      .sort((a, b) => b.createdAt - a.createdAt || b.id.localeCompare(a.id))
    const paid = orders.filter(item => item.status === 'paid')
    res.json({ orders: orders.slice((page - 1) * pageSize, page * pageSize), total: orders.length, page, pageSize,
      summary: { total: orders.length, paidCount: paid.length,
        paidAmountFen: paid.reduce((sum, item) => sum + item.amountFen, 0),
        pendingCount: orders.filter(item => item.status === 'created').length,
        manualCount: paid.filter(item => item.settlementSource === 'manual').length } })
  }))

  app.get('/api/orders/:id', requireAuth, wrap(async (req, res) => {
    const order = findOrder(req.params.id)
    res.json({ order: view(order), subscription: subscriptionOf(order) })
  }))

  app.post('/api/orders/:id/complete', requireAuth, wrap(async (req, res) => {
    const order = findOrder(req.params.id)
    const reason = String(req.body?.reason || '').trim() || '后台人工补单'
    if (reason.length > 200) throw fail('补单备注不能超过 200 字')
    const result = ledger.settleManually(order, { username: req.username, reason })
    if (!result.ok) throw fail(result.reason)
    res.json({ ok: true, alreadyPaid: Boolean(result.alreadyPaid), order: view(order), subscription: subscriptionOf(order) })
  }))
}
