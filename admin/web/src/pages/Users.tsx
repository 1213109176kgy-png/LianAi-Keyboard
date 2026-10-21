import { useEffect, useState } from 'react'
import { Alert, App, Button, Card, Descriptions, Drawer, Table, Tag } from 'antd'
import { EyeOutlined, TeamOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { api } from '../api'

interface Subscription {
  planName: string
  dailyQuota: number
  expiresAt: number
}

interface UserRow {
  id: string
  phone: string
  createdAt: number
  lastLoginAt: number
  tokenCount: number
  subscription: Subscription | null
  orderCount: number
  paidCount: number
  spentFen: number
  usageCount: number
  usageCostFen: number
  activeDays: number
  lastActiveAt: string | null
}

interface OrderRow {
  id: string
  outTradeNo: string
  planName: string
  periodDays: number
  amountFen: number
  channel: string
  status: string
  createdAt: number
}

interface UsageRow {
  id: string
  displayName: string
  feature: string
  inputTokens: number
  outputTokens: number
  billingUnit?: 'tokens' | 'audio_hour'
  audioSeconds?: number
  costFen: number
  createdAt: number
}

interface UserDetail {
  user: {
    id: string
    phone: string
    createdAt: number
    lastLoginAt: number
    tokenCount: number
  }
  subscription: Subscription | null
  orders: OrderRow[]
  usage: UsageRow[]
  activityDays: string[]
  stats: {
    orderCount: number
    paidCount: number
    spentFen: number
    usageCount: number
    usageCostFen: number
    activeDays: number
    lastActiveAt: string | null
  }
}

const yuan = (fen: number) => `¥${(fen / 100).toFixed(2)}`

const FEATURE_LABELS: Record<string, string> = {
  dictation: '语音听写',
  polish: '文本润色',
  reply: '帮你回',
  vision: '截图识别',
  ask: '问答'
}

const ORDER_STATUS: Record<string, { color: string; text: string }> = {
  created: { color: 'default', text: '待支付' },
  paid: { color: 'green', text: '已支付' },
  expired: { color: 'orange', text: '已超时' },
  closed: { color: 'default', text: '已关闭' }
}

export default function Users() {
  const { message } = App.useApp()
  const [users, setUsers] = useState<UserRow[]>([])
  const [loading, setLoading] = useState(true)
  const [detail, setDetail] = useState<UserDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [search, setSearch] = useState('')

  useEffect(() => {
    ;(async () => {
      try {
        const data = await api.get<{ users: UserRow[] }>('/users')
        setUsers(data.users)
      } catch (error) {
        message.error((error as Error).message)
      } finally {
        setLoading(false)
      }
    })()
  }, [message])

  const openDetail = async (id: string) => {
    setDetailLoading(true)
    setDetail(null)
    try {
      const data = await api.get<UserDetail>(`/users/${id}`)
      setDetail(data)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setDetailLoading(false)
    }
  }

  const filtered = users.filter(user => user.phone.includes(search.trim()))

  const columns: ColumnsType<UserRow> = [
    {
      title: '手机号',
      dataIndex: 'phone',
      width: 130,
      render: (phone: string) => <span style={{ fontWeight: 600 }}>{phone}</span>
    },
    {
      title: '订阅',
      dataIndex: 'subscription',
      width: 150,
      render: (sub: Subscription | null) =>
        sub ? (
          <div>
            <Tag color="magenta">{sub.planName}</Tag>
            <div style={{ fontSize: 12, color: '#b09aa3' }}>
              {sub.expiresAt > Date.now() ? dayjs(sub.expiresAt).format('YYYY-MM-DD 到期') : '已过期'}
            </div>
          </div>
        ) : (
          <Tag>免费用户</Tag>
        )
    },
    {
      title: '注册时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (time: number) => dayjs(time).format('YYYY-MM-DD HH:mm')
    },
    {
      title: '最近登录',
      dataIndex: 'lastLoginAt',
      width: 150,
      render: (time: number) => dayjs(time).format('YYYY-MM-DD HH:mm')
    },
    {
      title: '最近活跃',
      dataIndex: 'lastActiveAt',
      width: 110,
      render: (d: string | null) => d || '—'
    },
    {
      title: '累计消费',
      dataIndex: 'spentFen',
      width: 100,
      render: (fen: number) => (fen > 0 ? <span style={{ color: '#d4380d', fontWeight: 600 }}>{yuan(fen)}</span> : '—')
    },
    {
      title: '订单',
      width: 80,
      render: (_, record) => `${record.paidCount}/${record.orderCount}`
    },
    {
      title: 'AI 调用',
      dataIndex: 'usageCount',
      width: 90,
      render: (count: number, record) => (
        <span>
          {count} 次
          {record.usageCostFen > 0 && (
            <span style={{ fontSize: 12, color: '#b09aa3' }}>（{yuan(record.usageCostFen)}）</span>
          )}
        </span>
      )
    },
    {
      title: '操作',
      width: 90,
      render: (_, record) => (
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => openDetail(record.id)}>
          详情
        </Button>
      )
    }
  ]

  const orderColumns: ColumnsType<OrderRow> = [
    { title: '订单号', dataIndex: 'outTradeNo', width: 200, ellipsis: true },
    { title: '套餐', dataIndex: 'planName' },
    { title: '金额', dataIndex: 'amountFen', width: 90, render: (fen: number) => yuan(fen) },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (status: string) => {
        const meta = ORDER_STATUS[status]
        return <Tag color={meta?.color || 'default'}>{meta?.text || status}</Tag>
      }
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (time: number) => dayjs(time).format('YYYY-MM-DD HH:mm')
    }
  ]

  const usageColumns: ColumnsType<UsageRow> = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (time: number) => dayjs(time).format('YYYY-MM-DD HH:mm')
    },
    { title: '模型', dataIndex: 'displayName' },
    {
      title: '功能',
      dataIndex: 'feature',
      width: 100,
      render: (feature: string) => FEATURE_LABELS[feature] || feature
    },
    {
      title: '用量',
      width: 150,
      render: (_, record) => record.billingUnit === 'audio_hour'
        ? `${Number((record.audioSeconds ?? 0).toFixed(3))} 秒音频`
        : `${record.inputTokens} / ${record.outputTokens} tokens`
    },
    { title: '成本', dataIndex: 'costFen', width: 110, render: (fen: number) => `¥${(fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 6 })}` }
  ]

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16, borderRadius: 12 }}
        message="App 注册用户一览"
        description="展示所有 App 端注册用户：订阅、订单、AI 调用与活跃情况；点击详情查看完整记录。"
      />
      <Card
        variant="borderless"
        title={<span><TeamOutlined style={{ color: '#ff6b9d', marginRight: 6 }} />用户列表（{users.length}）</span>}
        extra={
          <input
            placeholder="搜索手机号"
            value={search}
            onChange={event => setSearch(event.target.value)}
            style={{
              border: '1px solid #f3d9e2',
              borderRadius: 10,
              padding: '5px 12px',
              outline: 'none',
              width: 180
            }}
          />
        }
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 16 } }}
      >
        <Table<UserRow>
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ pageSize: 20, showTotal: total => `共 ${total} 位用户` }}
          size="small"
          locale={{ emptyText: '还没有注册用户 —— App 端注册后会出现在这里' }}
        />
      </Card>

      <Drawer
        title={detail ? `用户 ${detail.user.phone}` : '用户详情'}
        width={720}
        open={!!detail || detailLoading}
        onClose={() => setDetail(null)}
        loading={detailLoading}
      >
        {detail && (
          <div>
            <Descriptions
              bordered
              size="small"
              column={2}
              style={{ marginBottom: 20 }}
              items={[
                { key: 'phone', label: '手机号', children: detail.user.phone },
                {
                  key: 'sub',
                  label: '当前订阅',
                  children: detail.subscription ? (
                    <span>
                      {detail.subscription.planName} ·{' '}
                      {detail.subscription.expiresAt > Date.now()
                        ? dayjs(detail.subscription.expiresAt).format('YYYY-MM-DD 到期')
                        : '已过期'}
                    </span>
                  ) : (
                    '免费用户'
                  )
                },
                {
                  key: 'created',
                  label: '注册时间',
                  children: dayjs(detail.user.createdAt).format('YYYY-MM-DD HH:mm:ss')
                },
                {
                  key: 'login',
                  label: '最近登录',
                  children: dayjs(detail.user.lastLoginAt).format('YYYY-MM-DD HH:mm:ss')
                },
                { key: 'tokens', label: '有效登录态', children: `${detail.user.tokenCount} 个设备` },
                { key: 'active', label: '累计活跃天数', children: `${detail.stats.activeDays} 天` },
                {
                  key: 'spent',
                  label: '累计消费',
                  children: detail.stats.spentFen > 0 ? yuan(detail.stats.spentFen) : '—'
                },
                {
                  key: 'usage',
                  label: 'AI 调用',
                  children: `${detail.stats.usageCount} 次（成本 ${yuan(detail.stats.usageCostFen)}）`
                }
              ]}
            />
            {detail.activityDays.length > 0 && (
              <div style={{ marginBottom: 20 }}>
                <div style={{ fontWeight: 600, marginBottom: 8 }}>活跃日期</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {detail.activityDays.slice(-30).map(d => (
                    <Tag key={d} color="pink">
                      {d}
                    </Tag>
                  ))}
                </div>
              </div>
            )}
            <Card
              size="small"
              title={`订单记录（${detail.orders.length}）`}
              style={{ marginBottom: 16, borderRadius: 12 }}
              styles={{ body: { padding: 12 } }}
            >
              <Table<OrderRow>
                rowKey="id"
                columns={orderColumns}
                dataSource={detail.orders}
                pagination={false}
                size="small"
                locale={{ emptyText: '暂无订单' }}
              />
            </Card>
            <Card
              size="small"
              title={`AI 调用记录（最近 ${detail.usage.length} 条）`}
              style={{ borderRadius: 12 }}
              styles={{ body: { padding: 12 } }}
            >
              <Table<UsageRow>
                rowKey="id"
                columns={usageColumns}
                dataSource={detail.usage}
                pagination={{ pageSize: 8 }}
                size="small"
                locale={{ emptyText: '暂无调用记录' }}
              />
            </Card>
          </div>
        )}
      </Drawer>
    </div>
  )
}
