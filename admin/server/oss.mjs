import OSS from 'ali-oss'
import fs from 'node:fs'
import crypto from 'node:crypto'

export function ossConfigured(config) {
  return Boolean(
    config &&
    config.region &&
    config.bucket &&
    config.accessKeyId &&
    config.accessKeySecret
  )
}

function clientOf(config) {
  return new OSS({
    region: config.region,
    bucket: config.bucket,
    accessKeyId: config.accessKeyId,
    accessKeySecret: config.accessKeySecret,
    secure: true,
    // Small ECS uplinks (measured ~0.4MB/s cross-region) need roomy timeouts.
    timeout: 600000
  })
}

/**
 * Distribution URL for an object. APK files must be served through the user's
 * own filed custom domain (CDN); OSS default endpoints reject APK downloads
 * with ApkDownloadForbidden, so cdnHost takes priority when configured.
 */
export function publicUrl(config, key) {
  const cdnHost = String(config.cdnHost || '').replace(/^https?:\/\//, '').replace(/\/+$/, '')
  if (cdnHost) return `https://${cdnHost}/${key}`
  const region = config.region.replace(/^https?:\/\//, '').replace(/\/$/, '')
  return `https://${config.bucket}.${region}.aliyuncs.com/${key}`
}

/** Pre-signed PUT URL so the browser can upload directly to OSS. */
export function presignPutUrl(config, key) {
  const client = clientOf(config)
  return client.signatureUrl(key, {
    method: 'PUT',
    expires: 1800,
    'Content-Type': 'application/octet-stream'
  })
}

export function objectKey(config, fileName) {
  const prefix = (config.pathPrefix || '').replace(/^\/+|\/+$/g, '')
  const safe = fileName.replace(/[^\w.\-]+/g, '_')
  return prefix ? `${prefix}/${safe}` : safe
}

export async function testConnection(config) {
  const client = clientOf(config)
  const info = await client.getBucketInfo()
  return info?.bucket?.Location || 'ok'
}

export async function uploadFile(config, key, filePath) {
  const client = clientOf(config)
  // Multipart keeps every request well under the timeout on slow uplinks,
  // where a single-stream put of a ~60MB APK would exceed it.
  await client.multipartUpload(key, filePath, {
    partSize: 5 * 1024 * 1024,
    timeout: 600000
  })
  return publicUrl(config, key)
}

export async function deleteObject(config, key) {
  const client = clientOf(config)
  await client.delete(key)
}

export function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const digest = crypto.createHash('sha256')
    const stream = fs.createReadStream(filePath)
    stream.on('data', chunk => digest.update(chunk))
    stream.on('error', reject)
    stream.on('end', () => resolve(digest.digest('hex')))
  })
}
