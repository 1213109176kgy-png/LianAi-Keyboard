const DAY_MS = 86400000

export function userVipInfo(db, userId, now = Date.now()) {
  const item = (db.subscriptions || []).find(entry => entry.userId === userId)
  const expiresAt = item?.expiresAt || 0
  const active = Boolean(userId && expiresAt > now)
  return { active, planId: item?.planId || '', planName: item?.planName || '',
    dailyQuota: item?.dailyQuota ?? 0, expiresAt,
    daysLeft: active ? Math.ceil((expiresAt - now) / DAY_MS) : 0 }
}

export const requireVip = db => (req, res, next) => {
  if (!userVipInfo(db, req.appUser?.id).active) {
    return res.status(403).json({ code: 'VIP_REQUIRED', message: '请开通 VIP，或在 App「模型配置」中配置自己的模型参数' })
  }
  next()
}

export function configUser(db, user) {
  if (!user) return null
  return { id: user.id, phone: user.phone || '', createdAt: user.createdAt || 0,
    lastLoginAt: user.lastLoginAt || 0, vip: userVipInfo(db, user.id) }
}
