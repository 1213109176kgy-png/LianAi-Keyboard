import { useState } from 'react'
import { Button, Card, Form, Input, App } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api, TOKEN_KEY } from '../api'

interface LoginForm {
  username: string
  password: string
}

export default function Login() {
  const [loading, setLoading] = useState(false)
  const { message } = App.useApp()
  const navigate = useNavigate()

  const onFinish = async (values: LoginForm) => {
    setLoading(true)
    try {
      const result = await api.post<{ token: string }>('/login', values)
      localStorage.setItem(TOKEN_KEY, result.token)
      message.success('登录成功')
      navigate('/versions', { replace: true })
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background:
          'radial-gradient(1100px 520px at 12% 8%, #ffd9e8 0%, transparent 55%),' +
          'radial-gradient(900px 520px at 88% 92%, #ffe6d5 0%, transparent 55%),' +
          'linear-gradient(135deg, #fff5f8 0%, #ffeef4 45%, #ffe3ec 100%)',
        padding: 24
      }}
    >
      <div style={{ width: 396, maxWidth: '100%' }}>
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <img
            src="/brand/logo.webp"
            alt="恋爱键盘 Logo"
            style={{
              width: 72,
              height: 72,
              margin: '0 auto 18px',
              borderRadius: 24,
              boxShadow: '0 14px 34px rgba(53, 170, 232, .18)',
              display: 'block',
              objectFit: 'contain'
            }}
          />
          <div style={{ fontSize: 24, fontWeight: 700, color: '#333', letterSpacing: 1 }}>恋爱键盘 · 管理后台</div>
          <div style={{ marginTop: 8, fontSize: 13, color: '#a38a95' }}>升级包管理与阿里云 OSS 配置</div>
        </div>
        <Card
          variant="borderless"
          style={{ borderRadius: 20, boxShadow: '0 18px 50px rgba(214, 126, 158, .18)' }}
          styles={{ body: { padding: '30px 30px 22px' } }}
        >
          <Form<LoginForm> size="large" onFinish={onFinish} autoComplete="off">
            <Form.Item name="username" rules={[{ required: true, message: '请输入账号' }]}>
              <Input prefix={<UserOutlined style={{ color: '#e08aa4' }} />} placeholder="账号" />
            </Form.Item>
            <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password prefix={<LockOutlined style={{ color: '#e08aa4' }} />} placeholder="密码" />
            </Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={loading}
              style={{ height: 44, fontWeight: 600, boxShadow: '0 8px 20px rgba(255, 107, 157, .35)' }}
            >
              登 录
            </Button>
          </Form>
          <div style={{ marginTop: 16, textAlign: 'center', fontSize: 12, color: '#c1a7b1' }}>
            默认账号 admin / admin123，登录后请及时修改密码
          </div>
        </Card>
      </div>
    </div>
  )
}
