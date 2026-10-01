package com.ruxi.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ruxi.app.data.api.ChatClient
import com.ruxi.app.data.api.StreamEvent
import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.CharacterCard
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Provider
import com.ruxi.app.data.model.Role
import com.ruxi.app.data.prompt.PromptEngine
import com.ruxi.app.data.store.AppStore
import com.ruxi.app.ui.character.CharacterEditScreen
import com.ruxi.app.ui.chat.ChatScreen
import com.ruxi.app.ui.home.HomeScreen
import com.ruxi.app.ui.settings.ProviderEditScreen
import com.ruxi.app.ui.settings.SettingsScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 页面路由。用简单的 sealed class 而不是 navigation-compose —— 层级浅，够用且少一层依赖。 */
sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data class CharacterEdit(val id: String?) : Route
    data class ProviderEdit(val id: String?) : Route
    data class Chat(val characterId: String, val sessionId: String) : Route
}

/**
 * 应用根组件。
 *
 * ## 状态管理为什么不用 ViewModel
 * 数据全在 [AppStore] 这个单例里，UI 只是它的一个视图。
 * 引入 ViewModel 只会多一层「把 StateFlow 从 store 搬到 VM」的样板代码，
 * 而这个 App 没有「同一份数据多个界面各自持有副本」的需求。
 *
 * ## 流式输出的状态放在这里而不是 store
 * [streamingText] 是**纯 UI 瞬时状态**：进程被杀后不需要恢复（那条消息本来也不完整）。
 * 放进 store 会污染持久化数据。
 */
@Composable
fun RuxiAppRoot(store: AppStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val characters by store.characters.collectAsState()
    val providers by store.providers.collectAsState()
    val activeProviderId by store.activeProviderId.collectAsState()
    val promptSettings by store.promptSettings.collectAsState()
    val uiSettings by store.uiSettings.collectAsState()
    val sessions by store.sessions.collectAsState()

    var route by remember { mutableStateOf<Route>(Route.Home) }

    // 当前对话的瞬时状态。
    // [streamingSessionId] 用来把流式状态**绑定到具体会话** —— 否则用户在流式过程中
    // 返回首页、点进另一个角色，会看到上一个会话的流式气泡，且停止按钮作用于错误的会话。
    var streaming by remember { mutableStateOf(false) }
    var streamingSessionId by remember { mutableStateOf<String?>(null) }
    var streamingText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val r = route) {
            Route.Home -> HomeScreen(
                characters = characters,
                onOpenCharacter = { id ->
                    val card = store.characterById(id)
                    if (card != null) {
                        if (!card.isConfigured) {
                            // 未配置设定的卡直接引导去编辑，避免用户困惑「为什么发消息没反应」
                            route = Route.CharacterEdit(id)
                        } else {
                            // 打开最近一条会话，没有就新建
                            val existing = store.sessionsOf(id).firstOrNull()
                            val session = existing ?: store.createSession(id)
                            route = Route.Chat(id, session.id)
                        }
                    }
                },
                onCreateCharacter = { route = Route.CharacterEdit(null) },
                onEditCharacter = { route = Route.CharacterEdit(it) },
                onDeleteCharacter = { store.deleteCharacter(it) },
                onOpenSettings = { route = Route.Settings },
            )

            Route.Settings -> SettingsScreen(
                providers = providers,
                activeId = activeProviderId,
                promptSettings = promptSettings,
                uiSettings = uiSettings,
                onBack = { route = Route.Home },
                onAddProvider = { route = Route.ProviderEdit(null) },
                onEditProvider = { route = Route.ProviderEdit(it) },
                onDeleteProvider = { store.deleteProvider(it) },
                onSelectProvider = { store.setActiveProvider(it) },
                onUpdatePrompt = { store.updatePromptSettings(it) },
                onUpdateUi = { store.updateUiSettings(it) },
            )

            is Route.ProviderEdit -> {
                val initial = r.id?.let { id -> providers.firstOrNull { it.id == id } }
                ProviderEditScreen(
                    initial = initial,
                    onBack = { route = Route.Settings },
                    onSave = { provider ->
                        store.upsertProvider(provider)
                        route = Route.Settings
                    },
                )
            }

            is Route.CharacterEdit -> {
                val initial = r.id?.let { store.characterById(it) }
                CharacterEditScreen(
                    initial = initial,
                    defaultHidden = promptSettings.hiddenTemplate.ifBlank { com.ruxi.app.data.model.PromptSettings.DEFAULT_HIDDEN },
                    onBack = { route = Route.Home },
                    onSave = { card ->
                        store.upsertCharacter(card)
                        route = Route.Home
                    },
                    onPickImage = { uri -> store.importImage(uri) },
                    onRemoveImage = { path -> store.deleteImageFile(path) },
                )
            }

            is Route.Chat -> {
                val card = store.characterById(r.characterId)
                val session = sessions.firstOrNull { it.id == r.sessionId }

                if (card == null || session == null) {
                    LaunchedEffect(Unit) { route = Route.Home }
                } else {
                    ChatScreen(
                        card = card,
                        messages = session.messages,
                        // 只在本会话确实是流式目标时才显示流式气泡
                        streaming = streaming && streamingSessionId == session.id,
                        streamingText = streamingText,
                        errorText = errorText,
                        // 「显示原始输出」= 用户想看原文；或用户关掉了自动还原
                        showRaw = uiSettings.showRawText || !promptSettings.autoRestore,
                        separator = promptSettings.separator.ifEmpty { "·" },
                        onBack = {
                            // 返回时**不打断**流式：用户可能只是想看一眼角色列表。
                            // 流结束后消息照常落盘，回来还能看到完整回复。
                            route = Route.Home
                        },
                        onSend = { text, images ->
                            errorText = null
                            val provider = store.activeProvider()
                            if (provider == null) {
                                errorText = "还没有配置供应商，请到设置里添加"
                            } else if (provider.effectiveKeys.isEmpty()) {
                                errorText = "当前供应商没有填 API Key"
                            } else {
                                // ⚠️ 顺序很重要：**先组装、再落盘**。
                                // 反过来的话，PromptEngine 会把刚追加的用户消息当作
                                // 「历史消息」收进上下文，然后 build() 末尾又追加一次本轮输入
                                // → 用户的话被发了两遍，模型容易答重复。
                                val historySnapshot = store.sessionById(session.id) ?: session
                                val built = PromptEngine.build(
                                    card = card,
                                    session = historySnapshot,
                                    userText = text,
                                    userImages = images,
                                    settings = promptSettings,
                                )

                                // 组装完再把用户消息落盘（界面立刻能看到）
                                store.appendMessage(
                                    session.id,
                                    ChatMessage(role = Role.USER, content = text, images = images),
                                )

                                // 流式请求
                                streaming = true
                                streamingSessionId = session.id
                                streamingText = ""
                                streamJob = scope.launch {
                                    val buffer = StringBuilder()
                                    var failed = false
                                    try {
                                        ChatClient.get().stream(provider, built.messages)
                                            .collect { event ->
                                                when (event) {
                                                    is StreamEvent.Delta -> {
                                                        buffer.append(event.text)
                                                        streamingText = buffer.toString()
                                                    }
                                                    is StreamEvent.Done -> Unit
                                                    is StreamEvent.Failed -> {
                                                        failed = true
                                                        errorText = event.message
                                                    }
                                                }
                                            }
                                    } finally {
                                        streaming = false
                                        streamingSessionId = null
                                        val finalText = buffer.toString()
                                        // 有内容就存，哪怕是失败中断后的半截 ——
                                        // 用户已经看到这些字了，丢掉会让界面「凭空少一段」
                                        if (finalText.isNotBlank()) {
                                            store.appendMessage(
                                                session.id,
                                                ChatMessage(
                                                    role = Role.ASSISTANT,
                                                    content = finalText,
                                                )
                                            )
                                            // 标记图片已注入：只有成功拿到回复才算真的注入过。
                                            // 失败时保持 false，下次重发还能带上图。
                                            if (!failed) {
                                                val s = store.sessionById(session.id)
                                                if (s != null && !s.injectedImages) {
                                                    store.upsertSession(s.copy(injectedImages = true))
                                                }
                                            }
                                        }
                                        streamingText = ""
                                    }
                                }
                            }
                        },
                        onStop = {
                            streamJob?.cancel()
                            streaming = false
                            streamingSessionId = null
                            val partial = streamingText
                            if (partial.isNotBlank()) {
                                store.appendMessage(
                                    session.id,
                                    ChatMessage(role = Role.ASSISTANT, content = partial),
                                )
                            }
                            streamingText = ""
                        },
                        onClearMessages = {
                            store.upsertSession(
                                session.copy(messages = emptyList(), injectedImages = false)
                            )
                        },
                        onToggleRaw = {
                            store.updateUiSettings(
                                uiSettings.copy(showRawText = !uiSettings.showRawText)
                            )
                        },
                        onPickImage = { uri -> store.importImage(uri) },
                        onShowPrompt = {
                            val built = PromptEngine.build(
                                card = card,
                                session = store.sessionById(session.id) ?: session,
                                userText = "",
                                userImages = emptyList(),
                                settings = promptSettings,
                            )
                            val sysLen = built.messages.firstOrNull()
                                ?.takeIf { it.role == Role.SYSTEM }?.content?.length ?: 0
                            val imgCount = built.messages.count { it.images.isNotEmpty() }
                            errorText = "本轮注入 ${built.messages.size} 条消息 · " +
                                "系统提示词 $sysLen 字 · 图片 $imgCount 条 · " +
                                "分隔符「${promptSettings.separator}」"
                        },
                    )
                }
            }
        }
    }
}
