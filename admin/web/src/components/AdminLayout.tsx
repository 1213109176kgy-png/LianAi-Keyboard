import { useState } from 'react'
import { Avatar, Dropdown, Layout, Menu, App } from 'antd'
import {
  BarChartOutlined,
  CloudUploadOutlined,
  CrownOutlined,
  KeyOutlined,
  LineChartOutlined,
  LogoutOutlined,
  RobotOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
  AppstoreOutlined,
  ShoppingOutlined,
  PayCircleOutlined
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api'

const ITEMS = [
  { key: '/versions', icon: <AppstoreOutlined />, label: '版本管理' },
  { key: '/oss', icon: <CloudUploadOutlined />, label: 'OSS 配置' },
  { key: '/payment', icon: <PayCircleOutlined />, label: '支付配置' },
  { key: '/plans', icon: <CrownOutlined />, label: '套餐管理' },
  { key: '/orders', icon: <ShoppingOutlined />, label: '订单管理' },
  { key: '/models', icon: <RobotOutlined />, label: '模型配置' },
  { key: '/business', icon: <SettingOutlined />, label: '业务配置' },
  { key: '/costs', icon: <BarChartOutlined />, label: '模型成本' },
  { key: '/users', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/dau', icon: <LineChartOutlined />, label: '日活统计' },
  { key: '/password', icon: <KeyOutlined />, label: '修改密码' }
]

const TITLES: Record<string, { title: string; subtitle: string }> = {
  '/versions': { title: '版本管理', subtitle: '上传 APK 升级包，App 端将自动检测并提示更新' },
  '/oss': { title: 'OSS 配置', subtitle: '配置阿里云 OSS 后，上传的安装包会存入你的 Bucket' },
  '/payment': { title: '支付配置', subtitle: '配置订阅收款通道参数（微信、支付宝、聚合支付），密钥仅保存在服务端' },
  '/orders': { title: '订单管理', subtitle: '查看订单、支付状态与金额，支持手动补单并发放套餐权益' },
  '/plans': { title: '套餐管理', subtitle: '维护订阅套餐（价格、有效期、AI 额度），上架后 App 订阅页自动展示' },
  '/models': { title: '模型配置', subtitle: '配置 DeepSeek、GLM、Kimi、小米 MiMo 等模型与密钥' },
  '/business': { title: '业务配置', subtitle: '帮你回、超会说、截图识别与基础语音的模型选择' },
  '/costs': { title: '模型成本', subtitle: '模型调用记账、VIP 收入与利润统计' },
  '/users': { title: '用户管理', subtitle: 'App 注册用户列表与详情（订阅、订单、AI 调用、活跃）' },
  '/dau': { title: '日活统计', subtitle: '用户日活趋势、新增与经营概览' },
  '/password': { title: '修改密码', subtitle: '定期更换后台登录密码' }
}

export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { message } = App.useApp()
  const [collapsed, setCollapsed] = useState(false)

  const logout = async () => {
    try {
      await api.post('/logout')
    } catch {
      /* ignore */
    }
    localStorage.removeItem('lianai_admin_token')
    message.success('已退出登录')
    navigate('/login', { replace: true })
  }

  const meta = TITLES[location.pathname] || TITLES['/versions']

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider
        theme="dark"
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        width={216}
        style={{
          background: 'linear-gradient(180deg, #33262b 0%, #2a1f24 100%)'
        }}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 10,
            overflow: 'hidden'
          }}
        >
          <img
            src="/brand/logo.webp"
            alt="恋爱键盘 Logo"
            style={{
              width: 34,
              height: 34,
              borderRadius: 11,
              objectFit: 'contain',
              flex: 'none'
            }}
          />
          {!collapsed && (
            <div style={{ color: '#fff', fontWeight: 700, fontSize: 15, whiteSpace: 'nowrap' }}>恋爱键盘后台</div>
          )}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={ITEMS}
          onClick={({ key }) => navigate(key)}
          style={{ background: 'transparent', borderInlineEnd: 'none' }}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header
          style={{
            background: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 28px',
            borderBottom: '1px solid #f3e6ea',
            height: 64
          }}
        >
          <div>
            <span style={{ fontSize: 16, fontWeight: 650, color: '#333' }}>{meta.title}</span>
            <span style={{ marginLeft: 12, fontSize: 12.5, color: '#b09aa3' }}>{meta.subtitle}</span>
          </div>
          <Dropdown
            menu={{
              items: [
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }
              ]
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }}>
              <Avatar style={{ background: '#ff6b9d' }} icon={<UserOutlined />} />
              <span style={{ color: '#555' }}>admin</span>
            </div>
          </Dropdown>
        </Layout.Header>
        <Layout.Content style={{ padding: 24 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  )
}
