package com.ruxi.app.data.store

import android.content.Context
import com.ruxi.app.data.model.CharacterCard
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Provider
import com.ruxi.app.data.model.Role
import com.ruxi.app.data.model.Session
import com.ruxi.app.data.model.UiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * 全局数据仓库（单例）。
 *
 * ## 设计取舍
 * - 状态用 [StateFlow] 暴露，UI 直接 collect。数据量小，全量替换即可，
 *   不做细粒度的 diff —— 那点性能收益抵不上复杂度。
 * - 写入即落盘（[PrefsStore.write]）。没有「未保存的草稿」概念，
 *   避免用户在编辑角色卡时被来电打断、回来发现改动没了。
 * - 会话数据可能较大（几千条消息），但 SharedPreferences 存 JSON 在这个量级
 *   （几十 KB ~ 几百 KB）仍然很快，不必提前优化。
 */
class AppStore private constructor(context: Context) {

    private val prefs = PrefsStore(context)
    private val imageStore = ImageStore(context)
    private val appContext = context.applicationContext

    // ─────────────── 角色卡 ───────────────
    private val _characters = MutableStateFlow(
        prefs.read(PrefsStore.KEY_CHARACTERS, ListSerializer(CharacterCard.serializer()), emptyList())
    )
    val characters: StateFlow<List<CharacterCard>> = _characters.asStateFlow()

    // ─────────────── 供应商 ───────────────
    private val _providers = MutableStateFlow(
        prefs.read(PrefsStore.KEY_PROVIDERS, ListSerializer(Provider.serializer()), emptyList())
    )
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _activeProviderId = MutableStateFlow(
        prefs.readString(PrefsStore.KEY_ACTIVE_PROVIDER, "")
    )
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    // ─────────────── 会话 ───────────────
    private val _sessions = MutableStateFlow(
        prefs.read(PrefsStore.KEY_SESSIONS, ListSerializer(Session.serializer()), emptyList())
    )
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    // ─────────────── 设置 ───────────────
    private val _uiSettings = MutableStateFlow(
        prefs.read(PrefsStore.KEY_UI_SETTINGS, UiSettings.serializer(), UiSettings())
    )
    val uiSettings: StateFlow<UiSettings> = _uiSettings.asStateFlow()

    // ═══════════════ 角色卡操作 ═══════════════

    fun upsertCharacter(card: CharacterCard) {
        val now = System.currentTimeMillis()
        val updated = card.copy(updatedAt = now)
        val list = _characters.value.toMutableList()
        val idx = list.indexOfFirst { it.id == card.id }
        if (idx >= 0) list[idx] = updated else list.add(0, updated)
        _characters.value = list
        prefs.write(PrefsStore.KEY_CHARACTERS, ListSerializer(CharacterCard.serializer()), list)
    }

    fun deleteCharacter(id: String) {
        // 连带清理：该角色的所有会话 + 图片文件
        val card = _characters.value.firstOrNull { it.id == id }
        card?.images?.forEach { imageStore.delete(it.path) }
        _sessions.value.filter { it.characterId == id }.forEach { s ->
            s.messages.forEach { m -> m.images.forEach { imageStore.delete(it.path) } }
        }
        _characters.value = _characters.value.filterNot { it.id == id }
        prefs.write(
            PrefsStore.KEY_CHARACTERS,
            ListSerializer(CharacterCard.serializer()),
            _characters.value
        )

        val remaining = _sessions.value.filterNot { it.characterId == id }
        _sessions.value = remaining
        prefs.write(PrefsStore.KEY_SESSIONS, ListSerializer(Session.serializer()), remaining)
    }

    fun characterById(id: String): CharacterCard? = _characters.value.firstOrNull { it.id == id }

    // ═══════════════ 供应商操作 ═══════════════

    fun upsertProvider(provider: Provider) {
        val list = _providers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == provider.id }
        if (idx >= 0) list[idx] = provider else list.add(list.size, provider)
        _providers.value = list
        prefs.write(PrefsStore.KEY_PROVIDERS, ListSerializer(Provider.serializer()), list)

        // 第一个供应商自动设为当前
        if (_activeProviderId.value.isBlank()) setActiveProvider(provider.id)
    }

    fun deleteProvider(id: String) {
        _providers.value = _providers.value.filterNot { it.id == id }
        prefs.write(
            PrefsStore.KEY_PROVIDERS,
            ListSerializer(Provider.serializer()),
            _providers.value
        )
        if (_activeProviderId.value == id) {
            setActiveProvider(_providers.value.firstOrNull()?.id ?: "")
        }
    }

    fun setActiveProvider(id: String) {
        _activeProviderId.value = id
        prefs.writeString(PrefsStore.KEY_ACTIVE_PROVIDER, id)
    }

    fun activeProvider(): Provider? =
        _providers.value.firstOrNull { it.id == _activeProviderId.value }

    // ═══════════════ 会话操作 ═══════════════

    fun sessionsOf(characterId: String): List<Session> =
        _sessions.value.filter { it.characterId == characterId }
            .sortedByDescending { it.updatedAt }

    fun sessionById(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    fun upsertSession(session: Session) {
        val updated = session.copy(updatedAt = System.currentTimeMillis())
        val list = _sessions.value.toMutableList()
        val idx = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = updated else list.add(0, updated)
        _sessions.value = list
        prefs.write(PrefsStore.KEY_SESSIONS, ListSerializer(Session.serializer()), list)
    }

    /** 追加一条消息并落盘（流式过程中不调用，等消息完整后再存） */
    fun appendMessage(sessionId: String, message: ChatMessage) {
        val session = sessionById(sessionId) ?: return
        upsertSession(session.copy(messages = session.messages + message))
    }

    /** 替换最后一条消息（流式结束后把临时消息换成最终版本） */
    fun replaceLastMessage(sessionId: String, message: ChatMessage) {
        val session = sessionById(sessionId) ?: return
        val msgs = session.messages.toMutableList()
        if (msgs.isEmpty()) return
        msgs[msgs.lastIndex] = message
        upsertSession(session.copy(messages = msgs))
    }

    /**
     * 给某条消息追加一个新版本，并切到它。
     *
     * 编辑消息、重新生成回复都走这里 —— **旧内容不删，作为同组的另一个版本保留**。
     * 新版本的 id 是新生成的，[ChatMessage.groupId] 沿用原组的。
     */
    fun addVersion(
        sessionId: String,
        messageId: String,
        content: String,
        reasoning: String = "",
    ) {
        val session = sessionById(sessionId) ?: return
        val target = session.messages.firstOrNull { it.id == messageId } ?: return
        val group = target.group
        val maxIdx = session.messages
            .filter { it.group == group }
            .maxOfOrNull { it.versionIndex } ?: 0
        val newMsg = target.copy(
            id = UUID.randomUUID().toString(),
            content = content,
            reasoning = reasoning,
            timestamp = System.currentTimeMillis(),
            groupId = group,
            versionIndex = maxIdx + 1,
            incomplete = false,
        )
        upsertSession(
            session.copy(
                messages = session.messages + newMsg,
                activeVersion = session.activeVersion + (group to newMsg.id),
            )
        )
    }

    /** 切换某组显示的版本 */
    fun selectVersion(sessionId: String, groupId: String, messageId: String) {
        val session = sessionById(sessionId) ?: return
        upsertSession(session.copy(activeVersion = session.activeVersion + (groupId to messageId)))
    }

    /**
     * 该会话所属角色卡引用的图片路径。
     *
     * 角色卡的参考图会被首轮消息引用（同一份文件，不是副本），
     * 所以删消息时不能无差别删文件 —— 得先排除角色卡还在用的那些。
     */
    private fun cardImagePaths(characterId: String): Set<String> =
        characterById(characterId)?.images?.mapTo(mutableSetOf()) { it.path } ?: emptySet()

    /** 消息全删光时，把开场白补回来（用户预期是「重来一遍」） */
    private fun withGreetingRestored(session: Session): Session {
        if (session.messages.isNotEmpty()) return session
        val greeting = characterById(session.characterId)?.greeting ?: ""
        if (greeting.isBlank()) return session
        return session.copy(
            messages = listOf(
                ChatMessage(role = Role.ASSISTANT, content = greeting),
            )
        )
    }

    /** 删除单条消息 */
    fun deleteMessage(sessionId: String, messageId: String) {
        val session = sessionById(sessionId) ?: return
        val msg = session.messages.firstOrNull { it.id == messageId } ?: return
        val keep = cardImagePaths(session.characterId)
        msg.images.filterNot { it.path in keep }.forEach { imageStore.delete(it.path) }
        upsertSession(withGreetingRestored(session.copy(messages = session.messages.filterNot { it.id == messageId })))
    }

    fun deleteSession(id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        prefs.write(PrefsStore.KEY_SESSIONS, ListSerializer(Session.serializer()), _sessions.value)
    }

    /** 新建会话（可选注入开场白） */
    fun createSession(characterId: String): Session {
        val card = characterById(characterId)
        // ⚠️ 不能写 `card?.greeting?.orEmpty()` —— 整条可空链的结果仍是 String?，
        // 传给非空的 content 会编译失败。用 `?: ""` 才能收窄成 String。
        val greeting: String = card?.greeting ?: ""
        val session = Session(
            characterId = characterId,
            title = card?.name ?: "新对话",
            messages = if (greeting.isBlank()) emptyList() else listOf(
                ChatMessage(
                    role = Role.ASSISTANT,
                    content = greeting,
                )
            ),
        )
        upsertSession(session)
        return session
    }

    // ═══════════════ 设置 ═══════════════

    fun updateUiSettings(settings: UiSettings) {
        _uiSettings.value = settings
        prefs.write(PrefsStore.KEY_UI_SETTINGS, UiSettings.serializer(), settings)
    }

    // ═══════════════ 图片 ═══════════════

    fun importImage(uri: android.net.Uri): com.ruxi.app.data.model.AttachedImage? =
        imageStore.import(appContext, uri)?.let { com.ruxi.app.data.model.AttachedImage(path = it) }

    /** 只返回落盘路径（背景图这类不需要 AttachedImage 包装的场景） */
    fun importImageFile(uri: android.net.Uri): String? = imageStore.import(appContext, uri)

    fun importImages(uris: List<android.net.Uri>): List<com.ruxi.app.data.model.AttachedImage> =
        imageStore.importAll(appContext, uris)

    fun deleteImageFile(path: String) = imageStore.delete(path)

    /**
     * 一次性迁移：把已有的大图压掉。
     *
     * 早期版本选图是原样复制的，相册里 3MB 的图就存 3MB。这些图每轮都要
     * base64 塞进请求体，实测单张 3MB 上传要 130 秒 —— 首字延迟被拖到几十秒。
     *
     * 迁移在后台线程跑，不阻塞启动；跑完把引用路径一并改掉。
     * 用 SharedPreferences 里的标记位保证只跑一次。
     */
    fun migrateCompressImages() {
        if (prefs.readString(KEY_IMAGE_MIGRATED) == "1") return
        val oldChars = _characters.value
        val oldSessions = _sessions.value

        val newChars = oldChars.map { c ->
            c.copy(images = c.images.map { img ->
                img.copy(path = imageStore.compressExisting(img.path))
            })
        }
        val newSessions = oldSessions.map { s ->
            s.copy(messages = s.messages.map { m ->
                m.copy(images = m.images.map { img ->
                    img.copy(path = imageStore.compressExisting(img.path))
                })
            })
        }

        val charsChanged = newChars != oldChars
        val sessionsChanged = newSessions != oldSessions
        if (charsChanged) {
            _characters.value = newChars
            prefs.write(PrefsStore.KEY_CHARACTERS, ListSerializer(CharacterCard.serializer()), newChars)
        }
        if (sessionsChanged) {
            _sessions.value = newSessions
            prefs.write(PrefsStore.KEY_SESSIONS, ListSerializer(Session.serializer()), newSessions)
        }
        prefs.writeString(KEY_IMAGE_MIGRATED, "1")
    }

    companion object {
        @Volatile
        private const val KEY_IMAGE_MIGRATED = "image_compressed_v1"

        private var instance: AppStore? = null

        fun get(context: Context): AppStore =
            instance ?: synchronized(this) {
                instance ?: AppStore(context.applicationContext).also { instance = it }
            }
    }
}
