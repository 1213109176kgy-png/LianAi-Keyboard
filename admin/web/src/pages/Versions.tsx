import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Progress,
  Space,
  Table,
  Tag,
  Typography,
  Upload
} from 'antd'
import { CloudUploadOutlined, CopyOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import type { UploadFile } from 'antd'
import { api } from '../api'

interface VersionRecord {
  id: string
  versionCode: number
  versionName: string
  notes: string
  apkUrl: string
  sha256: string
  sizeBytes: number
  createdAt: number
}

function formatSize(bytes: number) {
  if (!bytes) return '-'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

interface UploadForm {
  versionName: string
  versionCode: number
  notes?: string
}

export default function Versions() {
  const { message, modal } = App.useApp()
  const [versions, setVersions] = useState<VersionRecord[]>([])
  const [ossReady, setOssReady] = useState(false)
  const [loading, setLoading] = useState(true)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [percent, setPercent] = useState(0)
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [form] = Form.useForm<UploadForm>()
  const [editing, setEditing] = useState<VersionRecord | null>(null)
  const [editingNotes, setEditingNotes] = useState('')
  const [editOpen, setEditOpen] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const data = await api.get<{ versions: VersionRecord[]; ossConfigured: boolean }>('/versions')
      setVersions(data.versions)
      setOssReady(data.ossConfigured)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const openUpload = () => {
    if (!ossReady) {
      modal.warning({
        title: '请先配置阿里云 OSS',
        content: '升级包会直接上传到你配置的 OSS Bucket。当前尚未配置 OSS，请先前往「OSS 配置」完成设置。',
        okText: '前往配置',
        onOk: () => window.location.assign('/oss')
      })
      return
    }
    setFileList([])
    setPercent(0)
    form.resetFields()
    setUploadOpen(true)
  }

  const submitUpload = async (values: UploadForm) => {
    const file = fileList[0]?.originFileObj
    if (!file) {
      message.error('请选择要上传的 APK 文件')
      return
    }
    setUploading(true)
    setPercent(0)
    try {
      // 1) Ask the backend for a pre-signed PUT URL.
      const pre = await api.post<{ url: string; key: string; apkUrl: string }>('/oss/presign', {
        fileName: `lianai-${values.versionName}.apk`
      })
      // 2) Stream the file straight to OSS (never through the admin server).
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest()
        xhr.open('PUT', pre.url)
        xhr.setRequestHeader('Content-Type', 'application/octet-stream')
        xhr.upload.onprogress = event => {
          if (event.lengthComputable) setPercent(Math.round((event.loaded / event.total) * 100))
        }
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) resolve()
          else reject(new Error(`OSS 上传失败（HTTP ${xhr.status}）`))
        }
        xhr.onerror = () => reject(new Error('网络异常，无法连接 OSS'))
        xhr.send(file)
      })
      setPercent(100)
      // 3) Local SHA-256 for the app-side integrity check.
      const { sha256 } = await import('js-sha256')
      const hasher = sha256.create()
      const reader = (file as File).stream().getReader()
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        hasher.update(value)
      }
      // 4) Register the release; the download URL already points at the CDN host.
      await api.post('/versions/register', {
        versionCode: values.versionCode,
        versionName: values.versionName,
        notes: values.notes || '',
        apkUrl: pre.apkUrl,
        sha256: hasher.hex(),
        sizeBytes: file.size
      })
      message.success('上传成功，已发布到升级接口')
      setUploadOpen(false)
      await refresh()
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setUploading(false)
    }
  }

  const removeVersion = async (record: VersionRecord) => {
    try {
      await api.delete(`/versions/${record.id}`)
      message.success('已删除')
      await refresh()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const saveNotes = async () => {
    if (!editing) return
    try {
      await api.put(`/versions/${editing.id}`, { notes: editingNotes })
      message.success('更新说明已保存')
      setEditOpen(false)
      await refresh()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const columns = [
    {
      title: '版本',
      key: 'version',
      width: 130,
      render: (_: unknown, record: VersionRecord) => (
        <Space size={6}>
          <span style={{ fontWeight: 650 }}>{record.versionName}</span>
          {versions[0]?.id === record.id && <Tag color="magenta">最新</Tag>}
        </Space>
      )
    },
    { title: 'versionCode', dataIndex: 'versionCode', width: 110 },
    {
      title: '安装包',
      key: 'size',
      width: 100,
      render: (_: unknown, record: VersionRecord) => formatSize(record.sizeBytes)
    },
    {
      title: 'SHA-256',
      dataIndex: 'sha256',
      width: 150,
      render: (value: string) => (
        <Typography.Text
          style={{ fontSize: 12, maxWidth: 140 }}
          ellipsis={{ tooltip: value }}
          copyable={{ text: value, icon: <CopyOutlined style={{ color: '#e08aa4' }} /> }}
        >
          {value}
        </Typography.Text>
      )
    },
    {
      title: '更新说明',
      dataIndex: 'notes',
      ellipsis: true
    },
    {
      title: '上传时间',
      key: 'createdAt',
      width: 150,
      render: (_: unknown, record: VersionRecord) => dayjs(record.createdAt).format('YYYY-MM-DD HH:mm')
    },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_: unknown, record: VersionRecord) => (
        <Space>
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            onClick={() => {
              setEditing(record)
              setEditingNotes(record.notes)
              setEditOpen(true)
            }}
          >
            说明
          </Button>
          <Popconfirm title="删除该版本？" description="将同时删除 OSS 上的安装包" onConfirm={() => removeVersion(record)}>
            <Button type="text" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <div>
      {!ossReady && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16, borderRadius: 12 }}
          message="尚未配置阿里云 OSS"
          description="配置完成后才能上传升级包。上传的 APK 会直接存入你自己的 OSS Bucket。"
          action={
            <Button size="small" type="primary" onClick={() => window.location.assign('/oss')}>
              去配置
            </Button>
          }
        />
      )}
      <Card
        variant="borderless"
        style={{ borderRadius: 16, boxShadow: '0 10px 32px rgba(214,126,158,.10)' }}
        styles={{ body: { padding: 20 } }}
        title={<span style={{ fontWeight: 650 }}>已发布版本（{versions.length}）</span>}
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void refresh()} loading={loading}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openUpload}>
              上传新版本
            </Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={versions}
          pagination={false}
          locale={{ emptyText: '还没有上传过版本，点击右上角「上传新版本」开始' }}
        />
      </Card>

      <Modal
        title="上传新版本"
        open={uploadOpen}
        onCancel={() => !uploading && setUploadOpen(false)}
        footer={null}
        destroyOnClose
        width={520
        }
      >
        <Form form={form} layout="vertical" onFinish={submitUpload} style={{ marginTop: 8 }}>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              name="versionName"
              label="版本名称"
              rules={[{ required: true, message: '如 1.5.4' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="如 1.5.4" />
            </Form.Item>
            <Form.Item
              name="versionCode"
              label="versionCode"
              rules={[{ required: true, message: '递增的整数，如 34' }]}
              style={{ flex: 1 }}
            >
              <InputNumber min={1} precision={0} placeholder="34" style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item name="notes" label="更新说明">
            <Input.TextArea rows={3} placeholder="展示给用户的更新内容，如：修复若干问题、新增……" />
          </Form.Item>
          <Form.Item label="安装包（.apk）" required>
            <Upload.Dragger
              accept=".apk"
              maxCount={1}
              fileList={fileList}
              beforeUpload={() => false}
              onChange={({ fileList: list }) => setFileList(list)}
            >
              <p className="ant-upload-drag-icon">
                <CloudUploadOutlined style={{ color: '#ff6b9d', fontSize: 40 }} />
              </p>
              <p className="ant-upload-text">点击或拖拽 APK 到此处</p>
              <p className="ant-upload-hint">由你的浏览器直传 OSS（不经过服务器），自动计算 SHA-256 并通过 CDN 域名发布</p>
            </Upload.Dragger>
          </Form.Item>
          {uploading && <Progress percent={percent} style={{ marginBottom: 16 }} />}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
            <Button onClick={() => setUploadOpen(false)} disabled={uploading}>
              取消
            </Button>
            <Button type="primary" htmlType="submit" loading={uploading}>
              {uploading ? `上传中 ${percent}%` : '上传并发布'}
            </Button>
          </div>
        </Form>
      </Modal>

      <Modal
        title={`更新说明 · ${editing?.versionName || ''}`}
        open={editOpen}
        onOk={saveNotes}
        onCancel={() => setEditOpen(false)}
        okText="保存"
        destroyOnClose
      >
        <Input.TextArea rows={4} value={editingNotes} onChange={event => setEditingNotes(event.target.value)} />
      </Modal>
    </div>
  )
}
