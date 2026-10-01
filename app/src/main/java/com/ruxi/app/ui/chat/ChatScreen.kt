package com.ruxi.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.CharacterCard
import com.ruxi.app.data.model.ChatMessage
import com.ruxi.app.data.prompt.PromptEngine
import com.ruxi.app.ui.components.CharacterAvatar
import com.ruxi.app.ui.theme.LocalAppearance
import com.ruxi.app.ui.theme.RuxiColors
import java.io.File

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
    messages: List<ChatMessage>,
    streaming: Boolean,
    streamingText: String,
    errorText: String?,
    showRaw: Boolean,
    /** 当前生效的分隔符，用于还原显示。必须来自设置，不能硬编码。 */
    separator: String,
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
) {
    var input by remember { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf(emptyList<AttachedImage>()) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) pendingImages = pendingImages + uris.mapNotNull { onPickImage(it) }
    }

    // 新内容出现时滚到底。
    //
    // ⚠️ 不要用 `streamingText.length` 当 key —— 流式时它每个 token 都变，
    // 会触发高频重组合 + animateScrollToItem 抢滚动，长回复下明显掉帧。
    // 改成只依赖「消息条数」和「是否在流式」，流式期间用 scrollToItem 直接跳
    // （不带动画，开销小得多）。
    LaunchedEffect(messages.size, streaming) {
        if (listState.firstVisibleItemIndex <= 2) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                                showRaw = showRaw,
                                separator = separator,
                                showThinking = showThinking,
                                thinkingExpanded = thinkingExpanded,
                            )
                        }
                    }
                    items(
                        count = messages.size,
                        key = { idx -> messages[messages.size - 1 - idx].id },
                    ) { idx ->
                        MessageBubble(
                            message = messages[messages.size - 1 - idx],
                            card = card,
                            isStreaming = false,
                            showRaw = showRaw,
                            separator = separator,
                            showThinking = showThinking,
                            thinkingExpanded = thinkingExpanded,
                        )
                    }
                }

                if (messages.isEmpty() && !streaming) {
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
            )
        }
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
                .padding(start = 4.dp, end = 4.dp, top = 34.dp, bottom = 8.dp),
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
    showRaw: Boolean,
    separator: String,
    showThinking: Boolean,
    thinkingExpanded: Boolean,
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

            if (message.content.isNotBlank()) {
                val displayText = if (showRaw) message.content
                else PromptEngine.restore(message.content, separator)

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

            if (isStreaming) {
                Spacer(Modifier.height(4.dp))
                TypingDots()
            }
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
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
