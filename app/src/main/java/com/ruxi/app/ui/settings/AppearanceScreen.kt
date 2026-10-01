package com.ruxi.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ruxi.app.data.model.UiSettings
import com.ruxi.app.ui.components.SectionTitle
import com.ruxi.app.ui.theme.RuxiColors
import com.ruxi.app.ui.theme.parseHexColor
import com.ruxi.app.ui.theme.toHexString
import java.io.File

/**
 * 外观设置页：自定义背景、主题色、透明度。
 *
 * ## 交互设计上的两个决定
 * 1. **改一下就立刻生效**（不用点保存）。这类视觉参数必须"所见即所得"，
 *    否则用户得反复「改→保存→看→再改」，非常折磨。
 * 2. 滑块实时预览 + 数值回显。纯滑块不知道具体值，纯数字输入又不好调。
 */
@Composable
fun AppearanceScreen(
    settings: UiSettings,
    onBack: () -> Unit,
    onUpdate: (UiSettings) -> Unit,
    onPickBackground: (android.net.Uri) -> String?,
    onDeleteBackground: (String) -> Unit,
) {
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onPickBackground(uri)?.let { path ->
                val old = settings.backgroundImage
                onUpdate(settings.copy(backgroundImage = path))
                if (old.isNotBlank()) onDeleteBackground(old)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("外观", style = MaterialTheme.typography.titleLarge) },
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
            // ══════════ 主题色 ══════════
            SectionTitle("主题色")

            // 预设色板（两行）
            UiSettings.PRESETS.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { (label, hex) ->
                        val color = parseHexColor(hex) ?: Color.Gray
                        val selected = settings.accentColor.equals(hex, ignoreCase = true)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onUpdate(settings.copy(accentColor = hex)) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (selected) Modifier.border(
                                            2.dp, MaterialTheme.colorScheme.onBackground, CircleShape
                                        ) else Modifier
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF1A1030),
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) RuxiColors.accent
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 自定义 hex 输入
            var hexInput by remember { mutableStateOf(settings.accentColor) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    RuxiTextField(
                        value = hexInput,
                        onValueChange = { hexInput = it },
                        label = "自定义颜色 #RRGGBB",
                        singleLine = true,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (parseHexColor(hexInput) != null) {
                            onUpdate(settings.copy(accentColor = hexInput.trim()))
                        }
                    },
                    enabled = parseHexColor(hexInput) != null,
                ) {
                    Text("应用", color = RuxiColors.accent)
                }
            }

            // 当前色预览
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cur = parseHexColor(settings.accentColor) ?: RuxiColors.Primary
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(cur)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "当前：${settings.accentColor.ifBlank { "默认紫罗兰" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (settings.accentColor.isNotBlank()) {
                    TextButton(onClick = { onUpdate(settings.copy(accentColor = "")); hexInput = "" }) {
                        Text("恢复默认", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ══════════ 自定义背景 ══════════
            SectionTitle("自定义背景")

            val hasBg = settings.backgroundImage.isNotBlank() &&
                File(settings.backgroundImage).exists()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 预览缩略图
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { bgPicker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasBg) {
                        AsyncImage(
                            model = File(settings.backgroundImage),
                            contentDescription = "背景预览",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "选择背景",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { bgPicker.launch("image/*") }) {
                        Text(
                            if (hasBg) "更换背景图" else "选择背景图",
                            color = RuxiColors.accent,
                        )
                    }
                    if (hasBg) {
                        TextButton(onClick = {
                            onDeleteBackground(settings.backgroundImage)
                            onUpdate(settings.copy(backgroundImage = ""))
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "移除背景",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            if (hasBg) {
                Spacer(Modifier.height(10.dp))

                // 压暗
                SliderRow(
                    label = "压暗程度",
                    value = settings.backgroundDim.toFloat(),
                    range = 0f..90f,
                    display = "${settings.backgroundDim}%",
                    onChange = { onUpdate(settings.copy(backgroundDim = it.toInt())) },
                )

                // 模糊
                SliderRow(
                    label = "模糊",
                    value = settings.backgroundBlur.toFloat(),
                    range = 0f..40f,
                    display = "${settings.backgroundBlur}",
                    onChange = { onUpdate(settings.copy(backgroundBlur = it.toInt())) },
                )

                // 透明度
                SliderRow(
                    label = "背景透明度",
                    value = settings.backgroundOpacity.toFloat(),
                    range = 10f..100f,
                    display = "${settings.backgroundOpacity}%",
                    onChange = { onUpdate(settings.copy(backgroundOpacity = it.toInt())) },
                )

                Text(
                    text = "提示：背景图较花时，把「压暗」调到 50% 以上、「模糊」调到 10 以上，正文会清楚很多。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            // ══════════ 界面微调 ══════════
            SectionTitle("界面微调")

            SliderRow(
                label = "字号缩放",
                value = settings.fontScale,
                range = 0.8f..1.4f,
                display = String.format("%.2fx", settings.fontScale),
                onChange = { onUpdate(settings.copy(fontScale = (it * 100).toInt() / 100f)) },
            )

            SliderRow(
                label = "气泡圆角",
                value = settings.bubbleCorner.toFloat(),
                range = 0f..28f,
                display = "${settings.bubbleCorner}dp",
                onChange = { onUpdate(settings.copy(bubbleCorner = it.toInt())) },
            )

            // 主题模式
            SectionTitle("明暗")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    UiSettings.THEME_DARK to "深色",
                    UiSettings.THEME_LIGHT to "浅色",
                    UiSettings.THEME_SYSTEM to "跟随系统",
                ).forEach { (mode, label) ->
                    val selected = settings.themeMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (selected) RuxiColors.accentDeep
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onUpdate(settings.copy(themeMode = mode)) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingSwitchRow(
                title = "显示原始输出",
                subtitle = "气泡里显示模型吐出的带分隔符原文",
                checked = settings.showRawText,
                onChange = { onUpdate(settings.copy(showRawText = it)) },
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 带标签和数值回显的滑块行 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = display,
                style = MaterialTheme.typography.bodySmall,
                color = RuxiColors.accent,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = RuxiColors.accent,
                activeTrackColor = RuxiColors.accent,
            ),
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
