package com.ruxi.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.ruxi.app.ui.settings.AppearanceScreen
import com.ruxi.app.ui.settings.ProviderEditScreen
import com.ruxi.app.ui.settings.SettingsScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 失败自动重试次数上限（不含首次请求） */
private const val MAX_RETRIES = 3

/** 页面路由。用简单的 sealed class 而不是 navigation-compose —— 层级浅，够用且少一层依赖。 */
sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object Appearance : Route
    /** 从对话页直达供应商设置（改完返回对话，不绕首页） */
    data object ProviderSettings : Route
    /** 角色编辑。返回走导航栈，自动回到进来时的页面。 */
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
 *
 * ## 导航为什么用栈而不是单个 route
 * 单个 route 只能记住「现在在哪」，记不住「从哪来」。
 * 于是从对话页进供应商设置，返回只能硬编码回首页 —— 用户得重新点角色、找回会话。
 * 用 [backStack] 记住来路后，返回就是弹栈，回到进来时的那个页面。
 */
@Composable
fun RuxiAppRoot(store: AppStore) {
    val scope = rememberCoroutineScope()

    val characters by store.characters.collectAsState()
    val providers by store.providers.collectAsState()
    val activeProviderId by store.activeProviderId.collectAsState()
    val uiSettings by store.uiSettings.collectAsState()
    val sessions by store.sessions.collectAsState()

    // 导航栈：栈顶是当前页，返回就是弹栈。
    // 首页是根，永远不进栈（回退到它时栈空，再按返回才退出 App）。
    var backStack by remember { mutableStateOf<List<Route>>(emptyList()) }
    val route: Route = backStack.lastOrNull() ?: Route.Home

    /** 进新页面（压栈） */
    fun navigateTo(target: Route) {
        if (target == route) return
        backStack = backStack + target
    }

    /** 返回上一级；已经在首页则返回 false，交给系统处理（退出 App） */
    fun goBack(): Boolean {
        if (backStack.isEmpty()) return false
        backStack = backStack.dropLast(1)
        return true
    }

    // 系统返回键：有上一级就返回，没有才退出。
    // 不做这个的话，按返回直接退出 App —— 用户以为会回到上一页。
    BackHandler(enabled = backStack.isNotEmpty()) {
        goBack()
    }


    // 当前对话的瞬时状态。
    // [streamingSessionId] 用来把流式状态**绑定到具体会话** —— 否则用户在流式过程中
    // 返回首页、点进另一个角色，会看到上一个会话的流式气泡，且停止按钮作用于错误的会话。
    var streaming by remember { mutableStateOf(false) }
    var streamingSessionId by remember { mutableStateOf<String?>(null) }
    var streamingText by remember { mutableStateOf("") }
    // 思考过程单独存：它跟正文走不同的渲染分支，混在一起会污染正文
    var streamingReasoning by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    /**
     * 发起一次补全请求（发送新消息 / 重新回复 共用）。
     *
     * ## 为什么要抽出来
     * 「重新回复」= 删掉末尾的 assistant 消息 + 用当前历史再跑一次。
     * 这跟「发送新消息」在**组装请求之后**的部分完全一样：
     * 建流、收 Delta/Reasoning、落盘、标记图片注入。
     * 复制一份的话，以后改流式逻辑要改两处，必漏。
     *
     * @param sessionId   目标会话
     * @param card        角色卡
     * @param provider    供应商
     * @param built       已组装好的 messages（调用方负责，因为两种场景的历史不同）
     * @param persistUser 是否把用户消息落盘（重新回复时不需要，那条已经在历史里了）
     * @param userText    用户消息文本（persistUser=true 时用）
     * @param userImages  用户消息图片
     * @param regenerateGroup 重新生成时，结果作为该组的新版本追加（null = 新消息）
     */
    fun runCompletion(
        sessionId: String,
        card: CharacterCard,
        provider: Provider,
        built: PromptEngine.BuildResult,
        persistUser: Boolean,
        userText: String = "",
        userImages: List<AttachedImage> = emptyList(),
        regenerateGroup: String? = null,
    ) {
        if (persistUser) {
            // 落盘的内容要跟**实际发出去的那条**一致 —— 含首轮注入的角色卡参考图
            // 和它的备注文本。
            //
            // 原来这里只存 userText + userImages（用户自己选的图），
            // 角色卡图从来没进过历史。后果是 PromptEngine 每轮都判断
            // 「历史里没有角色卡图」→ 每轮重新注入，同一批图被反复发给模型。
            // 占位符逻辑也因此永远等不到「已注入」状态。
            //
            // ⚠️ content 也必须用 built 里那条：它带着「以下 N 张图片是参考资料」
            // 的备注。只补 images 不补 content，历史里就会出现
            // 「有图但没有对应说明」的错位，重新打开会话时更明显。
            val sent = built.messages.lastOrNull { it.role == Role.USER }
            store.appendMessage(
                sessionId,
                ChatMessage(
                    role = Role.USER,
                    content = sent?.content ?: userText,
                    images = sent?.images ?: userImages,
                ),
            )
        }

        streaming = true
        streamingSessionId = sessionId
        streamingText = ""
        streamingReasoning = ""
        streamJob = scope.launch {
            val buffer = StringBuilder()
            val thinkBuffer = StringBuilder()
            var failed = false
            try {
                // 超时 / 429 / 5xx / 连接中断自动重试，最多 MAX_RETRIES 次。
                // 每次重试前清空已收到的内容 —— 重试是整轮重来，接着上次的
                // 半截输出拼会得到重复的前缀。
                var attempt = 0
                while (true) {
                    buffer.setLength(0)
                    thinkBuffer.setLength(0)
                    streamingText = ""
                    streamingReasoning = ""
                    errorText = null
                    var retryable: String? = null

                    ChatClient.get().stream(provider, built.messages)
                        .collect { event ->
                            when (event) {
                                is StreamEvent.Delta -> {
                                    buffer.append(event.text)
                                    streamingText = buffer.toString()
                                }
                                is StreamEvent.Reasoning -> {
                                    thinkBuffer.append(event.text)
                                    streamingReasoning = thinkBuffer.toString()
                                }
                                is StreamEvent.Done -> Unit
                                is StreamEvent.Failed -> {
                                    failed = true
                                    errorText = event.message
                                    if (event.retryable && attempt < MAX_RETRIES) {
                                        retryable = event.message
                                    }
                                }
                            }
                        }

                    val reason = retryable ?: break
                    attempt++
                    failed = false
                    errorText = "第 $attempt 次重试中…（$reason）"
                    // 退避：2s、4s、8s。太急的话上游还在抖，纯浪费额度。
                    delay(2000L shl (attempt - 1))
                }
            } finally {
                streaming = false
                streamingSessionId = null
                val finalText = buffer.toString()
                if (finalText.isNotBlank() || thinkBuffer.isNotBlank()) {
                    val group = regenerateGroup
                    if (group != null) {
                        // 重新生成：结果作为原位置的新版本，旧回复保留可切回。
                        // 取版本号最大的那条作为基准（不能用 lastOrNull —— 那是列表位置，
                        // 不是版本序号，多版本混排时会取错）。
                        val prev = store.sessionById(sessionId)
                            ?.messages?.filter { it.group == group }
                            ?.maxByOrNull { it.versionIndex }
                        if (prev != null) {
                            store.addVersion(sessionId, prev.id, finalText, thinkBuffer.toString())
                        } else {
                            store.appendMessage(
                                sessionId,
                                ChatMessage(
                                    role = Role.ASSISTANT,
                                    content = finalText,
                                    reasoning = thinkBuffer.toString(),
                                )
                            )
                        }
                    } else {
                        store.appendMessage(
                            sessionId,
                            ChatMessage(
                                role = Role.ASSISTANT,
                                content = finalText,
                                reasoning = thinkBuffer.toString(),
                            )
                        )
                    }
                }
                streamingText = ""
                streamingReasoning = ""
            }
        }
    }

    // 正在编辑的消息 id（null = 没在编辑）
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf("") }
    // 长按弹出的操作菜单目标
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    // 「帮我回复」正在生成中
    var assisting by remember { mutableStateOf(false) }
    // 生成好的内容，交给输入框（由 ChatScreen 消费后清空）
    var assistDraft by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        // 背景改由 BackgroundLayer 提供，这里用透明色透出去
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            // 背景只在对话页生效，且用**当前角色卡**的图 ——
            // 首页/设置页保持纯色，免得切换角色时背景乱闪。
            val chatCard = (route as? Route.Chat)?.let { store.characterById(it.characterId) }
            com.ruxi.app.ui.components.BackgroundLayer(
                imagePath = chatCard?.backgroundImage.orEmpty(),
                dim = chatCard?.backgroundDim ?: 45,
                blur = chatCard?.backgroundBlur ?: 0,
                opacity = chatCard?.backgroundOpacity ?: 100,
                isDark = uiSettings.themeMode != "light",
            )
            when (val r = route) {
            Route.Home -> HomeScreen(
                characters = characters,
                onOpenCharacter = { id ->
                    val card = store.characterById(id)
                    if (card != null) {
                        if (!card.isConfigured) {
                            // 未配置设定的卡直接引导去编辑，避免用户困惑「为什么发消息没反应」
                            navigateTo(Route.CharacterEdit(id))
                        } else {
                            // 打开最近一条会话，没有就新建
                            val existing = store.sessionsOf(id).firstOrNull()
                            val session = existing ?: store.createSession(id)
                            navigateTo(Route.Chat(id, session.id))
                        }
                    }
                },
                onCreateCharacter = { navigateTo(Route.CharacterEdit(null)) },
                onEditCharacter = { navigateTo(Route.CharacterEdit(it)) },
                onDeleteCharacter = { store.deleteCharacter(it) },
                onOpenSettings = { navigateTo(Route.Settings) },
            )

            Route.Settings -> SettingsScreen(
                providers = providers,
                activeId = activeProviderId,
                uiSettings = uiSettings,
                onBack = { goBack() },
                onAddProvider = { navigateTo(Route.ProviderEdit(null)) },
                onEditProvider = { navigateTo(Route.ProviderEdit(it)) },
                onDeleteProvider = { store.deleteProvider(it) },
                onSelectProvider = { store.setActiveProvider(it) },
                onUpdateUi = { store.updateUiSettings(it) },
                onOpenAppearance = { navigateTo(Route.Appearance) },
            )

            Route.ProviderSettings -> SettingsScreen(
                providers = providers,
                activeId = activeProviderId,
                uiSettings = uiSettings,
                onBack = { goBack() },
                onAddProvider = { navigateTo(Route.ProviderEdit(null)) },
                onEditProvider = { navigateTo(Route.ProviderEdit(it)) },
                onDeleteProvider = { store.deleteProvider(it) },
                onSelectProvider = { store.setActiveProvider(it) },
                onUpdateUi = { store.updateUiSettings(it) },
                onOpenAppearance = { navigateTo(Route.Appearance) },
            )

            Route.Appearance -> AppearanceScreen(
                settings = uiSettings,
                onBack = { goBack() },
                onUpdate = { store.updateUiSettings(it) },
            )

            is Route.ProviderEdit -> {
                val initial = r.id?.let { id -> providers.firstOrNull { it.id == id } }
                ProviderEditScreen(
                    initial = initial,
                    onBack = { goBack() },
                    onSave = { provider ->
                        store.upsertProvider(provider)
                        goBack()
                    },
                )
            }

            is Route.CharacterEdit -> {
                val initial = r.id?.let { store.characterById(it) }
                CharacterEditScreen(
                    initial = initial,
                    onBack = { goBack() },
                    onSave = { card ->
                        store.upsertCharacter(card)
                        goBack()
                    },
                    onPickImage = { uri -> store.importImage(uri) },
                    onRemoveImage = { path -> store.deleteImageFile(path) },
                    onPickBackground = { uri -> store.importImageFile(uri) },
                    onRemoveBackground = { path -> store.deleteImageFile(path) },
                )
            }

            is Route.Chat -> {
                val card = store.characterById(r.characterId)
                val session = sessions.firstOrNull { it.id == r.sessionId }

                if (card == null || session == null) {
                    LaunchedEffect(Unit) { backStack = emptyList() }
                } else {
                    ChatScreen(
                        card = card,
                        session = session,
                        // 只在本会话确实是流式目标时才显示流式气泡
                        streaming = streaming && streamingSessionId == session.id,
                        streamingText = streamingText,
                        errorText = errorText,
                        onDismissError = { errorText = null },
                        showThinking = uiSettings.showThinking,
                        thinkingExpanded = uiSettings.thinkingExpanded,
                        streamingReasoning = streamingReasoning,
                        onBack = {
                            // 返回时**不打断**流式：用户可能只是想看一眼角色列表。
                            // 流结束后消息照常落盘，回来还能看到完整回复。
                            goBack()
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
                                // 「历史消息」收进上下文，build() 末尾又追加一次本轮输入
                                // → 用户的话被发了两遍，模型容易答重复。
                                val snapshot = store.sessionById(session.id) ?: session
                                val built = PromptEngine.build(
                                    card = card,
                                    session = snapshot,
                                    userText = text,
                                    userImages = images,
                                    replayThinking = provider.replayThinking,
                                )
                                runCompletion(
                                    sessionId = session.id,
                                    card = card,
                                    provider = provider,
                                    built = built,
                                    persistUser = true,
                                    userText = text,
                                    userImages = images,
                                )
                            }
                        },
                        onStop = {
                            streamJob?.cancel()
                            streaming = false
                            streamingSessionId = null
                            val partial = streamingText
                            val partialThink = streamingReasoning
                            if (partial.isNotBlank() || partialThink.isNotBlank()) {
                                store.appendMessage(
                                    session.id,
                                    ChatMessage(
                                        role = Role.ASSISTANT,
                                        content = partial,
                                        reasoning = partialThink,
                                    ),
                                )
                            }
                            streamingText = ""
                            streamingReasoning = ""
                        },
                        onClearMessages = {
                            // 清空 = 回到「刚建会话」的状态：消息清掉、开场白放回去。
                            // 角色卡图片不需要手动重置 —— 历史里没有那些路径了，
                            // PromptEngine 下一轮自然会重新注入。
                            val greeting = card.greeting
                            store.upsertSession(
                                session.copy(
                                    messages = if (greeting.isBlank()) emptyList() else listOf(
                                        ChatMessage(
                                            role = Role.ASSISTANT,
                                            content = greeting,
                                        )
                                    ),
                                )
                            )
                        },
                        onEditCharacter = {
                            // 记住当前会话，改完提示词直接回到这条对话
                            navigateTo(Route.CharacterEdit(card.id))
                        },
                        onOpenProviderSettings = { navigateTo(Route.ProviderSettings) },
                        onPickImage = { uri -> store.importImage(uri) },
                        assisting = assisting,
                        onAssistReply = {
                            val provider = store.activeProvider()
                            if (provider == null) {
                                errorText = "还没有配置供应商"
                            } else if (provider.effectiveKeys.isEmpty()) {
                                errorText = "当前供应商没有填 API Key"
                            } else if (streaming) {
                                errorText = "正在回复中，稍等一下"
                            } else {
                                errorText = null
                                assisting = true
                                scope.launch {
                                    // 「帮我回复」是**一次性短请求**，不用流式：
                                    // 它只输出一句话，等它完整回来再填进输入框更简单可靠
                                    // （流式反而要处理"边生成边改输入框"的闪烁）。
                                    val messages = PromptEngine.buildAssistReply(
                                        card = card,
                                        session = store.sessionById(session.id) ?: session,
                                    )
                                    val sb = StringBuilder()
                                    try {
                                        ChatClient.get().stream(provider, messages)
                                            .collect { ev ->
                                                when (ev) {
                                                    is StreamEvent.Delta -> sb.append(ev.text)
                                                    else -> Unit
                                                }
                                            }
                                    } catch (e: Exception) {
                                        errorText = "生成失败：${e.message}"
                                    }
                                    assisting = false
                                    val text = sb.toString()
                                        .trim()
                                        .trim('"', '「', '」', '“', '”', '\'', '：', ':')
                                        .trim()
                                    if (text.isNotBlank()) {
                                        assistDraft = text
                                    } else {
                                        errorText = "没能生成内容，再试一次？"
                                    }
                                }
                            }
                        },
                        assistDraft = assistDraft,
                        onAssistDraftConsumed = { assistDraft = null },

                        // ── 消息操作（长按触发）──
                        editingMessageId = editingMessageId,
                        editDraft = editDraft,
                        actionTarget = actionTarget,
                        onMessageLongPress = { msg ->
                            actionTarget = msg
                        },
                        onDismissMenu = { actionTarget = null },
                        onEditDraftChange = { editDraft = it },
                        onEditConfirm = {
                            val id = editingMessageId
                            if (id != null && editDraft.isNotBlank()) {
                                // 追加版本而不是覆盖 —— 旧的编辑前内容要能切回去
                                store.addVersion(session.id, id, editDraft)
                            }
                            editingMessageId = null
                            editDraft = ""
                        },
                        onEditCancel = {
                            editingMessageId = null
                            editDraft = ""
                        },
                        onStartEdit = { msg ->
                            actionTarget = null
                            editingMessageId = msg.id
                            editDraft = msg.content
                        },
                        onDeleteMessage = { msg ->
                            actionTarget = null
                            store.deleteMessage(session.id, msg.id)
                        },
                        onSelectVersion = { groupId, messageId ->
                            store.selectVersion(session.id, groupId, messageId)
                        },
                        onRegenerate = { msg ->
                            actionTarget = null
                            val provider = store.activeProvider()
                            if (provider == null) {
                                errorText = "还没有配置供应商"
                            } else if (streaming) {
                                errorText = "正在回复中，稍等一下"
                            } else {
                                // 重新回复 = 用**这条回复之前**的历史重跑，结果作为原位置的新版本。
                                // 旧回复不删，用户可以用箭头切回去对比。
                                //
                                // ⚠️ 必须按**位置**截断，不能只按 group 过滤掉自己。
                                // 只删自己会把后面几轮留在历史里，变成「助手先开口」的孤儿：
                                //   用户:你好 / 助手:你好 / 用户:你是谁 / 助手:我是xx
                                //   重生成第二条（助手:你好）→ 删掉它 → 历史成了
                                //   用户:你好 / 用户:你是谁 / 助手:我是xx
                                //   角色交替乱了，模型直接答歪。
                                //
                                // 正确做法：找到这条 assistant 前面那条 user，
                                // 历史保留到那条 user 之前，再把它的正文作为本轮输入。
                                val group = msg.group
                                val snapshot = store.sessionById(session.id) ?: session
                                val visible = snapshot.visibleMessages()
                                val targetIdx = visible.indexOfFirst { it.group == group }
                                if (targetIdx < 0) {
                                    errorText = "找不到这条消息，可能已被删除"
                                } else {
                                    // 往前找最近的一条 user（正常情况下就是紧挨着的上一条）
                                    val userIdx = (targetIdx - 1 downTo 0)
                                        .firstOrNull { visible[it].isUser }
                                    if (userIdx == null) {
                                        errorText = "这条回复前面没有用户消息，无法重新生成"
                                    } else {
                                        val userMsg = visible[userIdx]
                                        // 保留该 user 之前的全部历史，去掉它自己
                                        // —— 本轮输入由 userText 传入，避免重复一次
                                        val keepGroups = visible.take(userIdx).map { it.group }.toSet()
                                        val trimmed = snapshot.copy(
                                            messages = snapshot.messages.filter { it.group in keepGroups }
                                        )
                                        val built = PromptEngine.build(
                                            card = card,
                                            session = trimmed,
                                            userText = userMsg.content,
                                            userImages = userMsg.images,
                                            replayThinking = provider.replayThinking,
                                        )
                                        runCompletion(
                                            sessionId = session.id,
                                            card = card,
                                            provider = provider,
                                            built = built,
                                            persistUser = false,
                                            regenerateGroup = group,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
            }
        }
    }
}
