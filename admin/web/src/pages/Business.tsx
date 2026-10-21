import { useEffect, useRef, useState } from 'react'
import { Alert, App, Card, Col, Form, Row, Select, Switch, Tag } from 'antd'
import { api } from '../api'

interface FeatureConfig {
  enabled: boolean
  modelId: string
  modelDisplayName: string
  modelReady: boolean
}
interface BusinessConfig {
  visionReply: FeatureConfig
  speechRecognition: FeatureConfig
  replySuggestions: FeatureConfig
  superSpeak: FeatureConfig
}
interface BusinessForm {
  visionReplyEnabled: boolean
  modelId?: string
  speechRecognitionEnabled: boolean
  speechModelId?: string
  replyEnabled: boolean
  replyModelId?: string
  polishEnabled: boolean
  polishModelId?: string
}
interface ModelOption {
  id: string
  displayName: string
  provider: string
  capabilities: string[]
  apiKeySaved: boolean
  enabled: boolean
}

const FEATURE_FIELDS = {
  visionReply: { enabled: 'visionReplyEnabled', model: 'modelId' },
  speechRecognition: { enabled: 'speechRecognitionEnabled', model: 'speechModelId' },
  replySuggestions: { enabled: 'replyEnabled', model: 'replyModelId' },
  superSpeak: { enabled: 'polishEnabled', model: 'polishModelId' }
} as const

export default function Business() {
  const { message } = App.useApp()
  const [form] = Form.useForm<BusinessForm>()
  const [saving, setSaving] = useState(false)
  const saveInFlight = useRef(false)
  const savedValues = useRef<BusinessForm | null>(null)
  const [models, setModels] = useState<ModelOption[]>([])
  const [loaded, setLoaded] = useState(false)
  const visionEnabled = Form.useWatch('visionReplyEnabled', form)
  const speechEnabled = Form.useWatch('speechRecognitionEnabled', form)
  const replyEnabled = Form.useWatch('replyEnabled', form)
  const polishEnabled = Form.useWatch('polishEnabled', form)

  useEffect(() => {
    ;(async () => {
      try {
        const [config, modelData] = await Promise.all([
          api.get<BusinessConfig>('/business'),
          api.get<{ models: ModelOption[] }>('/models')
        ])
        setModels(modelData.models)
        const values: BusinessForm = {
          visionReplyEnabled: config.visionReply.enabled,
          modelId: config.visionReply.modelId || undefined,
          speechRecognitionEnabled: config.speechRecognition?.enabled || false,
          speechModelId: config.speechRecognition?.modelId || undefined,
          replyEnabled: config.replySuggestions?.enabled || false,
          replyModelId: config.replySuggestions?.modelId || undefined,
          polishEnabled: config.superSpeak?.enabled || false,
          polishModelId: config.superSpeak?.modelId || undefined
        }
        savedValues.current = values
        form.setFieldsValue(values)
        setLoaded(true)
      } catch (error) {
        message.error((error as Error).message)
      }
    })()
  }, [form, message])

  const autoSave = async (changed: Partial<BusinessForm>, values: BusinessForm) => {
    const previous = savedValues.current
    if (!loaded || !previous) return
    const key = (Object.keys(FEATURE_FIELDS) as (keyof typeof FEATURE_FIELDS)[]).find(feature => {
      const fields = FEATURE_FIELDS[feature]
      return fields.enabled in changed || fields.model in changed
    })
    if (!key) return
    const fields = FEATURE_FIELDS[key]
    const restore = () => form.setFields([
      { name: fields.enabled, value: previous[fields.enabled], errors: [] },
      { name: fields.model, value: previous[fields.model], errors: [] }
    ])
    if (saveInFlight.current) { restore(); return }
    const next = { ...values }
    if (next[fields.enabled] && !next[fields.model]) {
      if (fields.enabled in changed) {
        restore()
        message.warning('请先选择模型，再开启功能')
        return
      }
      // 清空正在使用的模型时，同时关闭该业务，避免保存无效配置。
      next[fields.enabled] = false
      form.setFields([
        { name: fields.enabled, value: false, errors: [] },
        { name: fields.model, value: undefined, errors: [] }
      ])
    }
    saveInFlight.current = true
    setSaving(true)
    try {
      const config = { enabled: next[fields.enabled], modelId: next[fields.model] || '' }
      await api.put('/business', key === 'visionReply'
        ? { visionReplyEnabled: config.enabled, modelId: config.modelId }
        : { [key]: config })
      savedValues.current = { ...previous, [fields.enabled]: next[fields.enabled], [fields.model]: next[fields.model] }
      message.success(!next[fields.model] && previous[fields.enabled]
        ? '已保存：清空模型并关闭该功能'
        : '已自动保存')
    } catch (error) {
      restore()
      message.error(`保存失败，已恢复原配置：${(error as Error).message}`)
    } finally {
      saveInFlight.current = false
      setSaving(false)
    }
  }
  const eligible = (capability: string) => models.filter(item =>
    item.enabled && item.apiKeySaved && item.capabilities.includes(capability))
  const options = (capability: string) => eligible(capability).map(item => ({
    value: item.id, label: `${item.displayName}（${item.provider === 'xiaomi_mimo' ? '小米 MiMo' : item.provider}）`
  }))
  const cardStyle = { borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)', height: '100%' }

  return (
    <div style={{ maxWidth: 1280 }}>
      <Alert type="info" showIcon style={{ marginBottom: 16, borderRadius: 12 }}
        message="App 功能与模型配置 · 修改后自动保存"
        description="帮你回、超会说、截图识别：VIP 优先使用对应业务的官方模型，其他情况使用用户自配模型。基础语音识别不限制 VIP。官方 API Key 仅保存在后台。" />
      <Form form={form} layout="vertical" onValuesChange={autoSave} disabled={!loaded || saving} initialValues={{ visionReplyEnabled: false, speechRecognitionEnabled: false, replyEnabled: false, polishEnabled: false }}>
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <Card variant="borderless" title="粘贴TA的话（帮你回）" style={cardStyle} styles={{ body: { padding: 24 } }}>
              <Form.Item name="replyEnabled" label="功能开关" valuePropName="checked" extra="用于粘贴对方的话生成回复，以及帮你回中的问答与任务理解。">
                <Switch checkedChildren="已启用" unCheckedChildren="已关闭" />
              </Form.Item>
              <Form.Item name="replyModelId" label="文本模型" rules={[{ required: Boolean(replyEnabled), message: '请选择文本模型' }]}
                extra="仅列出已上架、已配置密钥且支持文本的模型，可与超会说使用不同模型。">
                <Select placeholder="选择文本模型" allowClear options={options('text')} />
              </Form.Item>
            </Card>
          </Col>
          <Col xs={24} lg={12}>
            <Card variant="borderless" title="超会说" style={cardStyle} styles={{ body: { padding: 24 } }}>
              <Form.Item name="polishEnabled" label="功能开关" valuePropName="checked" extra="用于超会说的表达优化、润色、翻译及文本改写；录音识别单独使用语音业务配置。">
                <Switch checkedChildren="已启用" unCheckedChildren="已关闭" />
              </Form.Item>
              <Form.Item name="polishModelId" label="文本模型" rules={[{ required: Boolean(polishEnabled), message: '请选择文本模型' }]}
                extra="仅列出已上架、已配置密钥且支持文本的模型。">
                <Select placeholder="选择文本模型" allowClear options={options('text')} />
              </Form.Item>
            </Card>
          </Col>
          <Col xs={24} lg={12}>
            <Card variant="borderless" title="截图识别回复（帮你回）" style={cardStyle} styles={{ body: { padding: 24 } }}>
              <Form.Item name="visionReplyEnabled" label="功能开关" valuePropName="checked"
                extra="开启后，VIP 用户可使用所选视觉模型；用户也可自行配置视觉模型。">
                <Switch checkedChildren="已启用" unCheckedChildren="已关闭" />
              </Form.Item>
              <Form.Item name="modelId" label="识别模型" rules={[{ required: Boolean(visionEnabled), message: '请选择视觉模型' }]}
                extra={loaded && !eligible('vision').length ? '暂无可选模型，请先在「模型配置」中上架一个已配置密钥的视觉模型。' : '仅列出已上架且已配置密钥的视觉模型。'}>
                <Select placeholder="选择视觉模型" allowClear options={options('vision')} />
              </Form.Item>
            </Card>
          </Col>
          <Col xs={24} lg={12}>
            <Card variant="borderless" title="语音识别" style={cardStyle} styles={{ body: { padding: 24 } }}>
              <Form.Item name="speechRecognitionEnabled" label="功能开关" valuePropName="checked"
                extra="基础功能，不限制 VIP。控制听写、超会说及帮你回中的语音转文字通道。">
                <Switch checkedChildren="已启用" unCheckedChildren="已关闭" />
              </Form.Item>
              <Form.Item name="speechModelId" label="语音模型" rules={[{ required: Boolean(speechEnabled), message: '请选择语音识别模型' }]}
                extra={loaded && !eligible('asr').length ? '暂无可选模型，请在「模型配置」添加小米 MiMo，选择 MiMo V2.5 ASR，填写密钥并上架。' : '仅列出已上架、已配置密钥且具有「语音识别」能力的模型。'}>
                <Select placeholder="选择语音识别模型" allowClear options={options('asr')} />
              </Form.Item>
              <Tag color="cyan">App 自动获取配置，无需重新安装</Tag>
              <Tag color="purple">语音调用计入模型成本</Tag>
            </Card>
          </Col>
        </Row>
      </Form>
    </div>
  )
}
