import { useEffect, useState } from 'react'
import { Alert, App, Card, Statistic, Table, Tag } from 'antd'
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DollarOutlined,
  FundOutlined,
  RiseOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { api } from '../api'

interface CostStats {
  totalCalls: number
  totalCostFen: number
  vipIncomeFen: number
  vipPaidOrders: number
  profitFen: number
  byModel: ModelSummary[]
  byFeature: FeatureSummary[]
}

interface ModelSummary {
  modelId: string
  displayName: string
  calls: number
  costFen: number
  inputTokens: number
  outputTokens: number
}

interface FeatureSummary {
  feature: string
  calls: number
  costFen: number
}

interface UsageRecord {
  id: string
  displayName: string
  provider: string
  userPhone: string
  feature: string
  inputTokens: number
  outputTokens: number
  costFen: number
  createdAt: number
}

const yuan = (fen: number) => `¥${(fen / 100).toFixed(2)}`

const maskedPhone = (phone: string) =>
  phone.length === 11 ? `${phone.slice(0, 3)}****${phone.slice(7)}` : phone || '—'

const FEATURE_LABELS: Record<string, string> = {
  dictation: '语音听写',
  polish: '文本润色',
  reply: '帮你回',
  vision: '图片识别',
  ask: '问答'
}

export default function Costs() {
  const { message } = App.useApp()
  const [stats, setStats] = useState<CostStats | null>(null)
  const [usage, setUsage] = useState<UsageRecord[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    ;(async () => {
      try {
        const [statsData, usageData] = await Promise.all([
          api.get<CostStats>('/stats/costs'),
          api.get<{ usage: UsageRecord[] }>('/usage?limit=300')
        ])
        setStats(statsData)
        setUsage(usageData.usage)
      } catch (error) {
        message.error((error as Error).message)
      } finally {
        setLoading(false)
      }
    })()
  }, [message])

  const usageColumns: ColumnsType<UsageRecord> = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (time: number) => dayjs(time).format('YYYY-MM-DD HH:mm:ss')
    },
    {
      title: '用户',
      dataIndex: 'userPhone',
      width: 130,
      render: (phone: string) => maskedPhone(phone)
    },
    {
      title: '模型',
      dataIndex: 'displayName',
      render: (name: string, record) => (
        <div>
          <div>{name}</div>
          {record.provider && <div style={{ fontSize: 12, color: '#b09aa3' }}>{record.provider}</div>}
        </div>
      )
    },
    {
      title: '功能',
      dataIndex: 'feature',
      width: 110,
      render: (feature: string) => FEATURE_LABELS[feature] || feature || '—'
    },
    {
      title: '输入 tokens',
      dataIndex: 'inputTokens',
      width: 110,
      render: (tokens: number) => tokens.toLocaleString()
    },
    {
      title: '输出 tokens',
      dataIndex: 'outputTokens',
      width: 110,
      render: (tokens: number) => tokens.toLocaleString()
    },
    {
      title: '成本',
      dataIndex: 'costFen',
      width: 100,
      render: (fen: number) => yuan(fen)
    }
  ]

  const modelColumns: ColumnsType<ModelSummary> = [
    { title: '模型', dataIndex: 'displayName' },
    { title: '调用次数', dataIndex: 'calls', width: 100 },
    {
      title: 'tokens（入/出）',
      width: 170,
      render: (_, record) => `${record.inputTokens.toLocaleString()} / ${record.outputTokens.toLocaleString()}`
    },
    {
      title: '成本',
      dataIndex: 'costFen',
      width: 100,
      render: (fen: number) => yuan(fen)
    }
  ]

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16, borderRadius: 12 }}
        message="经营统计：VIP 收入 vs 模型成本"
        description="模型调用成本来自官方 AI 通道的每一次转发记账（AI 代理上线后自动记录）；VIP 收入取全部已支付订单。利润 = VIP 收入 − 模型成本。"
      />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 14, marginBottom: 16 }}>
        <Card variant="borderless" style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}>
          <Statistic
            title="VIP 总收入"
            value={(stats?.vipIncomeFen ?? 0) / 100}
            precision={2}
              prefix={<DollarOutlined style={{ color: '#ff6b9d' }} />}
            suffix="元"
            loading={loading}
          />
          <div style={{ fontSize: 12, color: '#b09aa3', marginTop: 4 }}>已支付订单 {stats?.vipPaidOrders ?? 0} 笔</div>
        </Card>
        <Card variant="borderless" style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}>
          <Statistic
            title="模型调用成本"
            value={(stats?.totalCostFen ?? 0) / 100}
            precision={2}
            prefix={<FundOutlined style={{ color: '#1677ff' }} />}
            suffix="元"
            loading={loading}
          />
          <div style={{ fontSize: 12, color: '#b09aa3', marginTop: 4 }}>
            共 {stats?.totalCalls ?? 0} 次调用
          </div>
        </Card>
        <Card variant="borderless" style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}>
          <Statistic
            title="利润"
            value={(stats?.profitFen ?? 0) / 100}
            precision={2}
            prefix={
              (stats?.profitFen ?? 0) >= 0 ? (
                <ArrowUpOutlined style={{ color: '#2fae74' }} />
              ) : (
                <ArrowDownOutlined style={{ color: '#ff4757' }} />
              )
            }
            valueStyle={{ color: (stats?.profitFen ?? 0) >= 0 ? '#2fae74' : '#ff4757' }}
            suffix="元"
            loading={loading}
          />
          <div style={{ fontSize: 12, color: '#b09aa3', marginTop: 4 }}>VIP 收入 − 模型成本</div>
        </Card>
        <Card variant="borderless" style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}>
          <Statistic
            title="调用次数"
            value={stats?.totalCalls ?? 0}
            prefix={<ThunderboltOutlined style={{ color: '#faad14' }} />}
            loading={loading}
          />
          <div style={{ fontSize: 12, color: '#b09aa3', marginTop: 4 }}>
            {stats?.byFeature?.map(item => (
              <Tag key={item.feature} style={{ marginTop: 2 }}>
                {FEATURE_LABELS[item.feature] || item.feature} ×{item.calls}
              </Tag>
            )) || '—'}
          </div>
        </Card>
      </div>

      <Card
        variant="borderless"
        title={<span><RiseOutlined style={{ color: '#ff6b9d', marginRight: 6 }} />按模型汇总</span>}
        style={{ borderRadius: 16, marginBottom: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 16 } }}
      >
        <Table<ModelSummary>
          rowKey="modelId"
          columns={modelColumns}
          dataSource={stats?.byModel || []}
          loading={loading}
          pagination={false}
          size="small"
          locale={{ emptyText: '暂无模型调用记录（AI 通道上线后自动记账）' }}
        />
      </Card>

      <Card
        variant="borderless"
        title="调用记录明细"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 16 } }}
      >
        <Table<UsageRecord>
          rowKey="id"
          columns={usageColumns}
          dataSource={usage}
          loading={loading}
          pagination={{ pageSize: 20, showTotal: total => `共 ${total} 条` }}
          size="small"
          locale={{ emptyText: '暂无调用记录 —— AI 代理接口上线后，每一次模型调用都会在这里记账' }}
        />
      </Card>
    </div>
  )
}
