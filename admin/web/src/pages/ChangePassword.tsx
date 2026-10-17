import { Button, Card, Form, Input, App } from 'antd'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'

interface PasswordForm {
  oldPassword: string
  newPassword: string
  confirm: string
}

export default function ChangePassword() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [form] = Form.useForm<PasswordForm>()
  const [saving, setSaving] = useState(false)

  const submit = async (values: PasswordForm) => {
    setSaving(true)
    try {
      await api.post('/password', { oldPassword: values.oldPassword, newPassword: values.newPassword })
      message.success('密码已修改，请重新登录')
      localStorage.removeItem('lianai_admin_token')
      navigate('/login', { replace: true })
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <Card
        variant="borderless"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 26 } }}
      >
        <Form form={form} layout="vertical" onFinish={submit}>
          <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, message: '至少 6 位' }
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirm"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || value === getFieldValue('newPassword')) return Promise.resolve()
                  return Promise.reject(new Error('两次输入的密码不一致'))
                }
              })
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={saving} block>
            保存并重新登录
          </Button>
        </Form>
      </Card>
    </div>
  )
}
