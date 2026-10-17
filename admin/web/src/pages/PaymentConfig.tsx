import { useEffect, useState } from 'react'
import { Alert, App, Button, Card, Form, Input, Select, Space, Switch, Tabs, Tag } from 'antd'
import { AlipayOutlined, ApiOutlined, SafetyOutlined, WechatOutlined } from '@ant-design/icons'
import { api } from '../api'

interface PaymentConfigResponse {
  publicBaseUrl: string
  enabledChannels: string[]
  notifyUrls: Record<string, string> | null
  channels: {
    wechat: {
      enabled: boolean
      ready: boolean
      appId: string
      mchId: string
      merchantSerialNo: string
      apiV3KeySaved: boolean
      merchantPrivateKeySaved: boolean
      platformPublicKeySaved: boolean
    }
    alipay: {
      enabled: boolean
      ready: boolean
      appId: string
      gatewayUrl: string
      sellerId: string
      privateKeySaved: boolean
      alipayPublicKeySaved: boolean
    }
    aggregator: {
      enabled: boolean
      ready: boolean
      provider: string
      appId: string
      gatewayUrl: string
      appSecretSaved: boolean
    }
  }
}

interface PaymentForm {
  publicBaseUrl?: string
  wechat?: {
    enabled?: boolean
    appId?: string
    mchId?: string
    merchantSerialNo?: string
    apiV3Key?: string
    merchantPrivateKey?: string
    platformPublicKey?: string
  }
  alipay?: {
    enabled?: boolean
    appId?: string
    sellerId?: string
    privateKey?: string
    alipayPublicKey?: string
    gatewayUrl?: string
  }
  aggregator?: {
    enabled?: boolean
    provider?: string
    appId?: string
    appSecret?: string
    gatewayUrl?: string
  }
}

const CHANNEL_LABELS: Record<string, string> = { wechat: '微信支付', alipay: '支付宝', aggregator: '聚合支付' }

const AGGREGATORS = [
  { value: 'xunhupay', label: '虎皮椒' },
  { value: 'lantu', label: '蓝兔支付' },
  { value: 'payjs', label: 'PayJS' },
  { value: 'custom', label: '自定义' }
]

function SavedTag({ saved }: { saved: boolean | undefined }) {
  if (!saved) return null
  return <Tag color="pink" style={{ marginLeft: 6 }}>已保存</Tag>
}

function ChannelStatus({ enabled, ready }: { enabled: boolean; ready: boolean }) {
  if (enabled && ready) {
    return <Alert type="success" showIcon style={{ marginBottom: 16, borderRadius: 10 }} message="已启用" description="该通道已启用，App 端下单接口会返回此支付方式。" />
  }
  if (ready) {
    return <Alert type="info" showIcon style={{ marginBottom: 16, borderRadius: 10 }} message="参数已配置，未启用" description="打开下方启用开关并保存后，App 端才能使用该通道收款。" />
  }
  return <Alert type="warning" showIcon style={{ marginBottom: 16, borderRadius: 10 }} message="参数未配置完整" description="请补全本页字段；密钥只需保存一次，之后留空表示沿用。" />
}

export default function PaymentConfig() {
  const { message } = App.useApp()
  const [form] = Form.useForm<PaymentForm>()
  const [view, setView] = useState<PaymentConfigResponse | null>(null)
  const [saving, setSaving] = useState(false)
  const baseUrl = (Form.useWatch('publicBaseUrl', form) || '').trim().replace(/\/+$/, '')

  const load = async () => {
    const data = await api.get<PaymentConfigResponse>('/pay/config')
    setView(data)
    form.setFieldsValue({
      publicBaseUrl: data.publicBaseUrl,
      wechat: {
        enabled: data.channels.wechat.enabled,
        appId: data.channels.wechat.appId,
        mchId: data.channels.wechat.mchId,
        merchantSerialNo: data.channels.wechat.merchantSerialNo
      },
      alipay: {
        enabled: data.channels.alipay.enabled,
        appId: data.channels.alipay.appId,
        gatewayUrl: data.channels.alipay.gatewayUrl,
        sellerId: data.channels.alipay.sellerId
      },
      aggregator: {
        enabled: data.channels.aggregator.enabled,
        provider: data.channels.aggregator.provider,
        appId: data.channels.aggregator.appId,
        gatewayUrl: data.channels.aggregator.gatewayUrl
      }
    })
  }

  useEffect(() => {
    load().catch(error => message.error((error as Error).message))
  }, [message])

  const save = async (values: PaymentForm) => {
    setSaving(true)
    try {
      const data = await api.put<PaymentConfigResponse>('/pay/config', values)
      setView(data)
      form.setFieldsValue({
        wechat: { apiV3Key: '', merchantPrivateKey: '', platformPublicKey: '' },
        alipay: { privateKey: '', alipayPublicKey: '' },
        aggregator: { appSecret: '' }
      })
      message.success('支付配置已保存')
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const enabledCount = view?.enabledChannels.length || 0

  const secretExtra = '已保存过密钥，留空表示不修改'

  return (
    <div>
      {enabledCount > 0 ? (
        <Alert
          type="success"
          showIcon
          style={{ marginBottom: 16, borderRadius: 12 }}
          message={`已启用收款通道：${view!.enabledChannels.map(name => CHANNEL_LABELS[name]).join('、')}`}
          description="当前 App 使用支付宝手机网站支付。请确保对应应用已上线，并已绑定手机网站支付产品。"
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16, borderRadius: 12 }}
          message="尚未启用任何收款通道"
          description="至少完整配置并启用一个通道（微信支付 / 支付宝 / 聚合支付），App 端订阅功能才能收款。"
        />
      )}
      <Card
        variant="borderless"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 26 } }}
      >
        <Form form={form} layout="vertical" onFinish={save}>
          <Form.Item
            name="publicBaseUrl"
            label="服务公网地址"
            extra={
              baseUrl.startsWith('https://')
                ? `支付回调地址：${baseUrl}/api/pay/notify/wechat（微信）、…/alipay（支付宝）、…/aggregator（聚合），请填到对应支付后台`
                : '必填（启用通道前）：本后台对外的 HTTPS 地址，如 https://lianai.bahuang.tech，用于拼接支付回调 URL'
            }
          >
            <Input placeholder="如 https://lianai.bahuang.tech" />
          </Form.Item>
          <Tabs
            defaultActiveKey="alipay"
            items={[
              {
                key: 'wechat',
                label: (
                  <span><WechatOutlined style={{ marginRight: 6 }} />微信支付</span>
                ),
                forceRender: true,
                children: (
                  <>
                    <ChannelStatus enabled={!!view?.channels.wechat.enabled} ready={!!view?.channels.wechat.ready} />
                    <Form.Item name={['wechat', 'enabled']} label="启用该通道" valuePropName="checked">
                      <Switch />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                      <Form.Item name={['wechat', 'appId']} label="AppID" style={{ flex: 1 }}>
                        <Input placeholder="微信开放平台应用 AppID" autoComplete="off" />
                      </Form.Item>
                      <Form.Item name={['wechat', 'mchId']} label="商户号" style={{ flex: 1 }}>
                        <Input placeholder="如 1610000000" autoComplete="off" />
                      </Form.Item>
                    </Space>
                    <Form.Item name={['wechat', 'merchantSerialNo']} label="商户证书序列号">
                      <Input placeholder="商户 API 证书序列号（40 位十六进制）" autoComplete="off" />
                    </Form.Item>
                    <Form.Item
                      name={['wechat', 'apiV3Key']}
                      label={<span>APIv3 密钥<SavedTag saved={view?.channels.wechat.apiV3KeySaved} /></span>}
                      extra={view?.channels.wechat.apiV3KeySaved ? secretExtra : '商户平台设置的 32 位 APIv3 密钥'}
                    >
                      <Input.Password placeholder="APIv3 密钥" autoComplete="new-password" />
                    </Form.Item>
                    <Form.Item
                      name={['wechat', 'merchantPrivateKey']}
                      label={<span>商户 API 私钥<SavedTag saved={view?.channels.wechat.merchantPrivateKeySaved} /></span>}
                      extra={(view?.channels.wechat.merchantPrivateKeySaved ? `${secretExtra}。` : '') + '粘贴 apiclient_key.pem 或 RSA 私钥内容（含 BEGIN/END 行）'}
                    >
                      <Input.TextArea rows={4} placeholder="-----BEGIN PRIVATE KEY-----" autoComplete="off" />
                    </Form.Item>
                    <Form.Item
                      name={['wechat', 'platformPublicKey']}
                      label={<span>支付平台公钥<SavedTag saved={view?.channels.wechat.platformPublicKeySaved} /></span>}
                      extra={(view?.channels.wechat.platformPublicKeySaved ? `${secretExtra}。` : '') + '公钥模式下商户平台「API安全」页提供的支付公钥（pub.pem），用于校验微信回调签名'}
                    >
                      <Input.TextArea rows={3} placeholder="-----BEGIN PUBLIC KEY-----" autoComplete="off" />
                    </Form.Item>
                  </>
                )
              },
              {
                key: 'alipay',
                label: (
                  <span><AlipayOutlined style={{ marginRight: 6 }} />支付宝</span>
                ),
                forceRender: true,
                children: (
                  <>
                    <ChannelStatus enabled={!!view?.channels.alipay.enabled} ready={!!view?.channels.alipay.ready} />
                    <Form.Item name={['alipay', 'enabled']} label="启用该通道" valuePropName="checked">
                      <Switch />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                      <Form.Item name={['alipay', 'appId']} label="AppID" style={{ flex: 1 }}>
                        <Input placeholder="支付宝开放平台应用 AppID" autoComplete="off" />
                      </Form.Item>
                      <Form.Item name={['alipay', 'gatewayUrl']} label="网关地址" style={{ flex: 1 }}>
                        <Input placeholder="https://openapi.alipay.com/gateway.do" autoComplete="off" />
                      </Form.Item>
                    </Space>
                    <Form.Item name={['alipay', 'sellerId']} label="收款方用户 ID（选填）" extra="支付宝商家账号的 2088 开头用户 ID；填写后会额外核对回调和查单的收款方。">
                      <Input placeholder="如 2088…" autoComplete="off" />
                    </Form.Item>
                    <Form.Item
                      name={['alipay', 'privateKey']}
                      label={<span>应用私钥<SavedTag saved={view?.channels.alipay.privateKeySaved} /></span>}
                      extra={(view?.channels.alipay.privateKeySaved ? `${secretExtra}。` : '') + '密钥模式（公钥）下开放平台配置的应用私钥'}
                    >
                      <Input.TextArea rows={4} placeholder="-----BEGIN PRIVATE KEY-----" autoComplete="off" />
                    </Form.Item>
                    <Form.Item
                      name={['alipay', 'alipayPublicKey']}
                      label={<span>支付宝公钥<SavedTag saved={view?.channels.alipay.alipayPublicKeySaved} /></span>}
                      extra={(view?.channels.alipay.alipayPublicKeySaved ? `${secretExtra}。` : '') + '用于校验异步通知签名，非商户公钥'}
                    >
                      <Input.TextArea rows={3} placeholder="支付宝公钥内容" autoComplete="off" />
                    </Form.Item>
                  </>
                )
              },
              {
                key: 'aggregator',
                label: (
                  <span><ApiOutlined style={{ marginRight: 6 }} />聚合支付</span>
                ),
                forceRender: true,
                children: (
                  <>
                    <ChannelStatus enabled={!!view?.channels.aggregator.enabled} ready={!!view?.channels.aggregator.ready} />
                    <Alert
                      type="info"
                      showIcon
                      style={{ marginBottom: 16, borderRadius: 10 }}
                      message="适合尚未开通微信/支付宝商户号的情况"
                      description="聚合服务商（如虎皮椒）提供 H5 收银台，App 拉起 WebView 完成支付，个人主体即可申请。"
                    />
                    <Form.Item name={['aggregator', 'enabled']} label="启用该通道" valuePropName="checked">
                      <Switch />
                    </Form.Item>
                    <Space size={16} style={{ display: 'flex' }}>
                      <Form.Item name={['aggregator', 'provider']} label="服务商" style={{ flex: 1 }}>
                        <Select options={AGGREGATORS} />
                      </Form.Item>
                      <Form.Item name={['aggregator', 'gatewayUrl']} label="网关地址" style={{ flex: 1 }}>
                        <Input placeholder="以服务商文档为准，如 https://api.xunhupay.com" autoComplete="off" />
                      </Form.Item>
                    </Space>
                    <Form.Item name={['aggregator', 'appId']} label="AppID">
                      <Input placeholder="服务商分配的 appid" autoComplete="off" />
                    </Form.Item>
                    <Form.Item
                      name={['aggregator', 'appSecret']}
                      label={<span>AppSecret<SavedTag saved={view?.channels.aggregator.appSecretSaved} /></span>}
                      extra={view?.channels.aggregator.appSecretSaved ? secretExtra : '服务商分配的通信密钥'}
                    >
                      <Input.Password placeholder="AppSecret" autoComplete="new-password" />
                    </Form.Item>
                  </>
                )
              }
            ]}
          />
          <Button type="primary" htmlType="submit" loading={saving} style={{ marginTop: 6 }}>
            保存配置
          </Button>
        </Form>
        <Alert
          style={{ marginTop: 20, borderRadius: 10 }}
          type="info"
          showIcon
          icon={<SafetyOutlined />}
          message="安全提示"
          description="支付密钥仅保存在服务器本地的 db.json 中，前端与 App 端都不会拿到原文；回显只显示「已保存」状态。建议为每个通道单独创建密钥，泄露时在支付后台重置后回到本页覆盖保存即可。"
        />
      </Card>
    </div>
  )
}
