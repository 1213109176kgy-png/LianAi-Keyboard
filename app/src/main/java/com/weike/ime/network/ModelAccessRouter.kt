package com.weike.ime.network

import com.weike.ime.data.CloudApiSettings
import com.weike.ime.data.ModelEndpointConfig

enum class ModelFeature { REPLY, SUPER_SPEAK, VISION }
class ModelSetupRequiredException(message: String) : IllegalStateException(message)
data class ModelRoute(val official: Boolean, val endpoint: ModelEndpointConfig)

object ModelRoutingPolicy {
    fun featureConfig(config: AppRuntimeConfig, feature: ModelFeature) = when (feature) {
        ModelFeature.REPLY -> config.replySuggestions
        ModelFeature.SUPER_SPEAK -> config.superSpeak
        ModelFeature.VISION -> config.visionReply
    }
    fun official(config: AppRuntimeConfig?, feature: ModelFeature, now: Long = System.currentTimeMillis()): Boolean =
        config?.user?.isVip(now) == true && featureConfig(config, feature).enabled

    fun select(config: AppRuntimeConfig?, feature: ModelFeature, local: ModelEndpointConfig, token: String?, now: Long = System.currentTimeMillis()): ModelRoute {
        if (!token.isNullOrBlank() && official(config, feature, now)) {
            val path = when (feature) {
                ModelFeature.REPLY -> "/text/reply"
                ModelFeature.SUPER_SPEAK -> "/text/polish"
                ModelFeature.VISION -> "/vision"
            }
            return ModelRoute(true, ModelEndpointConfig(AppConfigurationClient.BASE + path, token, featureConfig(config!!, feature).model))
        }
        if (local.isComplete()) return ModelRoute(false, local)
        val name = when (feature) {
            ModelFeature.VISION -> "视觉"
            else -> "文本"
        }
        throw ModelSetupRequiredException(if (config?.user?.isVip(now) == true)
            "该功能尚未配置官方模型，请在 App「模型配置」中配置自己的${name}模型参数"
            else "请开通 VIP，或在 App「模型配置」中配置自己的${name}模型参数")
    }
}

class ModelAccessRouter(
    private val configuration: AppConfigurationClient,
    private val tokenProvider: () -> String?,
    private val settingsProvider: () -> CloudApiSettings
) {
    suspend fun route(feature: ModelFeature): ModelRoute {
        val token = tokenProvider()
        val config = if (token.isNullOrBlank()) null else configuration.refresh().getOrElse {
            throw IllegalStateException("暂时无法获取会员与业务配置，请检查网络后重试", it)
        }
        check(token == tokenProvider()) { "登录账号已切换，请重试" }
        val settings = settingsProvider()
        val local = when (feature) {
            ModelFeature.VISION -> settings.vision
            ModelFeature.REPLY -> settings.reply
            ModelFeature.SUPER_SPEAK -> settings.superSpeak
        }
        return ModelRoutingPolicy.select(config, feature, local, token)
    }
}
