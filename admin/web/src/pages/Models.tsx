import { useEffect, useState } from 'react'
import { Alert, App, Button, Card, Checkbox, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { api } from '../api'

interface ModelRecord {
  id: string
  provider: string
  model: string
  displayName: string
  baseUrl: string
  capabilities: string[]
  inputPriceFen: number
  outputPriceFen: number
  apiKeySaved: boolean
  enabled: boolean
  createdAt: number
  updatedAt: number
}

interface ModelForm {
  provider?: string
  model?: string
  displayName?: string
  baseUrl?: string
  apiKey?: string
  capabilities: string[]
  inputPriceYuan: number
  outputPriceYuan: number
  enabled: boolean
}

/** 服务商预设：地址与参考价自动带出（2026-10 按各官网定价页核对），价格以官网为准。 */
const PROVIDERS: Record<string, { name: string; baseUrl: string; models: ModelPreset[] }> = {
  deepseek: {
    name: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    models: [
      // 官方峰谷定价，此处按高峰价预填
      { model: 'deepseek-flash', displayName: 'DeepSeek V4.1 Flash', capabilities: ['text'], input: 2, output: 8 },
      { model: 'deepseek-v4-pro', displayName: 'DeepSeek V4 Pro', capabilities: ['text'], input: 9, output: 27 }
    ]
  },
  glm: {
    name: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    models: [
      { model: 'glm-5.3', displayName: 'GLM-5.3 旗舰', capabilities: ['text'], input: 8, output: 28 },
      { model: 'glm-5.3-flash', displayName: 'GLM-5.3-Flash（多模态）', capabilities: ['text', 'vision'], input: 0.8, output: 2.8 },
      { model: 'glm-5v-turbo', displayName: 'GLM-5V-Turbo（视觉）', capabilities: ['text', 'vision'], input: 5, output: 22 },
      { model: 'glm-4.6v', displayName: 'GLM-4.6V（视觉）', capabilities: ['text', 'vision'], input: 1, output: 3 },
      { model: 'glm-4.7-flash', displayName: 'GLM-4.7-Flash（免费）', capabilities: ['text'], input: 0, output: 0 }
    ]
  },
  kimi: {
    name: 'Kimi（月之暗面）',
    baseUrl: 'https://api.moonshot.cn/v1',
    models: [
      { model: 'kimi-k3', displayName: 'Kimi K3 旗舰（视觉）', capabilities: ['text', 'vision'], input: 20, output: 100 },
      { model: 'kimi-k2.6', displayName: 'Kimi K2.6（视觉）', capabilities: ['text', 'vision'], input: 6.5, output: 27 }
    ]
  },
  custom: { name: '自定义（OpenAI 兼容）', baseUrl: '', models: [] }
}

interface ModelPreset {
  model: string
  displayName: string
  capabilities: string[]
  input: number
  output: number
}

export default function Models() {
  const { message } = App.useApp()
  const [form] = Form.useForm<ModelForm>()
  const [models, setModels] = useState<ModelRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<ModelRecord | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      const data = await api.get<{ models: ModelRecord[] }>('/models')
      setModels(data.models)
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
    form.setFieldsValue({ provider: 'deepseek', baseUrl: PROVIDERS.deepseek.baseUrl, capabilities: ['text'], inputPriceYuan: 0, outputPriceYuan: 0, enabled: false })
    setModalOpen(true)
  }

  const openEdit = (record: ModelRecord) => {
    setEditing(record)
    form.setFieldsValue({
      provider: record.provider,
      model: record.model,
      displayName: record.displayName,
      baseUrl: record.baseUrl,
      capabilities: record.capabilities,
      inputPriceYuan: record.inputPriceFen / 100,
      outputPriceYuan: record.outputPriceFen / 100,
      enabled: record.enabled
    })
    setModalOpen(true)
  }

  const save = async (values: ModelForm) => {
    setSaving(true)
    try {
      const payload = {
        provider: values.provider,
        model: values.model,
        displayName: values.displayName,
        baseUrl: values.baseUrl,
        apiKey: values.apiKey || '',
        capabilities: values.capabilities,
        inputPriceFen: Math.round(values.inputPriceYuan * 100),
        outputPriceFen: Math.round(values.outputPriceYuan * 100),
        enabled: values.enabled
      }
      if (editing) {
        await api.put(`/models/${editing.id}`, payload)
        message.success('模型已更新')
      } else {
        await api.post('/models', payload)
        message.success('模型已添加')
      }
      setModalOpen(false)
      await load()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const remove = async (record: ModelRecord) => {
    try {
      await api.delete(`/models/${record.id}`)
      message.success(`已删除「${record.displayName}」`)
      await load()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const toggleEnabled = async (record: ModelRecord, enabled: boolean) => {
    try {
      await api.put(`/models/${record.id}`, {
        provider: record.provider,
        model: record.model,
        displayName: record.displayName,
        baseUrl: record.baseUrl,
        apiKey: '',
        capabilities: record.capabilities,
        inputPriceFen: record.inputPriceFen,
        outputPriceFen: record.outputPriceFen,
        enabled
      })
      setModels(prev => prev.map(item => (item.id === record.id ? { ...item, enabled } : item)))
      message.success(enabled ? `「${record.displayName}」已上架` : `「${record.displayName}」已下架`)
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const providerName = (key: string) => PROVIDERS[key]?.name || key

  // 表格按厂商分组：渲染前预计算每行的合并跨度（首行 = 数量，其余 = 0）
  const providerCount = new Map<string, number>()
  models.forEach(item => providerCount.set(item.provider, (providerCount.get(item.provider) || 0) + 1))
  const providerSpans = new Map<string, number>()
  let previousProvider = ''
  models.forEach(item => {
    providerSpans.set(item.id, item.provider === previousProvider ? 0 : providerCount.get(item.provider) || 1)
    previousProvider = item.provider
  })

  const columns: ColumnsType<ModelRecord> = [
    {
      title: '服务商',
      dataIndex: 'provider',
      width: 150,
      onCell: record => ({ rowSpan: providerSpans.get(record.id) ?? 1 }),
      render: (provider: string) => (
        <span style={{ fontWeight: 600 }}>
          {providerName(provider)}
          <span style={{ fontSize: 12, color: '#b09aa3', marginLeft: 4 }}>×{providerCount.get(provider) || 1}</span>
        </span>
      )
    },
    {
      title: '模型',
      dataIndex: 'displayName',
      render: (name: string, record) => (
        <div>
          <div style={{ fontWeight: 600 }}>{name}</div>
          <div style={{ fontSize: 12, color: '#b09aa3' }}>{record.model}</div>
        </div>
      )
    },
    {
      title: '能力',
      dataIndex: 'capabilities',
      width: 110,
      render: (caps: string[]) => (
        <>
          {caps.map(cap => (
            <Tag key={cap} color={cap === 'vision' ? 'purple' : 'blue'}>
              {cap === 'vision' ? '视觉' : '文本'}
            </Tag>
          ))}
        </>
      )
    },
    {
      title: '价格（元 / 百万tokens）',
      width: 170,
      render: (_, record) =>
        record.inputPriceFen === 0 && record.outputPriceFen === 0 ? (
          <Tag color="green">免费</Tag>
        ) : (
          <span>
            入 ¥{(record.inputPriceFen / 100).toFixed(2)} · 出 ¥{(record.outputPriceFen / 100).toFixed(2)}
          </span>
        )
    },
    {
      title: '密钥',
      dataIndex: 'apiKeySaved',
      width: 80,
      render: (saved: boolean) => (saved ? <Tag color="pink">已保存</Tag> : <Tag>未配置</Tag>)
    },
    {
      title: '上架',
      dataIndex: 'enabled',
      width: 70,
      render: (enabled: boolean, record) => (
        <Switch checked={enabled} size="small" onChange={value => toggleEnabled(record, value)} />
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
      render: (_, record) => (
        <span>
          <Button type="link" size="small" onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Popconfirm
            title={`删除「${record.displayName}」？`}
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => remove(record)}
          >
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </span>
      )
    }
  ]

  const selectedProvider = Form.useWatch('provider', form) || ''
  const presetList = PROVIDERS[selectedProvider]?.models || []
  const presetOptions = presetList.map(item => ({
    value: item.model,
    label: `${item.displayName}（${item.model}）`
  }))
  // 编辑已有记录但模型不在预设里（如自定义或已改名）时，保留下拉可选项
  if (editing && editing.model && !presetList.some(item => item.model === editing.model)) {
    presetOptions.push({ value: editing.model, label: `${editing.model}（当前）` })
  }

  const onProviderChange = (provider: string) => {
    form.setFieldsValue({
      baseUrl: PROVIDERS[provider]?.baseUrl || '',
      model: undefined
    })
  }

  const onModelSelected = (model: string) => {
    const preset = presetList.find(item => item.model === model)
    if (preset) {
      form.setFieldsValue({
        displayName: preset.displayName,
        capabilities: preset.capabilities,
        inputPriceYuan: preset.input,
        outputPriceYuan: preset.output
      })
    }
  }

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16, borderRadius: 12 }}
        message="官方 AI 通道的模型池"
        description="在此配置各服务商模型与密钥（密钥只保存在服务端），带「视觉」能力的模型将用于 App 端图片识别等功能；价格为参考展示，计费策略后续在此基础上下发。"
      />
      <Card
        variant="borderless"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 16 } }}
      >
        <div style={{ marginBottom: 14 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            添加模型
          </Button>
        </div>
        <Table<ModelRecord>
          rowKey="id"
          columns={columns}
          dataSource={models}
          loading={loading}
          pagination={false}
          locale={{ emptyText: '还没有模型，点击「添加模型」从 DeepSeek / GLM / Kimi 预设快速添加' }}
        />
      </Card>

      <Modal
        title={editing ? `编辑模型：${editing.displayName}` : '添加模型'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={save} style={{ marginTop: 8 }}>
          <Form.Item name="provider" label="服务商" rules={[{ required: true, message: '请选择服务商' }]}>
            <Select
              options={Object.entries(PROVIDERS).map(([value, item]) => ({ value, label: item.name }))}
              onChange={onProviderChange}
            />
          </Form.Item>
          {presetList.length > 0 ? (
            <Form.Item name="model" label="模型" rules={[{ required: true, message: '请选择模型' }]} extra="选择后自动带出展示名、参考价与能力">
              <Select options={presetOptions} onSelect={onModelSelected} placeholder="下拉选择模型" />
            </Form.Item>
          ) : (
            <Form.Item name="model" label="模型 ID" rules={[{ required: true, message: '请填写模型 ID' }]}>
              <Input placeholder="如 qwen3-max" maxLength={100} />
            </Form.Item>
          )}
          <Form.Item name="displayName" label="展示名" rules={[{ required: true, message: '请填写展示名' }]}>
            <Input placeholder="如 GLM-4.5V（视觉）" maxLength={60} />
          </Form.Item>
          <Form.Item name="baseUrl" label="接口地址（OpenAI 兼容）" rules={[{ required: true, message: '请填写接口地址' }]}>
            <Input placeholder="https://api.deepseek.com/v1" />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label="API Key"
            extra={editing?.apiKeySaved ? '已保存过密钥，留空表示不修改' : '仅保存在服务端，App 与前端均不可见'}
          >
            <Input.Password placeholder="sk-..." autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="capabilities" label="能力" rules={[{ required: true, message: '至少选择一种能力' }]}>
            <Checkbox.Group
              options={[
                { label: '文本', value: 'text' },
                { label: '视觉（图片识别）', value: 'vision' }
              ]}
            />
          </Form.Item>
          <Form.Item label="参考价格（元 / 百万 tokens）" required>
            <Space size={16} style={{ display: 'flex' }}>
              <Form.Item name="inputPriceYuan" noStyle rules={[{ required: true }]}>
                <InputNumber min={0} precision={2} style={{ width: '100%' }} prefix="输入 ¥" placeholder="输入价" />
              </Form.Item>
              <Form.Item name="outputPriceYuan" noStyle rules={[{ required: true }]}>
                <InputNumber min={0} precision={2} style={{ width: '100%' }} prefix="输出 ¥" placeholder="输出价" />
              </Form.Item>
            </Space>
          </Form.Item>
          <Form.Item name="enabled" label="上架状态" valuePropName="checked" extra="上架且已配置密钥的模型才会出现在 App 可用列表">
            <Switch checkedChildren="上架" unCheckedChildren="下架" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
