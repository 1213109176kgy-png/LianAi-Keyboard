import { useEffect, useState } from 'react'
import { Alert, App, Button, Card, Form, Input, Select, Space, Tag } from 'antd'
import { ApiOutlined, SafetyOutlined } from '@ant-design/icons'
import { api } from '../api'

interface OssConfigResponse {
  configured: boolean
  config: {
    region: string
    bucket: string
    accessKeyId: string
    accessKeySecretMasked: string
    pathPrefix: string
    cdnHost: string
  } | null
}

interface OssForm {
  region: string
  bucket: string
  accessKeyId: string
  accessKeySecret?: string
  pathPrefix?: string
  cdnHost?: string
}

const REGIONS = [
  'oss-cn-hangzhou',
  'oss-cn-shanghai',
  'oss-cn-qingdao',
  'oss-cn-beijing',
  'oss-cn-zhangjiakou',
  'oss-cn-huhehaote',
  'oss-cn-shenzhen',
  'oss-cn-guangzhou',
  'oss-cn-chengdu',
  'oss-cn-hongkong',
  'oss-ap-southeast-1'
]

export default function OssConfig() {
  const { message } = App.useApp()
  const [form] = Form.useForm<OssForm>()
  const [configured, setConfigured] = useState(false)
  const [secretMasked, setSecretMasked] = useState('')
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)

  useEffect(() => {
    ;(async () => {
      try {
        const data = await api.get<OssConfigResponse>('/oss')
        setConfigured(data.configured)
        if (data.config) {
          setSecretMasked(data.config.accessKeySecretMasked)
          form.setFieldsValue({
            region: data.config.region,
            bucket: data.config.bucket,
            accessKeyId: data.config.accessKeyId,
            pathPrefix: data.config.pathPrefix,
            cdnHost: data.config.cdnHost
          })
        }
      } catch (error) {
        message.error((error as Error).message)
      }
    })()
  }, [form, message])

  const save = async (values: OssForm) => {
    setSaving(true)
    try {
      await api.put('/oss', values)
      message.success('OSS 配置已保存')
      const data = await api.get<OssConfigResponse>('/oss')
      setConfigured(data.configured)
      setSecretMasked(data.config?.accessKeySecretMasked || '')
      form.setFieldValue('accessKeySecret', undefined)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const test = async () => {
    setTesting(true)
    try {
      await api.post('/oss/test', {})
      message.success('连接成功，Bucket 可正常访问')
    } catch (error) {
      message.error(`连接失败：${(error as Error).message}`)
    } finally {
      setTesting(false)
    }
  }

  return (
    <div>
      {configured ? (
        <Alert
          type="success"
          showIcon
          style={{ marginBottom: 16, borderRadius: 12 }}
          message="OSS 已配置"
          description="上传的升级包会存入该 Bucket，App 端升级接口会返回对应的下载地址。"
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16, borderRadius: 12 }}
          message="尚未配置 OSS"
          description="保存配置后才能上传升级包。建议 Bucket 读写权限设为「公共读」，或使用 Bucket Policy 限定目录只读。"
        />
      )}
      <Card
        variant="borderless"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 26 } }}
      >
        <Form form={form} layout="vertical" onFinish={save}>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              name="region"
              label="Region"
              rules={[{ required: true, message: '请选择或输入 Region' }]}
              style={{ flex: 1 }}
            >
              <Select placeholder="如 oss-cn-shenzhen" options={REGIONS.map(value => ({ value }))} showSearch />
            </Form.Item>
            <Form.Item
              name="bucket"
              label="Bucket"
              rules={[{ required: true, message: '请输入 Bucket 名称' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="如 lianai-keyboard" />
            </Form.Item>
          </Space>
          <Form.Item
            name="accessKeyId"
            label="AccessKey ID"
            rules={[{ required: true, message: '请输入 AccessKey ID' }]}
          >
            <Input placeholder="阿里云 AccessKey ID" autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="accessKeySecret"
            label={
              <span>
                AccessKey Secret{' '}
                {secretMasked && <Tag color="pink" style={{ marginLeft: 6 }}>{secretMasked}</Tag>}
              </span>
            }
            extra={secretMasked ? '已保存过 Secret，留空表示不修改' : undefined}
          >
            <Input.Password placeholder="阿里云 AccessKey Secret" autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="pathPrefix" label="存储路径前缀" extra="可选。如填 app 后，文件会存到 app/ 目录下">
            <Input placeholder="如 app" />
          </Form.Item>
          <Form.Item
            name="cdnHost"
            label="分发域名（CDN）"
            extra="必填项：阿里云禁止用 OSS 默认域名分发 APK，安装包下载地址会优先使用该已备案域名（如 dl.bahuang.tech）"
            rules={[{ required: true, message: '请填写已备案的 CDN/自定义域名' }]}
          >
            <Input placeholder="如 dl.bahuang.tech" />
          </Form.Item>
          <div style={{ display: 'flex', gap: 12, marginTop: 6 }}>
            <Button type="primary" htmlType="submit" loading={saving}>
              保存配置
            </Button>
            <Button icon={<ApiOutlined />} loading={testing} onClick={test} disabled={!configured}>
              测试连接
            </Button>
          </div>
        </Form>
        <Alert
          style={{ marginTop: 20, borderRadius: 10 }}
          type="info"
          showIcon
          icon={<SafetyOutlined />}
          message="安全提示"
          description="AccessKey 仅保存在服务器本地的 db.json 中，前端不会回显完整密钥。请为后台单独创建权限最小化的 RAM 子账号（只授权该 Bucket 的读写）。"
        />
      </Card>
    </div>
  )
}
