import { useEffect, useState } from 'react'
import { Alert, App, Button, Card, Form, Select, Switch, Tag } from 'antd'
import { api } from '../api'

interface BusinessConfig {
  visionReply: {
    enabled: boolean
    modelId: string
    modelDisplayName: string
    modelReady: boolean
  }
}

interface ModelOption {
  id: string
  displayName: string
  provider: string
  capabilities: string[]
  apiKeySaved: boolean
  enabled: boolean
}

export default function Business() {
  const { message } = App.useApp()
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const [models, setModels] = useState<ModelOption[]>([])
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    ;(async () => {
      try {
        const [config, modelData] = await Promise.all([
          api.get<BusinessConfig>('/business'),
          api.get<{ models: ModelOption[] }>('/models')
        ])
        setModels(modelData.models)
        form.setFieldsValue({
          visionReplyEnabled: config.visionReply.enabled,
          modelId: config.visionReply.modelId || undefined
        })
      } catch (error) {
        message.error((error as Error).message)
      } finally {
        setLoaded(true)
      }
    })()
  }, [form, message])

  const save = async (values: { visionReplyEnabled: boolean; modelId?: string }) => {
    setSaving(true)
    try {
      await api.put('/business', {
        visionReplyEnabled: values.visionReplyEnabled,
        modelId: values.modelId || ''
      })
      message.success('业务配置已保存')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  // 可选模型 = 已上架 + 已配密钥 + 具备视觉能力
  const visionModels = models.filter(
    item => item.enabled && item.apiKeySaved && item.capabilities.includes('vision')
  )

  return (
    <div style={{ maxWidth: 860 }}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16, borderRadius: 12 }}
        message="App 功能开关"
        description="控制恋爱键盘 App 里各 AI 功能的开放状态与所用模型。改动即时生效，App 端下次查询时自动同步。"
      />
      <Card
        variant="borderless"
        title="截图识别回复（帮你回）"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 24 } }}
      >
        <Form form={form} layout="vertical" onFinish={save}>
          <Form.Item
            name="visionReplyEnabled"
            label="功能开关"
            valuePropName="checked"
            extra="开启后，App「帮你回」页出现「截图识别回复」按钮；用户选聊天截图，后端调用下方模型生成 3 条场景化回复（恋爱教接话、商务促成交等）"
          >
            <Switch checkedChildren="已启用" unCheckedChildren="已关闭" />
          </Form.Item>
          <Form.Item
            name="modelId"
            label="识别模型"
            extra={
              visionModels.length === 0 && loaded
                ? '暂无可选模型：需要先在「模型配置」里上架一个带「视觉」标签且已填密钥的模型'
                : '仅列出已上架、已配置密钥且带「视觉」能力的模型'
            }
            rules={[{ required: false }]}
          >
            <Select
              placeholder="选择视觉模型"
              allowClear
              options={visionModels.map(item => ({
                value: item.id,
                label: `${item.displayName}（${item.provider}）`
              }))}
            />
          </Form.Item>
          {loaded && visionModels.length > 0 && (
            <div style={{ marginBottom: 16 }}>
              <Tag color="pink">每次调用自动记入「模型成本」</Tag>
              <Tag color="purple">按所选模型的官网价格计费</Tag>
            </div>
          )}
          <Button type="primary" htmlType="submit" loading={saving}>
            保存配置
          </Button>
        </Form>
      </Card>
    </div>
  )
}
