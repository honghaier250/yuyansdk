package com.yuyan.imemodule.network

import android.content.Context
import android.util.Log
import com.yuyan.imemodule.network.model.KimiChatRequest
import com.yuyan.imemodule.network.model.KimiChatResponse
import com.yuyan.imemodule.network.model.KimiMessage
import com.yuyan.imemodule.network.model.KimiResult
import java.util.concurrent.TimeUnit

/**
 * KIMI API客户端
 * 用于调用KIMI大模型API
 */
class KimiApiClient private constructor() {
    private val TAG = "KimiApiClient"

    // API基础URL - 实际调用由KimiApiManager负责
    private val BASE_URL = "https://api.moonshot.cn/"

    // API管理器实例
    private var apiManager: KimiApiManager? = null

    /**
     * 设置API管理器
     */
    fun setApiManager(manager: KimiApiManager) {
        this.apiManager = manager
    }

    /**
     * 调用KIMI API优化文本表达
     * @param inputText 用户输入的文本
     * @param expressionType 表达类型（如"正式/商务体"、"口语化/朋友体"等）
     * @param callback 回调函数，返回优化后的文本列表
     * @param customSystemPrompt 自定义系统提示词，如果为null则使用默认提示词
     * @param customUserMessage 自定义用户消息，如果为null则使用默认消息
     */
    fun optimizeExpression(
        inputText: String,
        expressionType: String,
        callback: (KimiResult) -> Unit,
        customSystemPrompt: String? = null,
        customUserMessage: String? = null
    ) {
        Log.d(TAG, "开始优化表达, 类型: $expressionType, 文本: $inputText")

        // 检查API管理器是否已设置
        if (apiManager == null) {
            Log.e(TAG, "API管理器未设置")
            callback(KimiResult(false, errorMessage = "API管理器未设置"))
            return
        }

        // 检查是否已授权
        if (!apiManager!!.isAuthorized()) {
            Log.e(TAG, "未授权，请先激活授权码")
            callback(KimiResult(false, errorMessage = "未授权，请先激活授权码"))
            return
        }

        // 检查是否还有剩余使用次数
        val remainingCount = apiManager!!.getRemainingUsageCount()
        
        // 计算这次调用需要的使用次数（根据模型不同可能消耗不同次数）
        val usageCost = if (model == "moonshot-v1-32k") 2 else 1
        
        if (remainingCount < usageCost) {
            // 区分授权码类型，给出不同提示
            val authType = apiManager!!.getAuthCodeType()
            val errorMessage = if (authType == "balance") {
                "您的授权码余额不足，需要$usageCost点额度，请联系管理员获取更多使用额度"
            } else {
                "您的API使用次数已达上限，请联系管理员获取更多使用额度"
            }
            
            Log.e(TAG, "API使用次数不足")
            callback(KimiResult(false, errorMessage = errorMessage))
            return
        }

        // 检查输入文本是否为空
        if (inputText.isBlank()) {
            Log.e(TAG, "输入文本为空")
            callback(KimiResult(false, errorMessage = "输入文本不能为空"))
            return
        }

        try {
            // 清理输入文本，去除多余的空白字符
            val cleanedText = inputText.trim()
            Log.d(TAG, "清理后的输入文本: $cleanedText")

            // 构建系统提示词
            val systemPrompt = customSystemPrompt ?: "你是一个专业的文本优化助手，请根据用户的输入，生成5种不同的'$expressionType'风格的表达方式。" +
            "请直接给出优化后的5个表达，每个表达一行，不要有编号，不要有任何解释和额外文字。" +
            "确保优化后的表达保持原意，但更符合'$expressionType'的风格。" +
            "请注意：不要生成与用户输入无关的内容，必须基于用户的原始输入进行优化。"

            // 构建用户消息
            val userMessage = customUserMessage ?: "请优化以下文本，保持原意但使用'$expressionType'风格：$cleanedText"

            Log.d(TAG, "系统提示词: $systemPrompt")
            Log.d(TAG, "用户消息: $userMessage")

            // 构建消息列表
            val messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage)
            )

            // 调用API管理器
            apiManager!!.callKimiApi(
                model = "moonshot-v1-8k",
                messages = messages,
                temperature = 0.7f,
                callback = { success, message, response ->
                    if (success && response != null) {
                        try {
                            // 解析API响应
                            @Suppress("UNCHECKED_CAST")
                            val choices = response["choices"] as? List<Map<String, Any>>

                            if (choices != null && choices.isNotEmpty()) {
                                // 获取助手的回复
                                @Suppress("UNCHECKED_CAST")
                                val messageObj = choices[0]["message"] as? Map<String, Any>
                                val assistantMessage = messageObj?.get("content") as? String

                                if (assistantMessage != null) {
                                    Log.d(TAG, "助手回复: $assistantMessage")

                                    // 解析回复，提取5个优化表达
                                    val expressions = assistantMessage.trim().split("\n")
                                        .filter { it.isNotBlank() }
                                        .take(5) // 确保最多只取5个

                                    // 检查是否获取到了表达
                                    if (expressions.isEmpty()) {
                                        Log.e(TAG, "未能提取到有效表达")
                                        callback(KimiResult(false, errorMessage = "未能生成有效的优化表达"))
                                        return@callKimiApi
                                    }

                                    Log.d(TAG, "提取的表达: $expressions")
                                    callback(KimiResult(true, data = expressions))
                                } else {
                                    Log.e(TAG, "无法获取助手回复内容")
                                    callback(KimiResult(false, errorMessage = "无法获取助手回复内容"))
                                }
                            } else {
                                Log.e(TAG, "API响应格式不正确")
                                callback(KimiResult(false, errorMessage = "API响应格式不正确"))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析API响应失败", e)
                            callback(KimiResult(false, errorMessage = "解析API响应失败: ${e.message}"))
                        }
                    } else {
                        Log.e(TAG, "API调用失败: $message")
                        callback(KimiResult(false, errorMessage = message))
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "优化表达过程中发生异常", e)
            callback(KimiResult(false, errorMessage = "优化表达过程中发生异常: ${e.message}"))
        }
    }

    /**
     * 翻译文本
     * @param inputText 要翻译的文本
     * @param targetLanguage 目标语言
     * @param callback 回调函数，返回翻译结果
     */
    fun translateText(inputText: String, targetLanguage: String, callback: (KimiResult) -> Unit) {
        Log.d(TAG, "开始翻译文本为$targetLanguage: $inputText")

        // 检查API管理器是否已设置
        if (apiManager == null) {
            Log.e(TAG, "API管理器未设置")
            callback(KimiResult(false, errorMessage = "API管理器未设置"))
            return
        }

        // 检查是否已授权
        if (!apiManager!!.isAuthorized()) {
            Log.e(TAG, "未授权，请先激活授权码")
            callback(KimiResult(false, errorMessage = "未授权，请先激活授权码"))
            return
        }

        try {
            // 构建系统提示词
            val systemPrompt = "你是一个专业的翻译助手，请将用户的输入准确翻译成${targetLanguage}。" +
                    "请直接给出5种不同的翻译结果，每个翻译一行，不要有编号，不要有任何解释和额外文字。" +
                    "确保翻译准确表达原文的意思，符合${targetLanguage}的语言习惯。"

            // 构建用户消息
            val userMessage = "请将以下文本翻译成${targetLanguage}：$inputText"

            // 构建消息列表
            val messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage)
            )

            // 调用API管理器
            apiManager!!.callKimiApi(
                model = "moonshot-v1-8k",
                messages = messages,
                temperature = 0.7f,
                callback = { success, message, response ->
                    if (success && response != null) {
                        try {
                            // 解析API响应
                            @Suppress("UNCHECKED_CAST")
                            val choices = response["choices"] as? List<Map<String, Any>>

                            if (choices != null && choices.isNotEmpty()) {
                                // 获取助手的回复
                                @Suppress("UNCHECKED_CAST")
                                val messageObj = choices[0]["message"] as? Map<String, Any>
                                val assistantMessage = messageObj?.get("content") as? String

                                if (assistantMessage != null) {
                                    Log.d(TAG, "翻译助手回复: $assistantMessage")

                                    // 解析回复，提取翻译结果
                                    val translations = assistantMessage.trim().split("\n")
                                        .filter { it.isNotBlank() }
                                        .take(5) // 确保最多只取5个

                                    if (translations.isEmpty()) {
                                        Log.e(TAG, "未能提取到有效翻译")
                                        callback(KimiResult(false, errorMessage = "未能生成有效的翻译"))
                                        return@callKimiApi
                                    }

                                    Log.d(TAG, "提取的翻译: $translations")
                                    callback(KimiResult(true, data = translations))
                                } else {
                                    Log.e(TAG, "无法获取翻译助手回复内容")
                                    callback(KimiResult(false, errorMessage = "无法获取翻译助手回复内容"))
                                }
                            } else {
                                Log.e(TAG, "API响应格式不正确")
                                callback(KimiResult(false, errorMessage = "API响应格式不正确"))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析API响应失败", e)
                            callback(KimiResult(false, errorMessage = "解析API响应失败: ${e.message}"))
                        }
                    } else {
                        Log.e(TAG, "API调用失败: $message")
                        callback(KimiResult(false, errorMessage = message))
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "翻译文本过程中发生异常", e)
            callback(KimiResult(false, errorMessage = "翻译文本过程中发生异常: ${e.message}"))
        }
    }

    /**
     * 初始化API客户端
     * @param context 上下文
     */
    fun init(context: Context) {
        Log.d(TAG, "初始化KimiApiClient")
        try {
            // 初始化API管理器
            apiManager = KimiApiManager.getInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "初始化API管理器失败", e)
        }
    }

    /**
     * 获取剩余使用次数
     */
    fun getRemainingUsageCount(): Int {
        return apiManager?.getRemainingUsageCount() ?: 0
    }

    /**
     * 是否已授权
     */
    fun isAuthorized(): Boolean {
        return apiManager?.isAuthorized() ?: false
    }

    /**
     * 设置授权状态监听器
     */
    fun setAuthStateListener(listener: (Boolean, String) -> Unit) {
        apiManager?.setAuthStateListener(listener)
    }

    /**
     * 激活授权码
     */
    fun activateAuthorizationCode(authCode: String, callback: (Boolean, String) -> Unit) {
        apiManager?.activateAuthorizationCode(authCode, callback)
    }

    /**
     * 获取授权码类型
     * @return 授权码类型名称，"device"表示设备限制型，"balance"表示余额式
     */
    fun getAuthCodeType(): String {
        return apiManager?.getAuthCodeType() ?: "device"
    }

    /**
     * 获取授权状态信息，包括类型和剩余次数
     * @return 包含授权状态信息的Map
     */
    fun getAuthStatusInfo(): Map<String, Any> {
        val isAuth = isAuthorized()
        val remaining = getRemainingUsageCount()
        val type = getAuthCodeType()
        
        return mapOf(
            "isAuthorized" to isAuth,
            "remaining" to remaining,
            "type" to type,
            "typeDescription" to when(type) {
                "balance" -> "余额式授权码"
                else -> "设备限制型授权码"
            }
        )
    }

    companion object {
        @Volatile
        private var instance: KimiApiClient? = null

        fun getInstance(): KimiApiClient {
            return instance ?: synchronized(this) {
                instance ?: KimiApiClient().also { instance = it }
            }
        }
    }
} 