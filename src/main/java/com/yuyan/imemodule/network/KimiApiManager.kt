package com.yuyan.imemodule.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import java.util.UUID

/**
 * KIMI API管理器
 * 采用后端管理方式处理API密钥和用户配额
 */
class KimiApiManager private constructor(context: Context) {
    private val TAG = "KimiApiManager"

    private val context: Context

    // SharedPreferences用于存储授权码和使用数据
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREF_NAME, Context.MODE_PRIVATE
    )

    // OkHttp客户端用于与后端通信
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Gson用于JSON序列化
    private val gson = Gson()

    // 后端服务地址
    private val serverUrl = "http://119.3.217.132:7999"

    // 当前授权码
    private var currentAuthCode: String
        get() = sharedPreferences.getString(KEY_AUTH_CODE, "") ?: ""
        set(value) {
            sharedPreferences.edit().putString(KEY_AUTH_CODE, value).apply()
        }

    // 授权码类型
    private var authCodeType: String
        get() = sharedPreferences.getString(KEY_AUTH_CODE_TYPE, "device") ?: "device"
        set(value) {
            sharedPreferences.edit().putString(KEY_AUTH_CODE_TYPE, value).apply()
        }

    // 设备ID，用于绑定授权码
    private val deviceId: String
        get() {
            var id = sharedPreferences.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                // 使用UUID生成唯一标识
                id = UUID.randomUUID().toString()
                // 保存设备ID
                sharedPreferences.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    // 缓存的剩余使用次数
    private val cachedRemainingCount = AtomicInteger(-1)

    // 是否已经初始化
    private var initialized = false

    // 授权状态监听器
    private var authStateListener: ((Boolean, String) -> Unit)? = null

    init {
        this.context = context.applicationContext
        Log.d(TAG, "初始化KIMI API管理器")
        // 检查是否有保存的授权码，有则尝试验证
        val savedCode = currentAuthCode
        if (savedCode.isNotEmpty()) {
            thread {
                checkAuthCodeValidity(savedCode)
            }
        }
    }

    /**
     * 设置授权状态变化监听器
     */
    fun setAuthStateListener(listener: (Boolean, String) -> Unit) {
        this.authStateListener = listener

        // 如果已有授权码，立即通知当前状态
        if (currentAuthCode.isNotEmpty()) {
            val remaining = getRemainingUsageCount()
            listener(remaining > 0, "当前授权码剩余使用次数: $remaining")
        } else {
            listener(false, "未授权")
        }
    }

    /**
     * 激活授权码
     * @param authCode 要激活的授权码
     * @param callback 回调函数，返回激活结果
     */
    fun activateAuthorizationCode(authCode: String, callback: (Boolean, String) -> Unit) {
        if (authCode.isBlank()) {
            callback(false, "授权码不能为空")
            return
        }

        thread {
            try {
                // 构造请求体
                val requestJson = gson.toJson(mapOf(
                    "auth_code" to authCode,
                    "device_id" to deviceId
                ))

                val requestBody = requestJson.toRequestBody("application/json".toMediaTypeOrNull())

                // 构造请求
                val request = Request.Builder()
                    .url("$serverUrl/api/v1/authorize")
                    .post(requestBody)
                    .build()

                // 发送请求
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseJson = response.body?.string() ?: "{}"
                    val authResult = gson.fromJson(responseJson, AuthorizeResponse::class.java)

                    if (authResult.success) {
                        // 保存授权码和类型
                        currentAuthCode = authCode
                        authCodeType = authResult.type ?: "device"  // 保存授权码类型
                        cachedRemainingCount.set(authResult.remaining)

                        // 通知授权状态改变
                        authStateListener?.invoke(true, "授权成功，剩余使用次数: ${authResult.remaining}")

                        callback(true, "授权成功，剩余使用次数: ${authResult.remaining}")
                    } else {
                        // 授权失败
                        callback(false, authResult.message ?: "授权失败")

                        // 通知授权状态改变
                        authStateListener?.invoke(false, authResult.message ?: "授权失败")
                    }
                } else {
                    // 请求失败
                    val errorBody = response.body?.string() ?: "未知错误"
                    callback(false, "服务器响应错误: $errorBody")

                    // 通知授权状态改变
                    authStateListener?.invoke(false, "服务器响应错误")
                }
            } catch (e: Exception) {
                Log.e(TAG, "激活授权码失败", e)
                callback(false, "网络错误: ${e.message}")

                // 通知授权状态改变
                authStateListener?.invoke(false, "网络错误")
            }
        }
    }

    /**
     * 检查授权码有效性
     */
    private fun checkAuthCodeValidity(authCode: String): Boolean {
        try {
            // 构造请求
            val request = Request.Builder()
                .url("$serverUrl/api/v1/usage?auth_code=$authCode&device_id=$deviceId")
                .get()
                .build()

            // 发送请求
            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseJson = response.body?.string() ?: "{}"
                val usageResult = gson.fromJson(responseJson, UsageResponse::class.java)

                if (usageResult.success) {
                    // 更新授权码类型
                    authCodeType = usageResult.type ?: "device"
                    // 更新缓存的剩余次数
                    cachedRemainingCount.set(usageResult.remaining)
                    initialized = true
                    return usageResult.remaining > 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查授权码有效性失败", e)
        }

        return false
    }

    /**
     * 获取剩余使用次数
     * @return 剩余可用的调用次数
     */
    fun getRemainingUsageCount(): Int {
        // 如果有缓存的值且已初始化，直接返回
        if (initialized && cachedRemainingCount.get() >= 0) {
            return cachedRemainingCount.get()
        }

        val authCode = currentAuthCode
        if (authCode.isEmpty()) {
            return 0
        }

        try {
            // 构造请求
            val request = Request.Builder()
                .url("$serverUrl/api/v1/usage?auth_code=$authCode&device_id=$deviceId")
                .get()
                .build()

            // 发送请求
            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseJson = response.body?.string() ?: "{}"
                val usageResult = gson.fromJson(responseJson, UsageResponse::class.java)

                if (usageResult.success) {
                    // 更新授权码类型
                    authCodeType = usageResult.type ?: "device"
                    // 更新缓存的剩余次数
                    cachedRemainingCount.set(usageResult.remaining)
                    initialized = true
                    return usageResult.remaining
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取剩余使用次数失败", e)
        }

        // 如果请求失败，但有缓存值，返回缓存值
        val cached = cachedRemainingCount.get()
        if (cached >= 0) {
            return cached
        }

        return 0
    }

    /**
     * 调用KIMI API
     * @param model 模型名称
     * @param messages 消息列表
     * @param temperature 温度参数
     * @param callback 回调函数，返回API响应
     */
    fun callKimiApi(
        model: String,
        messages: List<Map<String, String>>,
        temperature: Float,
        callback: (Boolean, String, Map<String, Any>?) -> Unit
    ) {
        val authCode = currentAuthCode
        if (authCode.isEmpty()) {
            callback(false, "未授权，请先激活授权码", null)
            return
        }

        thread {
            try {
                // 构造请求体
                val requestJson = gson.toJson(mapOf(
                    "auth_code" to authCode,
                    "device_id" to deviceId,
                    "model" to model,
                    "messages" to messages,
                    "temperature" to temperature
                ))

                val requestBody = requestJson.toRequestBody("application/json".toMediaTypeOrNull())

                // 构造请求
                val request = Request.Builder()
                    .url("$serverUrl/api/v1/complete")
                    .post(requestBody)
                    .build()

                // 发送请求
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseJson = response.body?.string() ?: "{}"
                    val apiResult = gson.fromJson(responseJson, ApiResponse::class.java)

                    if (apiResult.success) {
                        // 更新缓存的剩余次数
                        cachedRemainingCount.set(apiResult.remaining)

                        // 更新授权码类型
                        if (apiResult.type != null) {
                            authCodeType = apiResult.type
                        }

                        // 返回API响应
                        callback(true, "API调用成功", apiResult.response)
                    } else {
                        // API调用失败
                        callback(false, apiResult.message ?: "API调用失败", null)
                    }
                } else {
                    // 请求失败
                    val errorBody = response.body?.string() ?: "未知错误"
                    callback(false, "服务器响应错误: $errorBody", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "API调用失败", e)
                callback(false, "网络错误: ${e.message}", null)
            }
        }
    }

    /**
     * 记录API使用
     * 由于使用后端管理，此方法不再需要手动调用
     */
    fun recordApiUsage() {
        // 不再需要手动记录，后端已自动记录
        // 仅更新缓存的剩余次数
        val cached = cachedRemainingCount.get()
        if (cached > 0) {
            cachedRemainingCount.decrementAndGet()
        }
    }

    /**
     * 是否已授权
     */
    fun isAuthorized(): Boolean {
        return currentAuthCode.isNotEmpty() && getRemainingUsageCount() > 0
    }

    /**
     * 清除授权信息
     */
    fun clearAuthorization() {
        currentAuthCode = ""
        cachedRemainingCount.set(0)

        // 通知授权状态改变
        authStateListener?.invoke(false, "已清除授权")
    }

    /**
     * 获取授权码类型
     * @return 授权码类型 "device"或"balance"
     */
    fun getAuthCodeType(): String {
        return authCodeType
    }

    // 用于解析授权响应的数据类
    private data class AuthorizeResponse(
        val success: Boolean,
        @SerializedName("auth_code") val authCode: String = "",
        @SerializedName("max_usage") val maxUsage: Int = 0,
        @SerializedName("usage_count") val usageCount: Int = 0,
        val used: Int = 0,
        val remaining: Int = 0,
        val type: String? = "device",
        val message: String? = null
    )

    // 用于解析使用情况响应的数据类
    private data class UsageResponse(
        val success: Boolean,
        @SerializedName("auth_code") val authCode: String = "",
        @SerializedName("max_usage") val maxUsage: Int = 0,
        @SerializedName("usage_count") val usageCount: Int = 0,
        val used: Int = 0,
        val remaining: Int = 0,
        val type: String? = "device",
        val message: String? = null
    )

    // 用于解析API调用响应的数据类
    private data class ApiResponse(
        val success: Boolean,
        val response: Map<String, Any>? = null,
        val remaining: Int = 0,
        val type: String? = null,
        val message: String? = null
    )

    companion object {
        // SharedPreferences常量
        private const val PREF_NAME = "kimi_api_manager_prefs"
        private const val KEY_AUTH_CODE = "auth_code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_AUTH_CODE_TYPE = "auth_code_type"

        @Volatile
        private var instance: KimiApiManager? = null

        /**
         * 获取KimiApiManager实例
         * @param context 上下文
         * @return KimiApiManager实例
         */
        fun getInstance(context: Context): KimiApiManager {
            return instance ?: synchronized(this) {
                instance ?: KimiApiManager(context.applicationContext).also { instance = it }
            }
        }
    }
}