import crypto from 'node:crypto'

const GATEWAY = 'https://api.mch.weixin.qq.com'

function toPem(base64, label) {
  const body = String(base64).replace(/\s/g, '').replace(/-{5}[A-Z ]+-{5}/g, '')
  const lines = body.match(/.{1,64}/g) || []
  return `-----BEGIN ${label}-----\n${lines.join('\n')}\n-----END ${label}-----\n`
}

const merchantKey = config => crypto.createPrivateKey(toPem(config.merchantPrivateKey, 'PRIVATE KEY'))
const platformKey = config => crypto.createPublicKey(toPem(config.platformPublicKey, 'PUBLIC KEY'))

const nonce = () => crypto.randomBytes(16).toString('hex')

/** 微信支付 V3 请求签名（Authorization scheme 固定为 WECHATPAY2-SHA256-RSA2048） */
function authorization(config, method, urlPath, body) {
  const ts = Math.floor(Date.now() / 1000)
  const ns = nonce()
  const message = `${method}\n${urlPath}\n${ts}\n${ns}\n${body}\n`
  const signer = crypto.createSign('RSA-SHA256')
  signer.update(message)
  const signature = signer.sign(merchantKey(config)).toString('base64')
  return `WECHATPAY2-SHA256-RSA2048 mchid="${config.mchId}",nonce_str="${ns}",signature="${signature}",timestamp="${ts}",serial_no="${config.merchantSerialNo}"`
}

/** H5 下单：返回 h5_url（微信外浏览器打开后拉起微信支付） */
export async function h5PayUrl(config, { outTradeNo, amountFen, description, notifyUrl, clientIp }) {
  const urlPath = '/v3/pay/transactions/h5'
  const body = JSON.stringify({
    appid: config.appId,
    mchid: config.mchId,
    description,
    out_trade_no: outTradeNo,
    notify_url: notifyUrl,
    amount: { total: amountFen, currency: 'CNY' },
    scene_info: { payer_client_ip: clientIp || '127.0.0.1' }
  })
  const response = await fetch(GATEWAY + urlPath, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'User-Agent': 'lianai-admin',
      Authorization: authorization(config, 'POST', urlPath, body)
    },
    body
  })
  const text = await response.text()
  if (!response.ok) {
    let detail = text
    try {
      const json = JSON.parse(text)
      detail = json.message || text
    } catch { /* keep raw */ }
    throw new Error(`微信下单失败：${detail}`)
  }
  const json = JSON.parse(text)
  if (!json.h5_url) throw new Error('微信下单成功但未返回 h5_url')
  return json.h5_url
}

/** 校验回调签名（公钥模式：Wechatpay-Signature 用平台公钥验） */
export function verifyCallbackSignature(config, headers, rawBody) {
  try {
    const timestamp = String(headers['wechatpay-timestamp'] || '')
    const wechatNonce = String(headers['wechatpay-nonce'] || '')
    const signature = String(headers['wechatpay-signature'] || '')
    if (!timestamp || !wechatNonce || !signature) return false
    const verifier = crypto.createVerify('RSA-SHA256')
    verifier.update(`${timestamp}\n${wechatNonce}\n${rawBody}\n`)
    return verifier.verify(platformKey(config), Buffer.from(signature, 'base64'))
  } catch {
    return false
  }
}

/** 解密回调 resource（AES-256-GCM，密钥为 APIv3 key） */
export function decryptResource(apiV3Key, resource) {
  const key = Buffer.from(String(apiV3Key), 'utf8')
  const ciphertext = Buffer.from(resource.ciphertext, 'base64')
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(resource.nonce, 'utf8'))
  decipher.setAuthTag(ciphertext.subarray(ciphertext.length - 16))
  if (resource.associated_data) decipher.setAAD(Buffer.from(resource.associated_data, 'utf8'))
  const plain = Buffer.concat([decipher.update(ciphertext.subarray(0, ciphertext.length - 16)), decipher.final()])
  return JSON.parse(plain.toString('utf8'))
}
