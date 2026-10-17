import { useEffect, useState } from 'react'
import { Alert, App, Card, Statistic, Table } from 'antd'
import {
  DotChartOutlined,
  DollarOutlined,
  FundOutlined,
  RiseOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserAddOutlined
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { api } from '../api'

interface DailyItem {
  date: string
  active: number
  newUsers: number
}

interface RecentActive {
  id: string
  phone: string
  lastActive: string
  activeDays30: number
}

interface Overview {
  totalUsers: number
  todayNew: number
  todayActive: number
  weekActive: number
  monthActive: number
  totalOrders: number
  paidOrders: number
  revenueFen: number
  totalCalls: number
  totalCostFen: number
  daily: DailyItem[]
  recentActive: RecentActive[]
}

const cardShadow = { borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }

/** 纯 CSS 的 30 天日活柱状图（活跃=粉色，新增=橙色细柱） */
function DauChart({ daily }: { daily: DailyItem[] }) {
  const maxActive = Math.max(1, ...daily.map(item => item.active))
  const chartHeight = 160
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 10, fontSize: 12.5, color: '#8c737d' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={{ width: 10, height: 10, borderRadius: 3, background: 'linear-gradient(180deg,#ff6b9d,#ff9d76)' }} />
          日活跃用户
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={{ width: 10, height: 10, borderRadius: 3, background: '#ffa940' }} />
          当日新增
        </span>
        <span style={{ marginLeft: 'auto' }}>近 30 天 · 峰值 {maxActive}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: chartHeight + 22 }}>
        {daily.map(item => {
          const activeH = Math.round((item.active / maxActive) * chartHeight)
          const newH = item.newUsers > 0 ? Math.max(4, Math.round((item.newUsers / maxActive) * chartHeight)) : 0
          return (
            <div
              key={item.date}
              title={`${item.date}｜活跃 ${item.active}｜新增 ${item.newUsers}`}
              style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%' }}
            >
              <div style={{ flex: 1, display: 'flex', alignItems: 'flex-end', gap: 1, width: '100%', justifyContent: 'center' }}>
                {newH > 0 && (
                  <div style={{ width: 5, height: newH, borderRadius: 2, background: '#ffa940' }} />
                )}
                <div
                  style={{
                    width: newH > 0 ? 10 : 14,
                    height: Math.max(item.active > 0 ? 4 : 0, activeH),
                    borderRadius: 3,
                    background:
                      item.active > 0 ? 'linear-gradient(180deg,#ff6b9d,#ff9d76)' : '#f3dfe6'
                  }}
                />
              </div>
              <div style={{ fontSize: 9.5, color: '#b09aa3', marginTop: 4, whiteSpace: 'nowrap' }}>
                {dayjs(item.date).format('M.D')}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default function Dau() {
  const { message } = App.useApp()
  const [overview, setOverview] = useState<Overview | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    ;(async () => {
      try {
        const data = await api.get<Overview>('/stats/overview')
        setOverview(data)
      } catch (error) {
        message.error((error as Error).message)
      } finally {
        setLoading(false)
      }
    })()
  }, [message])

  const recentColumns: ColumnsType<RecentActive> = [
    { title: '手机号', dataIndex: 'phone', width: 140 },
    { title: '最近活跃', dataIndex: 'lastActive', width: 130 },
    { title: '近 30 天活跃天数', dataIndex: 'activeDays30', width: 150 }
  ]

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16, borderRadius: 12 }}
        message="用户活跃与经营概览"
        description="日活口径：当天登录、使用 AI 功能或产生订单的注册用户；历史数据由调用与订单记录回溯，接入埋点后每天自动累计。"
      />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 14, marginBottom: 16 }}>
        <Card variant="borderless" style={cardShadow}>
          <Statistic
            title="总用户"
            value={overview?.totalUsers ?? 0}
            prefix={<TeamOutlined style={{ color: '#ff6b9d' }} />}
            loading={loading}
          />
        </Card>
        <Card variant="borderless" style={cardShadow}>
          <Statistic
            title="今日新增"
            value={overview?.todayNew ?? 0}
            prefix={<UserAddOutlined style={{ color: '#ffa940' }} />}
            loading={loading}
          />
        </Card>
        <Card variant="borderless" style={cardShadow}>
          <Statistic
            title="今日活跃"
            value={overview?.todayActive ?? 0}
            prefix={<DotChartOutlined style={{ color: '#1677ff' }} />}
            loading={loading}
          />
          <div style={{ fontSize: 12, color: '#b09aa3', marginTop: 4 }}>
            7 日 {overview?.weekActive ?? 0} · 30 日 {overview?.monthActive ?? 0}
          </div>
        </Card>
        <Card variant="borderless" style={cardShadow}>
          <Statistic
            title="累计收入"
            value={(overview?.revenueFen ?? 0) / 100}
            precision={2}
            prefix={<DollarOutlined style={{ color: '#2fae74' }} />}
            suffix="元"
            loading={loading}
          />
          <div style={{ fontSize: 12, color: '#b09aa3', marginTop: 4 }}>
            已支付 {overview?.paidOrders ?? 0} / {overview?.totalOrders ?? 0} 单
          </div>
        </Card>
        <Card variant="borderless" style={cardShadow}>
          <Statistic
            title="模型成本"
            value={(overview?.totalCostFen ?? 0) / 100}
            precision={2}
            prefix={<FundOutlined style={{ color: '#d4380d' }} />}
            suffix="元"
            loading={loading}
          />
          <div style={{ fontSize: 12, color: '#b09aa3', marginTop: 4 }}>共 {overview?.totalCalls ?? 0} 次调用</div>
        </Card>
      </div>

      <Card
        variant="borderless"
        title={<span><RiseOutlined style={{ color: '#ff6b9d', marginRight: 6 }} />日活趋势</span>}
        style={{ ...cardShadow, marginBottom: 16 }}
        styles={{ body: { padding: 20 } }}
      >
        <DauChart daily={overview?.daily || []} />
      </Card>

      <Card
        variant="borderless"
        title={<span><ThunderboltOutlined style={{ color: '#ff6b9d', marginRight: 6 }} />最近活跃用户</span>}
        style={cardShadow}
        styles={{ body: { padding: 16 } }}
      >
        <Table<RecentActive>
          rowKey="id"
          columns={recentColumns}
          dataSource={overview?.recentActive || []}
          loading={loading}
          pagination={false}
          size="small"
          locale={{ emptyText: '暂无活跃记录' }}
        />
      </Card>
    </div>
  )
}
