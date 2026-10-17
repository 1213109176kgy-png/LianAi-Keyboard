import { useEffect, useState } from 'react'
import { Alert, App, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Switch, Table, Tag } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { api } from '../api'

interface Plan {
  id: string
  name: string
  description: string
  periodDays: number
  priceFen: number
  dailyQuota: number
  enabled: boolean
  sortOrder: number
  createdAt: number
  updatedAt: number
}

interface PlanForm {
  name: string
  description?: string
  periodDays: number
  priceYuan: number
  dailyQuota: number
  sortOrder: number
  enabled: boolean
}

export default function Plans() {
  const { message } = App.useApp()
  const [form] = Form.useForm<PlanForm>()
  const [plans, setPlans] = useState<Plan[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<Plan | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [togglingId, setTogglingId] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const data = await api.get<{ plans: Plan[] }>('/plans')
      setPlans(data.plans)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ periodDays: 30, priceYuan: 0, dailyQuota: 0, sortOrder: 0, enabled: true })
    setModalOpen(true)
  }

  const openEdit = (plan: Plan) => {
    setEditing(plan)
    form.setFieldsValue({
      name: plan.name,
      description: plan.description,
      periodDays: plan.periodDays,
      priceYuan: plan.priceFen / 100,
      dailyQuota: plan.dailyQuota,
      sortOrder: plan.sortOrder,
      enabled: plan.enabled
    })
    setModalOpen(true)
  }

  const save = async (values: PlanForm) => {
    setSaving(true)
    try {
      const payload = {
        name: values.name,
        description: values.description || '',
        periodDays: values.periodDays,
        priceFen: Math.round(values.priceYuan * 100),
        dailyQuota: values.dailyQuota,
        sortOrder: values.sortOrder,
        enabled: values.enabled
      }
      if (editing) {
        await api.put(`/plans/${editing.id}`, payload)
        message.success('套餐已更新')
      } else {
        await api.post('/plans', payload)
        message.success('套餐已创建')
      }
      setModalOpen(false)
      await load()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const remove = async (plan: Plan) => {
    try {
      await api.delete(`/plans/${plan.id}`)
      message.success(`已删除「${plan.name}」`)
      await load()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const toggleEnabled = async (plan: Plan, enabled: boolean) => {
    setTogglingId(plan.id)
    try {
      await api.put(`/plans/${plan.id}`, {
        name: plan.name,
        description: plan.description,
        periodDays: plan.periodDays,
        priceFen: plan.priceFen,
        dailyQuota: plan.dailyQuota,
        sortOrder: plan.sortOrder,
        enabled
      })
      setPlans(prev => prev.map(item => (item.id === plan.id ? { ...item, enabled } : item)))
      message.success(enabled ? `「${plan.name}」已上架` : `「${plan.name}」已下架`)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setTogglingId(null)
    }
  }

  const columns: ColumnsType<Plan> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 150,
      render: (name: string, plan) => (
        <div>
          <div style={{ fontWeight: 600 }}>{name}</div>
          {plan.description && (
            <div style={{ fontSize: 12, color: '#b09aa3' }}>{plan.description}</div>
          )}
        </div>
      )
    },
    {
      title: '有效期',
      dataIndex: 'periodDays',
      width: 90,
      render: (days: number) => `${days} 天`
    },
    {
      title: '价格',
      dataIndex: 'priceFen',
      width: 100,
      render: (fen: number) =>
        fen === 0 ? <Tag color="green">免费</Tag> : `¥${(fen / 100).toFixed(2)}`
    },
    {
      title: '每日 AI 额度',
      dataIndex: 'dailyQuota',
      width: 110,
      render: (quota: number) => (quota === 0 ? '不限' : `${quota} 次/天`)
    },
    {
      title: '排序',
      dataIndex: 'sortOrder',
      width: 70
    },
    {
      title: '上架',
      dataIndex: 'enabled',
      width: 80,
      render: (enabled: boolean, plan) => (
        <Switch
          checked={enabled}
          size="small"
          loading={togglingId === plan.id}
          onChange={value => toggleEnabled(plan, value)}
        />
      )
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 150,
      render: (time: number) => dayjs(time).format('YYYY-MM-DD HH:mm')
    },
    {
      title: '操作',
      key: 'actions',
      width: 130,
      render: (_, plan) => (
        <span>
          <Button type="link" size="small" onClick={() => openEdit(plan)}>
            编辑
          </Button>
          <Popconfirm
            title={`删除「${plan.name}」？`}
            description="已付费用户的权益不受影响，仅 App 端不再展示该套餐。"
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => remove(plan)}
          >
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </span>
      )
    }
  ]

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16, borderRadius: 12 }}
        message="套餐即订阅商品"
        description="上架的套餐会通过公开接口 /api/app/plans 提供给 App 订阅页，按「排序」升序展示；每日 AI 额度填 0 表示不限量。"
      />
      <Card
        variant="borderless"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 16 } }}
      >
        <div style={{ marginBottom: 14 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建套餐
          </Button>
        </div>
        <Table<Plan>
          rowKey="id"
          columns={columns}
          dataSource={plans}
          loading={loading}
          pagination={false}
          locale={{ emptyText: '还没有套餐，点击「新建套餐」创建第一个订阅商品' }}
        />
      </Card>
      <Modal
        title={editing ? `编辑套餐：${editing.name}` : '新建套餐'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={save} style={{ marginTop: 8 }}>
          <Form.Item
            name="name"
            label="套餐名称"
            rules={[{ required: true, message: '请输入套餐名称' }, { max: 30, message: '不超过 30 字' }]}
          >
            <Input placeholder="如 月度会员 / 年费会员" maxLength={30} showCount />
          </Form.Item>
          <Form.Item name="description" label="描述 / 权益说明" rules={[{ max: 200, message: '不超过 200 字' }]}>
            <Input.TextArea
              placeholder="如 官方 AI 通道不限量 · 全功能，App 订阅页会展示给用户"
              maxLength={200}
              showCount
              rows={2}
            />
          </Form.Item>
          <Form.Item
            name="periodDays"
            label="有效期（天）"
            rules={[{ required: true, message: '请输入有效期' }]}
            extra="购买后订阅时长，如月度 30 天、年费 365 天"
          >
            <InputNumber min={1} max={3650} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="priceYuan"
            label="价格（元）"
            rules={[{ required: true, message: '请输入价格' }]}
            extra="免费体验套餐填 0"
          >
            <InputNumber min={0} max={1000000} precision={2} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="dailyQuota"
            label="每日 AI 额度（次）"
            rules={[{ required: true, message: '请输入每日额度' }]}
            extra="官方 AI 通道每天的调用上限，0 表示不限量"
          >
            <InputNumber min={0} max={1000000} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序" extra="数字越小越靠前，App 订阅页按此顺序展示">
            <InputNumber precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="enabled" label="上架状态" valuePropName="checked">
            <Switch checkedChildren="上架" unCheckedChildren="下架" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
