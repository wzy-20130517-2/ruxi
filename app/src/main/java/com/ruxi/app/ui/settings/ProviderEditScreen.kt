package com.ruxi.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ruxi.app.data.model.Protocol
import com.ruxi.app.data.model.Provider
import com.ruxi.app.ui.components.RuxiTextField
import com.ruxi.app.ui.components.SectionTitle
import com.ruxi.app.ui.theme.RuxiColors
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import com.ruxi.app.data.api.ChatClient
import com.ruxi.app.data.model.Effort
import kotlinx.coroutines.launch

/**
 * 供应商编辑页。
 *
 * 预设了几个常见中转站的 Base URL 模板，点一下直接填 ——
 * 手输 URL 是最容易出错的地方（少个 /v1、多个斜杠都会 404）。
 */
@Composable
fun ProviderEditScreen(
    initial: Provider?,
    onBack: () -> Unit,
    onSave: (Provider) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var poolText by remember {
        mutableStateOf(initial?.apiKeys?.joinToString("\n") ?: "")
    }
    var model by remember { mutableStateOf(initial?.model ?: "gpt-4o-mini") }
    var protocol by remember { mutableStateOf(initial?.protocol ?: Protocol.OPENAI.id) }
    var temperature by remember { mutableStateOf((initial?.temperature ?: 0.85f).toString()) }
    var maxTokens by remember { mutableStateOf((initial?.maxTokens ?: 2048).toString()) }
    var extraHeaders by remember { mutableStateOf(initial?.extraHeaders ?: "") }
    var usePool by remember { mutableStateOf((initial?.apiKeys?.size ?: 0) > 0) }
    var effort by remember { mutableStateOf(initial?.effort ?: Effort.NONE.id) }
    var replayThinking by remember { mutableStateOf(initial?.replayThinking ?: false) }

    // ── 模型列表拉取 ──
    var loadingModels by remember { mutableStateOf(false) }
    var modelDialog by remember { mutableStateOf<List<String>?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    // 勾选状态：默认**全不选**（用户明确要的），让他自己挑要用的
    var picked by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    /**
     * 用**当前表单里的值**临时拼一个 Provider 去请求 ——
     * 不能直接用 initial，因为用户可能刚改了 URL/Key 还没保存，
     * 拿旧值去拉列表会拉到上一个站点的模型（很迷惑）。
     */
    fun currentDraft(): Provider {
        val keys = poolText.lines().map { it.trim() }.filter { it.isNotBlank() }
        return (initial ?: Provider()).copy(
            name = name,
            baseUrl = baseUrl.trim(),
            apiKey = if (usePool) "" else apiKey.trim(),
            apiKeys = if (usePool) keys else emptyList(),
            model = model.trim(),
            protocol = protocol,
            extraHeaders = extraHeaders,
        )
    }

    fun fetchModels() {
        modelError = null
        loadingModels = true
        scope.launch {
            val (ids, err) = ChatClient.get().listModels(currentDraft())
            loadingModels = false
            if (ids.isEmpty()) {
                modelError = err ?: "没拉到模型"
            } else {
                picked = emptySet()      // 每次打开都重置为全不选
                modelDialog = ids
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (initial == null) "添加供应商" else "编辑供应商",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                RuxiTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称（自己认得就行）",
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                RuxiTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "Base URL",
                    singleLine = true,
                )
            }

            // ── 常见预设 ──
            SectionTitle("快速填入")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "OpenAI 官方" to "https://api.openai.com/v1",
                    "DeepSeek" to "https://api.deepseek.com/v1",
                    "中转站通用" to "https://your-proxy.com/v1",
                ).forEach { (label, url) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                baseUrl = url
                                if (name.isBlank()) name = label
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 密钥 ──
            SectionTitle("API Key")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (!usePool) RuxiColors.accentDeep
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { usePool = false }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "单个",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!usePool) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (usePool) RuxiColors.accentDeep
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { usePool = true }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "轮换池",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (usePool) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (usePool) "遇到 429/额度错误自动换下一个" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = RuxiColors.TextTertiary,
                    )
                }

                Spacer(Modifier.height(10.dp))
                if (usePool) {
                    RuxiTextField(
                        value = poolText,
                        onValueChange = { poolText = it },
                        label = "一行一个 key",
                        minLines = 4,
                    )
                } else {
                    RuxiTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "sk-...",
                        singleLine = true,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 模型 ──
            SectionTitle("模型")
            Column(Modifier.padding(horizontal = 16.dp)) {
                // 模型名 + 右侧「拉取列表」按钮。
                // 手输模型名最容易错（大小写、版本号、连字符），
                // 能从 /models 拉一次让用户勾选就少一大半「模型不存在」的报错。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        RuxiTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = "模型名，如 gpt-4o-mini / deepseek-chat",
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (loadingModels) MaterialTheme.colorScheme.surfaceVariant
                                else RuxiColors.accentDeep
                            )
                            .clickable(enabled = !loadingModels) { fetchModels() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loadingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = RuxiColors.accent,
                            )
                        } else {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = "获取模型列表",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "协议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Protocol.entries.forEach { p ->
                        val selected = protocol == p.id
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selected) RuxiColors.accentDeep
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { protocol = p.id }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = p.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        RuxiTextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            label = "温度 0~2",
                            singleLine = true,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        RuxiTextField(
                            value = maxTokens,
                            onValueChange = { maxTokens = it },
                            label = "最大回复长度",
                            singleLine = true,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 思考强度 ──
            SectionTitle("思考强度（effort）")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "调高会让模型先想再答，推理类问题更准，但更慢也更费 token。" +
                        "不同厂商字段名不同，这里已按协议自动翻译。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Effort.entries.forEach { e ->
                        val selected = effort == e.id
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selected) RuxiColors.accentDeep
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { effort = e.id }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = e.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (effort != Effort.NONE.id) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (Protocol.from(protocol)) {
                            Protocol.OPENAI -> "将以 reasoning_effort 字段发送"
                            Protocol.ANTHROPIC -> "将以 thinking.budget_tokens 发送" +
                                "（预算 ${Effort.from(effort).budgetTokens}，会自动小于最大回复长度）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = RuxiColors.TextTertiary,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "回传历史思考",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "让模型记得自己上一轮想过什么。默认关（省 token）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = replayThinking, onCheckedChange = { replayThinking = it })
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 高级 ──
            SectionTitle("高级（一般不用填）")
            RuxiTextField(
                value = extraHeaders,
                onValueChange = { extraHeaders = it },
                label = "额外请求头，一行一个，如 X-Token: abc",
                modifier = Modifier.padding(horizontal = 16.dp),
                minLines = 2,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val keys = poolText.lines().map { it.trim() }.filter { it.isNotBlank() }
                    val provider = (initial ?: Provider()).copy(
                        name = name.ifBlank { "未命名供应商" },
                        baseUrl = baseUrl.trim(),
                        apiKey = if (usePool) "" else apiKey.trim(),
                        apiKeys = if (usePool) keys else emptyList(),
                        model = model.trim(),
                        protocol = protocol,
                        temperature = temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.85f,
                        maxTokens = maxTokens.toIntOrNull()?.coerceIn(64, 32768) ?: 2048,
                        extraHeaders = extraHeaders,
                        effort = effort,
                        replayThinking = replayThinking,
                    )
                    onSave(provider)
                },
                enabled = baseUrl.isNotBlank() && model.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RuxiColors.accentDeep,
                    contentColor = Color.White,
                ),
            ) {
                Text("保存", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // ══════════ 模型列表勾选弹窗 ══════════
    //
    // 默认**全不选**（用户明确要求）：模型列表通常几十上百条，
    // 预选一个反而容易让人误以为「已经选好了」而直接点确定。
    modelDialog?.let { ids ->
        AlertDialog(
            onDismissRequest = { modelDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("选择模型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "已选 ${picked.size}/${ids.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = RuxiColors.accent,
                    )
                }
            },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { picked = ids.toSet() }) {
                            Text("全选", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { picked = emptySet() }) {
                            Text("清空", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(ids, key = { it }) { id ->
                            val checked = id in picked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        picked = if (checked) picked - id else picked + id
                                    }
                                    .padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 选多个时，第一个填进模型名（其余暂时丢弃 —— 一个供应商只跑一个模型；
                        // 需要多模型就建多个供应商配置，语义更清楚）
                        picked.firstOrNull()?.let { first ->
                            model = first
                        }
                        modelDialog = null
                    },
                    enabled = picked.isNotEmpty(),
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { modelDialog = null }) { Text("取消") }
            },
        )
    }

    // 拉取失败的提示
    modelError?.let { err ->
        AlertDialog(
            onDismissRequest = { modelError = null },
            title = { Text("获取模型列表失败") },
            text = {
                Text(
                    text = err + "\n\n可以继续手动输入模型名。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { modelError = null }) { Text("知道了") }
            },
        )
    }
}
