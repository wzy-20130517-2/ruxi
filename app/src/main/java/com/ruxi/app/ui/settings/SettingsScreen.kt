package com.ruxi.app.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruxi.app.data.api.ChatClient
import com.ruxi.app.data.model.PromptSettings
import com.ruxi.app.data.model.Provider
import com.ruxi.app.data.model.UiSettings
import com.ruxi.app.ui.character.RuxiTextField
import com.ruxi.app.ui.components.Pill
import com.ruxi.app.ui.components.SectionTitle
import com.ruxi.app.ui.theme.RuxiColors
import kotlinx.coroutines.launch

/**
 * 设置页（主页）。
 *
 * 分三块：供应商 / 提示词引擎 / 外观。
 * 供应商放在最上面，因为它是「能不能用」的前置条件。
 */
@Composable
fun SettingsScreen(
    providers: List<Provider>,
    activeId: String,
    promptSettings: PromptSettings,
    uiSettings: UiSettings,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onUpdatePrompt: (PromptSettings) -> Unit,
    onUpdateUi: (UiSettings) -> Unit,
    onOpenAppearance: () -> Unit,
) {
    var testing by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
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
            // ═══════ 供应商 ═══════
            SectionTitle("供应商（可配置多个，随时切换）")

            if (providers.isEmpty()) {
                Text(
                    text = "还没有供应商，点下方按钮添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            providers.forEach { p ->
                val isActive = p.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isActive) RuxiColors.accentDeep.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onSelectProvider(p.id) }
                        .padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = p.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isActive) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "当前使用",
                                    tint = RuxiColors.accent,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "${p.model} · ${p.maskedKey}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (p.effectiveKeys.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Pill(text = "${p.effectiveKeys.size} 个 key 轮换", color = RuxiColors.Tertiary)
                        }
                    }

                    IconButton(
                        onClick = {
                            testing = p.id
                            testResult = null
                            scope.launch {
                                val result = ChatClient.get().test(p)
                                testing = null
                                testResult = p.name to (if (result.first) "✓ ${result.second}"
                                else "✗ ${result.second}")
                            }
                        },
                        enabled = testing == null,
                    ) {
                        if (testing == p.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = RuxiColors.accent,
                            )
                        } else {
                            Icon(
                                Icons.Default.Science,
                                contentDescription = "测试连接",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                    IconButton(onClick = { onEditProvider(p.id) }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { onDeleteProvider(p.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onAddProvider,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加供应商")
            }

            Spacer(Modifier.height(24.dp))

            // ═══════ 提示词引擎 ═══════
            SectionTitle("提示词引擎")
            Column(Modifier.padding(horizontal = 16.dp)) {
                RuxiTextField(
                    value = promptSettings.separator,
                    onValueChange = { onUpdatePrompt(promptSettings.copy(separator = it)) },
                    label = "分隔符（默认 ·）",
                    singleLine = true,
                )

                Spacer(Modifier.height(12.dp))
                SettingSwitch(
                    title = "启用隐藏要求",
                    subtitle = "关闭后就是一个普通角色扮演应用",
                    checked = promptSettings.hiddenEnabled,
                    onChange = { onUpdatePrompt(promptSettings.copy(hiddenEnabled = it)) },
                )
                SettingSwitch(
                    title = "系统提示词也逐字分隔",
                    subtitle = "作为格式示范，效果比纯文字要求好得多",
                    checked = promptSettings.splitSystem,
                    onChange = { onUpdatePrompt(promptSettings.copy(splitSystem = it)) },
                )
                SettingSwitch(
                    title = "用户消息自动分隔",
                    subtitle = "你打的字会自动变成逐字分隔的形式",
                    checked = promptSettings.splitUser,
                    onChange = { onUpdatePrompt(promptSettings.copy(splitUser = it)) },
                )
                SettingSwitch(
                    title = "自动还原模型输出",
                    subtitle = "界面上显示「你好」而不是「你·好」",
                    checked = promptSettings.autoRestore,
                    onChange = { onUpdatePrompt(promptSettings.copy(autoRestore = it)) },
                )
            }

            SectionTitle("全局隐藏要求模板（角色卡未填时使用）")
            RuxiTextField(
                value = promptSettings.hiddenTemplate,
                onValueChange = { onUpdatePrompt(promptSettings.copy(hiddenTemplate = it)) },
                label = "留空 = 用内置默认模板。{SEP} 会替换成上面的分隔符",
                modifier = Modifier.padding(horizontal = 16.dp),
                minLines = 8,
            )
            TextButton(
                onClick = {
                    onUpdatePrompt(promptSettings.copy(hiddenTemplate = PromptSettings.DEFAULT_HIDDEN))
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("填入内置默认模板", color = RuxiColors.accent)
            }

            Spacer(Modifier.height(16.dp))

            // ═══════ 外观 ═══════
            SectionTitle("外观")
            Column(Modifier.padding(horizontal = 16.dp)) {
                // 外观细节（主题色/背景/字号）在独立页 —— 参数太多，塞这里会淹掉别的设置
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClick = onOpenAppearance)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "主题色 · 背景 · 字号",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "自定义背景图、主题颜色、透明度等",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = RuxiColors.accent,
                    )
                }

                Spacer(Modifier.height(12.dp))
                SettingSwitch(
                    title = "显示原始输出",
                    subtitle = "气泡里显示模型吐出的带分隔符原文",
                    checked = uiSettings.showRawText,
                    onChange = { onUpdateUi(uiSettings.copy(showRawText = it)) },
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    testResult?.let { (name, result) ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text("测试「$name」") },
            text = {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.startsWith("✓")) RuxiColors.Tertiary
                    else MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                TextButton(onClick = { testResult = null }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
