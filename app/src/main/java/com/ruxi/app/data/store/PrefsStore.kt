package com.ruxi.app.data.store

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * 极简的「对象 <-> SharedPreferences」持久化。
 *
 * ## 为什么不用 Room / DataStore
 * - Room：本项目数据量极小（几十张角色卡、几千条消息），上数据库纯属负担，
 *   且 schema 迁移在只有一两个人的项目里维护成本远大于收益
 * - DataStore：它的优势是「异步 + 一致性」，但这里所有读写都在主线程外的小数据量场景，
 *   SharedPreferences 的 apply() 已经够用，且能同步读（冷启动少一层 await）
 *
 * 所以：SharedPreferences 存 JSON 字符串，够用就好。
 *
 * ## 容错
 * [read] 在反序列化失败时返回默认值而不是抛异常 —— 旧版本存的 JSON
 * 遇到新版本新增的非空字段会解析失败，此时宁可丢一份配置也不能让 App 起不来。
 * 配合 Json 的 ignoreUnknownKeys + coerceInputValues，绝大多数字段变更都能平滑过渡。
 */
class PrefsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ruxi_store", Context.MODE_PRIVATE)

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun <T> read(key: String, serializer: KSerializer<T>, default: T): T {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(default)
    }

    fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        runCatching {
            prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()
        }
    }

    // ── 纯字符串读写 ──
    //
    // 不走 kotlinx.serialization：`String.serializer()` 在 Kotlin 2.1 上
    // 无法从 `kotlinx.serialization.builtins.serializer` 解析出来（实测 CI 报
    // "Unresolved reference 'serializer'"），而且它失败时会把泛型 T 推断成
    // String?，让调用处报一堆莫名其妙的 nullable 错误。
    // SharedPreferences 本来就原生支持字符串，没必要绕一圈。
    fun readString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun writeString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val KEY_CHARACTERS = "characters"
        const val KEY_PROVIDERS = "providers"
        const val KEY_SESSIONS = "sessions"
        const val KEY_ACTIVE_PROVIDER = "active_provider"
        const val KEY_UI_SETTINGS = "ui_settings"
    }
}
