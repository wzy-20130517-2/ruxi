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
import com.ruxi.app.ui.character.RuxiTextField
import com.ruxi.app.ui.components.SectionTitle
import com.ruxi.app.ui.theme.RuxiColors

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
                                if (!usePool) RuxiColors.PrimaryDeep
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
                                if (usePool) RuxiColors.PrimaryDeep
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
                RuxiTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "模型名，如 gpt-4o-mini / deepseek-chat",
                    singleLine = true,
                )

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
                                    if (selected) RuxiColors.PrimaryDeep
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
                    containerColor = RuxiColors.PrimaryDeep,
                    contentColor = Color.White,
                ),
            ) {
                Text("保存", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
