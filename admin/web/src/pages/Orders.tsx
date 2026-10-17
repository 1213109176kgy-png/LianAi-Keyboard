import { useCallback, useEffect, useRef, useState } from 'react'
import { App, Button, Card, Col, Descriptions, Drawer, Input, Modal, Row, Select, Space, Statistic, Table, Tag, Typography } from 'antd'
import { EyeOutlined, ReloadOutlined, ShoppingOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { api } from '../api'

interface Order {
  id: string
  outTradeNo: string
  userId: string
  userPhone: string
  userExists: boolean
  planName: string
  periodDays: number
  dailyQuota: number
  channel: string
  amountFen: number
  status: string
  createdAt: number
  expiresAt: number
  paidAt: number | null
  channelTradeNo: string
  appId: string
  settlementSource: string
  manualPaidBy: string
  manualPaidAt: number | null
  manualReason: string
  providerConfirmedAt: number | null
  entitlementExpiresAt: number | null
  canComplete: boolean
}
interface Subscription {
  planName: string
  dailyQuota: number
  expiresAt: number
  active: boolean
}
interface Detail { order: Order; subscription: Subscription | null }
interface Summary { total: number; paidCount: number; paidAmountFen: number; pendingCount: number; manualCount: number }
interface OrderList { orders: Order[]; total: number; summary: Summary }
const emptySummary: Summary = { total: 0, paidCount: 0, paidAmountFen: 0, pendingCount: 0, manualCount: 0 }
const platforms: Record<string, string> = { alipay: '支付宝', wechat: '微信支付', aggregator: '聚合支付', free: '免费领取' }
const statuses: Record<string, { text: string; color: string }> = {
  created: { text: '待支付', color: 'blue' }, paid: { text: '已支付', color: 'green' },
  expired: { text: '已超时', color: 'orange' }, closed: { text: '已关闭', color: 'default' }
}
const money = (fen: number) => `¥${(fen / 100).toFixed(2)}`
const date = (value: number | null | undefined) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'
const quota = (value: number) => value === 0 ? '不限量' : `每日 ${value} 次`
const statusTag = (status: string) => <Tag color={statuses[status]?.color}>{statuses[status]?.text || status}</Tag>
const sourceTag = (order: Order) => order.status !== 'paid' ? '—' :
  order.settlementSource === 'manual' ? <Tag color="orange">人工补单</Tag> :
    order.channel === 'free' ? <Tag>免费领取</Tag> : <Tag color="green">平台确认</Tag>

export default function Orders() {
  const { message } = App.useApp()
  const [data, setData] = useState<OrderList>({ orders: [], total: 0, summary: emptySummary })
  const [loading, setLoading] = useState(false)
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<string>()
  const [channel, setChannel] = useState<string>()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [completing, setCompleting] = useState<Order | null>(null)
  const [saving, setSaving] = useState(false)
  const [reason, setReason] = useState('')
  const listRequest = useRef(0)
  const detailRequest = useRef(0)

  const load = useCallback(async () => {
    const request = ++listRequest.current
    setLoading(true)
    const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
    if (search) query.set('search', search)
    if (status) query.set('status', status)
    if (channel) query.set('channel', channel)
    try {
      const result = await api.get<OrderList>(`/orders?${query}`)
      if (request === listRequest.current) setData(result)
    } catch (error) {
      if (request === listRequest.current) message.error((error as Error).message)
    } finally {
      if (request === listRequest.current) setLoading(false)
    }
  }, [page, pageSize, search, status, channel, message])
  useEffect(() => { void load() }, [load])

  const openDetail = async (id: string) => {
    const request = ++detailRequest.current
    setDetailId(id); setDetail(null); setDetailLoading(true)
    try {
      const result = await api.get<Detail>(`/orders/${encodeURIComponent(id)}`)
      if (request === detailRequest.current) setDetail(result)
    } catch (error) {
      if (request === detailRequest.current) message.error((error as Error).message)
    } finally {
      if (request === detailRequest.current) setDetailLoading(false)
    }
  }
  const prepareCompletion = (order: Order) => { setCompleting(order); setReason('') }
  const complete = async () => {
    if (!completing || saving) return
    setSaving(true)
    try {
      const result = await api.post<Detail & { alreadyPaid: boolean }>(`/orders/${encodeURIComponent(completing.id)}/complete`, { reason })
      message.success(result.alreadyPaid ? '订单已支付，未重复发放权益' : '补单成功，套餐有效期与额度已发放')
      setCompleting(null)
      if (detailId === completing.id) setDetail(result)
      await load()
    } catch (error) {
      message.error((error as Error).message)
    } finally { setSaving(false) }
  }

  const columns: ColumnsType<Order> = [
    { title: '订单号', dataIndex: 'outTradeNo', width: 250,
      render: (value: string) => <Typography.Text copyable ellipsis={{ tooltip: value }} style={{ maxWidth: 225 }}>{value}</Typography.Text> },
    { title: '用户手机号', dataIndex: 'userPhone', width: 135, render: (phone: string) => phone || '用户已不存在' },
    { title: '套餐', dataIndex: 'planName', width: 130 },
    { title: '支付平台', dataIndex: 'channel', width: 100, render: (value: string) => platforms[value] || value },
    { title: '金额', dataIndex: 'amountFen', width: 95, render: money },
    { title: '支付状态', dataIndex: 'status', width: 100, render: statusTag },
    { title: '入账方式', width: 110, render: (_, order) => sourceTag(order) },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: date },
    { title: '支付时间', dataIndex: 'paidAt', width: 170, render: date },
    { title: '操作', width: 165, fixed: 'right', render: (_, order) => <Space size={4}>
      <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => void openDetail(order.id)}>详情</Button>
      {order.status !== 'paid' && <Button type="link" size="small" disabled={!order.canComplete} onClick={() => prepareCompletion(order)}>补单</Button>}
    </Space> }
  ]
  const summary = data.summary
  const orderDescriptions = (order: Order) => [
    { key: 'id', label: '订单号', children: <Typography.Text copyable>{order.outTradeNo}</Typography.Text>, span: 2 },
    { key: 'user', label: '用户手机号', children: order.userPhone || '用户已不存在' },
    { key: 'plan', label: '购买套餐', children: order.planName },
    { key: 'amount', label: '金额', children: money(order.amountFen) },
    { key: 'channel', label: '支付平台', children: platforms[order.channel] || order.channel },
    { key: 'status', label: '支付状态', children: statusTag(order.status) },
    { key: 'source', label: '入账方式', children: sourceTag(order) },
    { key: 'days', label: '购买有效期', children: `${order.periodDays} 天` },
    { key: 'quota', label: '套餐额度', children: quota(order.dailyQuota) },
    { key: 'created', label: '创建时间', children: date(order.createdAt) },
    { key: 'paid', label: '支付时间', children: date(order.paidAt) },
    { key: 'expires', label: '付款截止', children: date(order.expiresAt) },
    { key: 'entitlement', label: '本次入账后到期', children: date(order.entitlementExpiresAt) },
    { key: 'trade', label: '平台交易号', children: order.channelTradeNo || '—', span: 2 },
    ...(order.settlementSource === 'manual' ? [
      { key: 'operator', label: '补单操作人', children: order.manualPaidBy },
      { key: 'manualAt', label: '补单时间', children: date(order.manualPaidAt) },
      { key: 'reason', label: '补单备注', children: order.manualReason, span: 2 },
      { key: 'providerAt', label: '后续平台确认', children: date(order.providerConfirmedAt), span: 2 }
    ] : [])
  ]

  return <div>
    <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
      {[['订单数', summary.total], ['已支付', summary.paidCount], ['已入账金额', money(summary.paidAmountFen)], ['人工补单', summary.manualCount]].map(([title, value]) =>
        <Col xs={12} xl={6} key={title}><Card variant="borderless" style={{ borderRadius: 12 }}><Statistic title={title} value={value} /></Card></Col>)}
    </Row>
    <Card variant="borderless" title={<span><ShoppingOutlined style={{ color: '#ff6b9d', marginRight: 6 }} />订单列表</span>}
      extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>}
      style={{ borderRadius: 16 }} styles={{ body: { padding: 16 } }}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search allowClear placeholder="订单号 / 手机号 / 套餐 / 平台交易号" style={{ width: 330 }} onSearch={value => { setSearch(value.trim()); setPage(1) }} />
        <Select allowClear placeholder="全部支付状态" style={{ width: 150 }} value={status}
          options={Object.entries(statuses).map(([value, meta]) => ({ value, label: meta.text }))}
          onChange={value => { setStatus(value); setPage(1) }} />
        <Select allowClear placeholder="全部支付平台" style={{ width: 150 }} value={channel}
          options={Object.entries(platforms).map(([value, label]) => ({ value, label }))}
          onChange={value => { setChannel(value); setPage(1) }} />
      </Space>
      <Table<Order> rowKey="id" columns={columns} dataSource={data.orders} loading={loading} size="small" scroll={{ x: 1525 }}
        pagination={{ current: page, pageSize, total: data.total, showSizeChanger: true, showTotal: total => `共 ${total} 笔订单`,
          onChange: (next, size) => { setPage(next); setPageSize(size) } }} locale={{ emptyText: '暂无符合条件的订单' }} />
    </Card>
    <Drawer title="订单详情" width={760} open={detailId !== null} loading={detailLoading}
      onClose={() => { detailRequest.current++; setDetailId(null); setDetail(null) }}>
      {detail && <>
        <Descriptions bordered column={2} size="small" items={orderDescriptions(detail.order)} />
        {detail.subscription && <Card size="small" title="用户当前权益" style={{ marginTop: 20 }}>
          <Space direction="vertical">
            <span>{detail.subscription.planName} · {detail.subscription.active ? '生效中' : '已到期'}</span>
            <span>{quota(detail.subscription.dailyQuota)} · 到期 {date(detail.subscription.expiresAt)}</span>
          </Space>
        </Card>}
        {detail.order.canComplete && <Button type="primary" style={{ marginTop: 20 }} onClick={() => prepareCompletion(detail.order)}>手动补单并发放权益</Button>}
      </>}
    </Drawer>
    <Modal title="手动补单" open={completing !== null} confirmLoading={saving} okText="确认补单并发放权益" cancelText="取消"
      closable={!saving} maskClosable={!saving} keyboard={!saving} onCancel={() => { if (!saving) setCompleting(null) }} onOk={() => void complete()}>
      {completing && <>
        <Descriptions column={1} size="small" items={[
          { key: 'id', label: '订单号', children: completing.outTradeNo },
          { key: 'phone', label: '用户', children: completing.userPhone },
          { key: 'plan', label: '套餐', children: `${completing.planName} · ${money(completing.amountFen)}` },
          { key: 'rights', label: '发放权益', children: `${completing.periodDays} 天 · ${quota(completing.dailyQuota)}` }
        ]} />
        <p style={{ color: '#786b72' }}>确认后订单改为已支付，并为该用户发放上述套餐权益；已有有效期会顺延。</p>
        <Input.TextArea rows={3} maxLength={200} showCount placeholder="补单备注（选填，如已核实到账）" value={reason} onChange={event => setReason(event.target.value)} />
      </>}
    </Modal>
  </div>
}
