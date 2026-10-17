import crypto from 'node:crypto'
import { AlipaySdk } from 'alipay-sdk'

function pem(value, label) {
  const text = String(value || '').trim()
  if (text.startsWith('-----BEGIN ')) return text
  const body = text.replace(/\s/g, '')
  return `-----BEGIN ${label}-----\n${(body.match(/.{1,64}/g) || []).join('\n')}\n-----END ${label}-----\n`
}

// Normalize both PKCS1/PKCS8 PEM inputs; the SDK always receives PKCS8.
function sdk(config) {
  const privateKey = crypto.createPrivateKey(pem(config.privateKey, 'PRIVATE KEY'))
    .export({ type: 'pkcs8', format: 'pem' })
  const alipayPublicKey = crypto.createPublicKey(pem(config.alipayPublicKey, 'PUBLIC KEY'))
    .export({ type: 'spki', format: 'pem' })
  return new AlipaySdk({
    appId: config.appId, privateKey, alipayPublicKey, keyType: 'PKCS8',
    signType: 'RSA2', gateway: config.gatewayUrl, timeout: 10000
  })
}

export function yuanToFen(value) {
  const match = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(String(value ?? ''))
  if (!match) return null
  const fen = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'))
  return Number.isSafeInteger(fen) ? fen : null
}

// Website/mobile application uses the mobile website payment product.
// Generating this URL neither probes nor creates a second provider order.
export function buildPaymentUrl(config, { outTradeNo, amountFen, subject, notifyUrl, returnUrl }) {
  return sdk(config).pageExecute('alipay.trade.wap.pay', 'GET', {
    notifyUrl, returnUrl,
    bizContent: {
      outTradeNo, totalAmount: (amountFen / 100).toFixed(2), subject,
      productCode: 'QUICK_WAP_WAY', timeoutExpress: '15m', quitUrl: returnUrl
    }
  })
}

export function verifyNotifySign(params, config) {
  try {
    if (params.sign_type !== 'RSA2') return false
    // Express already decoded the form values. Official SDK handles both
    // notification signing conventions without decoding a second time.
    return sdk(config).checkNotifySignV2(params)
  } catch {
    return false
  }
}

export async function queryTrade(config, outTradeNo) {
  let result
  try {
    result = await sdk(config).exec('alipay.trade.query', {
      bizContent: { outTradeNo }
    }, { validateSign: true })
  } catch {
    // SDK errors can contain request/response details; never forward those.
    throw Object.assign(new Error('支付宝查单失败，请确认应用已上线、支付产品已开通且密钥匹配'), { status: 502 })
  }
  if (result.subCode === 'ACQ.TRADE_NOT_EXIST') return null
  if (result.code !== '10000') {
    const message = result.subCode === 'isv.not-online-app'
      ? '支付宝应用尚未上线，请等待审核通过后再付款'
      : result.subCode === 'isv.insufficient-isv-permissions'
        ? '支付宝手机网站支付产品未开通，请联系管理员'
        : '支付宝暂时无法确认订单，请稍后重试'
    throw Object.assign(new Error(message), { status: 502 })
  }
  return {
    out_trade_no: result.outTradeNo, trade_no: result.tradeNo,
    trade_status: result.tradeStatus, total_amount: result.totalAmount,
    seller_id: result.sellerId
  }
}
