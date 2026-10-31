package com.ruxi.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.CharacterCard
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.model.Session
import com.ruxi.app.ui.components.CharacterAvatar
import com.ruxi.app.ui.theme.LocalAppearance
import com.ruxi.app.ui.theme.RuxiColors
import java.io.File
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 对话页。
 *
 * ## 布局要点
 * - 用 [imePadding] 让输入框跟着键盘上移（配合 manifest 的 adjustResize）
 * - 消息列表倒序排列：新消息在底部，滚动到底部用 `animateScrollToItem(0)`
 *   —— 比正向列表 + reverseLayout=false 简单，也不用算总长度
 * - 流式输出时自动跟随（[autoScroll]），但用户手动上滑后停止跟随，
 *   避免「想看历史却被不断拽回底部」
 */
@Composable
fun ChatScreen(
    card: CharacterCard,
    session: Session,
    streaming: Boolean,
    streamingText: String,
    errorText: String?,
    /** 是否显示模型思考过程 */
    showThinking: Boolean,
    /** 思考块默认是否展开 */
    thinkingExpanded: Boolean,
    /** 正在流式接收的思考内容 */
    streamingReasoning: String,
    onBack: () -> Unit,
    onSend: (String, List<AttachedImage>) -> Unit,
    onStop: () -> Unit,
    onClearMessages: () -> Unit,
    onEditCharacter: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onPickImage: (android.net.Uri) -> AttachedImage?,
    /** 点「帮我回复」 */
    onAssistReply: () -> Unit,
    /** 正在生成「帮我回复」 */
    assisting: Boolean,
    /** 生成好的内容（消费后由 onAssistDraftConsumed 清空） */
    assistDraft: String?,
    onAssistDraftConsumed: () -> Unit,

    // ── 消息操作 ──
    editingMessageId: String?,
    editDraft: String,
    actionTarget: ChatMessage?,
    onMessageLongPress: (ChatMessage) -> Unit,
    /** 关闭操作菜单。**必须独立于 onMessageLongPress** —— 那个是「打开」 */
    onDismissMenu: () -> Unit,
    onEditDraftChange: (String) -> Unit,
    onEditConfirm: () -> Unit,
    onEditCancel: () -> Unit,
    onStartEdit: (ChatMessage) -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    /** 切换某组显示的版本 */
    onSelectVersion: (groupId: String, messageId: String) -> Unit,
) {
    // 每组只显示一个版本，其余作为备选（左右箭头切换）
    val visible = session.visibleMessages()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }

    // 收到「帮我回复」的结果 → 填进输入框。
    // 用 LaunchedEffect 而不是直接在组合里赋值：组合函数可能因任何状态变化重跑，
    // 直接赋值会把用户正在打的字反复覆盖掉。
    LaunchedEffect(assistDraft) {
        assistDraft?.let {
            input = it
            onAssistDraftConsumed()
        }
    }
    var pendingImages by remember { mutableStateOf(emptyList<AttachedImage>()) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 选图 = 解码 + 压缩 + 写盘，同步 IO。放主线程会卡 UI，丢到 IO 线程。
    val pickScope = rememberCoroutineScope()
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pickScope.launch {
                val added = withContext(Dispatchers.IO) {
                    uris.mapNotNull { onPickImage(it) }
                }
                pendingImages = pendingImages + added
            }
        }
    }

    // 新内容出现时滚到底。
    //
    // ⚠️ 不要用 `streamingText.length` 当 key —— 流式时它每个 token 都变，
    // 会触发高频重组合 + animateScrollToItem 抢滚动，长回复下明显掉帧。
    // 改成只依赖「消息条数」和「是否在流式」，流式期间用 scrollToItem 直接跳
    // （不带动画，开销小得多）。
    LaunchedEffect(visible.size, streaming) {
        if (listState.firstVisibleItemIndex <= 2) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    Scaffold(
        // 透明底 —— 让 BackgroundLayer 透上来。角色卡没设背景时
        // BackgroundLayer 自己会铺纯色底，所以这里不需要再画一层。
        containerColor = Color.Transparent,
        // Scaffold 默认的 contentWindowInsets 是 systemBars，会把导航栏高度
        // 算进 content padding。而输入栏自己已经用 navigationBarsPadding()
        // 处理了一次 —— 两处叠加会让输入栏被抬高两倍导航栏高度，
        // Surface 只包住内容不铺到底，中间那段就露出背景（输入框下面漏一行）。
        //
        // 这里把底部交给输入栏自己管，Scaffold 只保留顶部（topBar 已消费）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                card = card,
                onBack = onBack,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onClearMessages = onClearMessages,
                onEditCharacter = onEditCharacter,
                onOpenProviderSettings = onOpenProviderSettings,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // ── 消息列表（倒序：index 0 是最新） ──
            //
            // 【性能要点】流式气泡作为**独立 item**（key 固定为 "streaming"），
            // 不参与下面的 messages 列表构建。否则每来一个 token 都要把整个
            // 列表重建一遍 —— 长对话下会明显卡顿。
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    reverseLayout = true,
                ) {
                    if (streaming) {
                        item(key = "streaming") {
                            MessageBubble(
                                message = ChatMessage(
                                    role = "assistant",
                                    content = streamingText,
                                    reasoning = streamingReasoning,
                                    incomplete = true,
                                ),
                                card = card,
                                isStreaming = true,
                                showThinking = showThinking,
                                thinkingExpanded = thinkingExpanded,
                                // 流式气泡**不响应长按**：它是每次重组都新建的临时对象
                                // （id 都是随机 UUID），拿它去编辑/删除/重答都会对不上历史里那条。
                                // 等生成完、落盘成真消息之后才可操作。
                                onLongPress = {},
                            )
                        }
                    }
                    items(
                        count = visible.size,
                        key = { idx -> visible[visible.size - 1 - idx].id },
                    ) { idx ->
                        val msg = visible[visible.size - 1 - idx]
                        val versions = session.versionsOf(msg.group)
                        MessageBubble(
                            message = msg,
                            card = card,
                            isStreaming = false,
                            showThinking = showThinking,
                            thinkingExpanded = thinkingExpanded,
                            onLongPress = { onMessageLongPress(msg) },
                            editing = editingMessageId == msg.id,
                            editDraft = editDraft,
                            onEditDraftChange = onEditDraftChange,
                            onEditConfirm = onEditConfirm,
                            onEditCancel = onEditCancel,
                            versionCount = versions.size,
                            versionCursor = session.versionCursor(msg.group),
                            onPrevVersion = {
                                val i = session.versionCursor(msg.group)
                                if (i > 0) onSelectVersion(msg.group, versions[i - 1].id)
                            },
                            onNextVersion = {
                                val i = session.versionCursor(msg.group)
                                if (i < versions.lastIndex) onSelectVersion(msg.group, versions[i + 1].id)
                            },
                        )
                    }
                }

                if (visible.isEmpty() && !streaming) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "开始你的第一句话吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RuxiColors.TextTertiary,
                        )
                    }
                }
            }

            // ── 错误条 ──
            errorText?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 待发送图片预览 ──
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingImages.forEach { img ->
                        Box {
                            AsyncImage(
                                model = File(img.path),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color(0xAA000000))
                                    .clickable {
                                        pendingImages = pendingImages.filterNot { it.id == img.id }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("×", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ── 输入栏 ──
            InputBar(
                value = input,
                onValueChange = { input = it },
                streaming = streaming,
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty() || pendingImages.isNotEmpty()) {
                        onSend(text, pendingImages)
                        input = ""
                        pendingImages = emptyList()
                    }
                },
                onStop = onStop,
                onPickImage = { imagePicker.launch("image/*") },
                onAssistReply = onAssistReply,
                assisting = assisting,
            )
        }
    }

    // ══════════ 长按消息的操作菜单 ══════════
    actionTarget?.let { msg ->
        // 能不能重答：只有 assistant 消息、且不在流式中才有意义
        val canRegenerate = msg.isAssistant && !msg.incomplete && !streaming
        val items = buildList {
            add(Triple(Icons.Default.Edit, "编辑内容", false) to { onStartEdit(msg) })
            if (canRegenerate) {
                add(Triple(Icons.Default.Refresh, "重新回复", false) to { onRegenerate(msg) })
            }
            add(Triple(Icons.Default.ContentCopy, "复制文本", false) to {
                clipboard.setText(AnnotatedString(msg.content))
                context.toast("已复制")
                onDismissMenu()
            })
            add(Triple(Icons.Default.Delete, "删除这条", true) to { onDeleteMessage(msg) })
        }
        MessageActionMenu(
            items = items,
            onDismiss = onDismissMenu,
        )
    }
}

/**
 * 悬浮操作菜单。
 *
 * 用 Dialog + 透明底 + 右侧浮层，而不是 AlertDialog ——
 * AlertDialog 自带标题栏和确认按钮，结构上是「对话框」；
 * 而长按菜单是一组并列的动作，要的是一张浮在内容上的卡片。
 */
@Composable
private fun MessageActionMenu(
    items: List<Pair<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, Boolean>, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 点空白处关闭
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .widthIn(max = 200.dp)
                    // 卡片自身消费点击，避免穿透到遮罩层
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    items.forEachIndexed { idx, (meta, action) ->
                        val (icon, label, danger) = meta
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            )
                        }
                        MenuAction(
                            icon = icon,
                            label = label,
                            danger = danger,
                            onClick = action,
                        )
                    }
                }
            }
        }
    }
}

/** 操作菜单里的一行 */
@Composable
private fun MenuAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

@Composable
private fun ChatTopBar(
    card: CharacterCard,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onClearMessages: () -> Unit,
    onEditCharacter: () -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x2A2F1E5C), Color(0x00000000))))
                // 用 statusBarsPadding 而不是硬编码 top —— 不同机型状态栏高度不同
                // （刘海/挖孔/手势条），写死 34dp 在某些机器上会被挡住或留白。
                .statusBarsPadding()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            CharacterAvatar(name = card.name, avatarPath = card.avatar, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (card.images.isNotEmpty()) "首轮注入 ${card.images.size} 张参考图"
                    else card.subtitle.ifBlank { "在线" },
                    style = MaterialTheme.typography.bodySmall,
                    color = RuxiColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    // 对话进行到一半最常见的两个念头：
                    //   ① 「角色说话不对劲」→ 改这个角色的提示词
                    //   ② 「没反应/答非所问」→ 检查供应商配置
                    // 所以这两个入口放最上面，直接跳转，不用退回首页绕一圈。
                    DropdownMenuItem(
                        text = { Text("编辑角色提示词") },
                        onClick = { onMenuOpenChange(false); onEditCharacter() },
                    )
                    DropdownMenuItem(
                        text = { Text("供应商设置") },
                        onClick = { onMenuOpenChange(false); onOpenProviderSettings() },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    DropdownMenuItem(
                        text = { Text("清空对话", color = MaterialTheme.colorScheme.error) },
                        onClick = { onMenuOpenChange(false); onClearMessages() },
                    )
                }
            }
        }
    }
}

/** 单条消息气泡 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    card: CharacterCard,
    isStreaming: Boolean,
    showThinking: Boolean,
    thinkingExpanded: Boolean,
    /** 长按弹出操作菜单（编辑/重答/复制/删除） */
    onLongPress: () -> Unit = {},
    /** 是否处于「编辑中」状态 */
    editing: Boolean = false,
    /** 编辑草稿与变更回调（editing=true 时生效） */
    editDraft: String = "",
    onEditDraftChange: (String) -> Unit = {},
    onEditConfirm: () -> Unit = {},
    onEditCancel: () -> Unit = {},
    /** 该消息所在组共有几个版本（>1 时显示切换箭头） */
    versionCount: Int = 1,
    /** 当前显示的是组内第几个版本（0 起） */
    versionCursor: Int = 0,
    onPrevVersion: () -> Unit = {},
    onNextVersion: () -> Unit = {},
) {
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            CharacterAvatar(name = card.name, avatarPath = card.avatar, size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 290.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            // ── 思考过程（仅 assistant，且设置里开启时才显示）──
            if (showThinking && !isUser && message.hasReasoning) {
                ThinkingBlock(
                    text = message.reasoning,
                    expandedByDefault = thinkingExpanded,
                    streaming = isStreaming,
                )
                Spacer(Modifier.height(6.dp))
            }

            // 图片
            if (message.images.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.images.forEach { img ->
                        AsyncImage(
                            model = File(img.path),
                            contentDescription = img.note,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .height(160.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                    }
                }
                if (message.content.isNotBlank()) Spacer(Modifier.height(6.dp))
            }

            if (editing) {
                // ── 编辑态：原位变成输入框 ──
                // 选择「原位编辑」而不是弹窗：用户改的往往是措辞，
                // 需要看着上下文改，弹窗会把上下文挡住。
                Surface(
                    shape = RoundedCornerShape(LocalAppearance.current.bubbleCorner.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(10.dp)) {
                        OutlinedTextField(
                            value = editDraft,
                            onValueChange = onEditDraftChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(10.dp),
                            maxLines = 12,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(onClick = onEditCancel) {
                                Text("取消", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = onEditConfirm) {
                                Text(
                                    "保存",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = RuxiColors.accent,
                                )
                            }
                        }
                    }
                }
            } else if (message.content.isNotBlank()) {
                val displayText = message.content

                val corner = LocalAppearance.current.bubbleCorner.dp
                Surface(
                    shape = RoundedCornerShape(
                        topStart = corner,
                        topEnd = corner,
                        bottomStart = if (isUser) corner else 4.dp,
                        bottomEnd = if (isUser) 4.dp else corner,
                    ),
                    color = when {
                        message.isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                        isUser -> RuxiColors.accentDeep
                        else -> MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress,
                    ),
                ) {
                    Text(
                        text = displayText.ifEmpty { if (isStreaming) "…" else "" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            message.isError -> MaterialTheme.colorScheme.error
                            isUser -> Color.White
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            // 版本切换：同一位置有多个版本时才显示
            if (versionCount > 1 && !isStreaming && !editing) {
                Spacer(Modifier.height(2.dp))
                VersionSwitcher(
                    count = versionCount,
                    cursor = versionCursor,
                    onPrev = onPrevVersion,
                    onNext = onNextVersion,
                )
            }

            if (isStreaming) {
                Spacer(Modifier.height(4.dp))
                TypingDots()
            }
        }
    }
}

/**
 * 版本切换控件：`‹ 1/2 ›`。
 *
 * 用序号而不是「编辑前/编辑后」这类文案 —— 版本数不设上限，
 * 用户可能来回改很多次，序号是唯一能扩展到任意数量的表示。
 */
@Composable
private fun VersionSwitcher(
    count: Int,
    cursor: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val canPrev = cursor > 0
    val canNext = cursor < count - 1
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val active = MaterialTheme.colorScheme.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onPrev,
            enabled = canPrev,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.ChevronLeft,
                contentDescription = "上一版",
                tint = if (canPrev) active else dim,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "${cursor + 1}/$count",
            style = MaterialTheme.typography.labelMedium,
            color = active,
        )
        IconButton(
            onClick = onNext,
            enabled = canNext,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "下一版",
                tint = if (canNext) active else dim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 思考过程展示块。
 *
 * 设计取舍：
 * - 默认**折叠**。思维链通常很长，展开会淹没正文；但完全不给看又显得不透明。
 * - 折叠时显示「思考中…」+ 已生成的字数，让用户知道它在动，不是卡住。
 * - 用等宽偏暗的样式，跟正文视觉上明确区分 —— 它是「草稿纸」不是「台词」。
 */
@Composable
private fun ThinkingBlock(
    text: String,
    expandedByDefault: Boolean,
    streaming: Boolean,
) {
    var expanded by remember(text.take(1)) { mutableStateOf(expandedByDefault) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RuxiColors.accent.copy(alpha = 0.07f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                tint = RuxiColors.accent.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (streaming) "思考中…" else "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = RuxiColors.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${text.length} 字 ${if (expanded) "▾" else "▸"}",
                style = MaterialTheme.typography.bodySmall,
                color = RuxiColors.TextTertiary,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 折叠态给个预览，展开后全量
                maxLines = if (streaming) Int.MAX_VALUE else Int.MAX_VALUE,
            )
        } else if (text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = text.takeLast(80).replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = RuxiColors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 「正在输入」的三点动画 */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 180, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(RuxiColors.accent.copy(alpha = alpha)),
            )
        }
    }
}

/** 底部输入栏 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    /** 点「帮我回复」：让模型以用户身份写一句话，结果填进输入框 */
    onAssistReply: () -> Unit,
    /** 正在生成「帮我回复」 */
    assisting: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // navigationBarsPadding 放在 Surface **内部的 Row** 上，
                // 让 Surface 自己一直铺到屏幕底边 —— 导航栏那块也归输入栏着色。
                //
                // 反过来把 navigationBarsPadding 加到 Surface 的外层 Modifier 上，
                // Surface 就只包住内容、不延伸到底，导航栏那条高度会露出背景层，
                // 表现为「输入框下面漏一行」。
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onPickImage, enabled = !streaming) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = "发送图片",
                    tint = if (streaming) RuxiColors.TextTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 「帮我回复」：以用户身份写一句话。
            // 结果**填进输入框**而不是直接发送 —— 用户可能想改两个字再发，
            // 直接发出去就没得改了（而且这是模型揣测用户想说什么，必须给确认机会）。
            IconButton(onClick = onAssistReply, enabled = !streaming && !assisting) {
                if (assisting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = RuxiColors.accent,
                    )
                } else {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = "帮我回复",
                        tint = if (streaming) RuxiColors.TextTertiary else RuxiColors.accent,
                    )
                }
            }

            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "说点什么…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RuxiColors.TextTertiary,
                    )
                },
                shape = RoundedCornerShape(20.dp),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (streaming) MaterialTheme.colorScheme.error else RuxiColors.accentDeep)
                    .clickable { if (streaming) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                if (streaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/** 短提示（复制成功之类） */
private fun android.content.Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
