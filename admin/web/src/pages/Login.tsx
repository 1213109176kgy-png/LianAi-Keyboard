import { useState } from 'react'
import { Button, Form, Input, App } from 'antd'
import {
  ArrowRightOutlined,
  AudioOutlined,
  CheckOutlined,
  HeartFilled,
  LockOutlined,
  MessageOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  UserOutlined
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api, TOKEN_KEY } from '../api'
import './Login.css'

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
    <main className="login-page">
      <div className="login-shell">
        <section className="login-story" aria-label="恋爱键盘">
          <div className="login-brand">
            <img src="/brand/logo.webp" alt="" width={54} height={54} />
            <div><strong>恋爱键盘</strong><span>让表达更有温度</span></div>
          </div>
          <div className="login-story-content">
            <h1>让心意，<br />有更好的表达。</h1>
            <p className="login-story-description">从一句回复开始，连接每一次真诚的交流。</p>
            <div className="login-illustration" aria-hidden="true">
              <div className="login-art-orbit" />
              <div className="login-art-heart"><HeartFilled /></div>
              <div className="login-chat login-chat-incoming">
                <span className="login-chat-avatar"><MessageOutlined /></span>
                <div><span className="login-chat-label">TA 的消息</span><p>今天有件开心的事，<br />想第一个告诉你。</p></div>
              </div>
              <div className="login-chat login-chat-reply">
                <span className="login-reply-label"><ThunderboltOutlined /> 帮你回</span>
                <p>那我得认真听听，<br />你的开心值得被分享。</p>
                <span className="login-reply-status"><CheckOutlined /> 把心意，好好说出来</span>
              </div>
              <div className="login-keyboard">
                <span className="login-key-symbol"><AudioOutlined /></span>
                <span className="login-key-space">每一句，都更懂你</span>
                <span className="login-key-send"><ArrowRightOutlined /></span>
              </div>
            </div>
          </div>
          <div className="login-capabilities">
            <span><MessageOutlined /> 智能回复</span>
            <span><HeartFilled /> 超会说</span>
            <span><AudioOutlined /> 语音输入</span>
          </div>
        </section>

        <section className="login-form-panel" aria-labelledby="login-title">
          <div className="login-form-heading">
            <span className="login-access-icon"><LockOutlined /></span>
            <h2 id="login-title">欢迎回来</h2>
            <p>登录恋爱键盘管理后台</p>
          </div>
          <Form<LoginForm> className="login-form" layout="vertical" size="large" onFinish={onFinish} requiredMark={false}>
            <Form.Item label="管理员账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
              <Input prefix={<UserOutlined />} placeholder="请输入管理员账号" autoComplete="username" spellCheck={false} />
            </Form.Item>
            <Form.Item label="登录密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password prefix={<LockOutlined />} placeholder="请输入登录密码" autoComplete="current-password" />
            </Form.Item>
            <Button className="login-submit" type="primary" htmlType="submit" block loading={loading}>
              {loading ? '正在登录' : '登录后台'}{!loading && <ArrowRightOutlined />}
            </Button>
          </Form>
          <div className="login-access-note"><SafetyCertificateOutlined /> 仅限授权管理员访问</div>
        </section>
      </div>
      <footer className="login-footer">恋爱键盘 · 管理后台</footer>
    </main>
  )
}
