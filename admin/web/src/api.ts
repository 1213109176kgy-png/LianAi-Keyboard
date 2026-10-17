export const TOKEN_KEY = 'lianai_admin_token'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem(TOKEN_KEY) || ''
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: {
      ...(init.headers || {}),
      Authorization: `Bearer ${token}`
    }
  })
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    window.location.href = '/login'
    throw new ApiError(401, '登录已过期，请重新登录')
  }
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new ApiError(response.status, (body as { message?: string }).message || `请求失败（${response.status}）`)
  }
  return body as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }),
  put: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  upload: <T>(path: string, form: FormData, onProgress?: (percent: number) => void) =>
    new Promise<T>((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', `/api${path}`)
      xhr.setRequestHeader('Authorization', `Bearer ${localStorage.getItem(TOKEN_KEY) || ''}`)
      xhr.upload.onprogress = event => {
        if (event.lengthComputable && onProgress) onProgress(Math.round((event.loaded / event.total) * 100))
      }
      xhr.onload = () => {
        let body: unknown = {}
        try {
          body = JSON.parse(xhr.responseText)
        } catch {
          /* ignore */
        }
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(body as T)
        } else if (xhr.status === 401) {
          localStorage.removeItem(TOKEN_KEY)
          window.location.href = '/login'
          reject(new ApiError(401, '登录已过期，请重新登录'))
        } else {
          reject(new ApiError(xhr.status, (body as { message?: string }).message || '上传失败'))
        }
      }
      xhr.onerror = () => reject(new ApiError(0, '网络异常，上传失败'))
      xhr.send(form)
    })
}
