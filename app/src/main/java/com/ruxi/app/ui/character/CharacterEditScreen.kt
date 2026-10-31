package com.ruxi.app.ui.character

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.ruxi.app.data.model.AttachedImage
import com.ruxi.app.data.model.CharacterCard
import com.ruxi.app.ui.components.RuxiTextField
import com.ruxi.app.ui.components.SectionTitle
import com.ruxi.app.ui.theme.RuxiColors
import java.io.File
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.filled.Delete

/**
 * 角色卡编辑页。
 *
 * 三块内容按「配置顺序」自上而下排列：
 *  1. 基本信息（名字 / 简介 / 头像）
 *  2. 提示词（系统设定 / 用户设定 / 隐藏要求）
 *  3. 参考图片（可备注）
 *
 */
@Composable
fun CharacterEditScreen(
    initial: CharacterCard?,
    onBack: () -> Unit,
    onSave: (CharacterCard) -> Unit,
    onPickImage: (android.net.Uri) -> AttachedImage?,
    onRemoveImage: (String) -> Unit,
    /** 选背景图，返回落盘路径 */
    onPickBackground: (android.net.Uri) -> String?,
    /** 删除背景文件 */
    onRemoveBackground: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var subtitle by remember { mutableStateOf(initial?.subtitle ?: "") }
    var avatar by remember { mutableStateOf(initial?.avatar) }
    var systemSetting by remember { mutableStateOf(initial?.systemSetting ?: "") }
    var userSetting by remember { mutableStateOf(initial?.userSetting ?: "") }
    var greeting by remember { mutableStateOf(initial?.greeting ?: "") }
    var images by remember { mutableStateOf(initial?.images ?: emptyList()) }
    var noteEditing by remember { mutableStateOf<AttachedImage?>(null) }

    // 对话背景（按角色卡独立）
    var bgPath by remember { mutableStateOf(initial?.backgroundImage ?: "") }
    var bgDim by remember { mutableStateOf(initial?.backgroundDim ?: 45) }
    var bgBlur by remember { mutableStateOf(initial?.backgroundBlur ?: 0) }
    var bgOpacity by remember { mutableStateOf(initial?.backgroundOpacity ?: 100) }

    // 选图后要解码 + 压缩 + 写盘，都是同步 IO。原图动辄几 MB，
    // 放主线程解码会明显卡顿，所以统一丢到 IO 线程再回主线程更新状态。
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val newPath = withContext(Dispatchers.IO) { onPickBackground(uri) }
                if (newPath != null) {
                    val old = bgPath
                    bgPath = newPath
                    if (old.isNotBlank()) withContext(Dispatchers.IO) { onRemoveBackground(old) }
                }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val (added, failed) = withContext(Dispatchers.IO) {
                    var bad = 0
                    val list = uris.mapNotNull {
                        onPickImage(it) ?: run { bad++; null }
                    }
                    list to bad
                }
                images = images + added
                if (failed > 0) {
                    android.widget.Toast.makeText(
                        context,
                        "有 $failed 张图片读取失败（可能已被移动或删除）",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val img = withContext(Dispatchers.IO) { onPickImage(uri) }
                if (img != null) avatar = img.path
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (initial == null) "新建角色" else "编辑角色",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val card = (initial ?: CharacterCard()).copy(
                                name = name.ifBlank { "未命名" },
                                subtitle = subtitle,
                                avatar = avatar,
                                systemSetting = systemSetting,
                                userSetting = userSetting,
                                greeting = greeting,
                                images = images,
                                backgroundImage = bgPath,
                                backgroundDim = bgDim,
                                backgroundBlur = bgBlur,
                                backgroundOpacity = bgOpacity,
                            )
                            onSave(card)
                        },
                        enabled = systemSetting.isNotBlank(),
                    ) {
                        Text(
                            "保存",
                            color = if (systemSetting.isNotBlank()) RuxiColors.accent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
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
            // ── 头像 + 名字 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { avatarPicker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    val path = avatar
                    if (path != null && File(path).exists()) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = "头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "选择头像",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    RuxiTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "角色名",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    RuxiTextField(
                        value = subtitle,
                        onValueChange = { subtitle = it },
                        label = "一句话简介",
                        singleLine = true,
                    )
                }
            }

            // ── 系统设定 ──
            SectionTitle("系统设定（每轮注入）")
            RuxiTextField(
                value = systemSetting,
                onValueChange = { systemSetting = it },
                label = "角色的世界观、性格、说话方式…",
                modifier = Modifier.padding(horizontal = 16.dp),
                minLines = 5,
            )

            // ── 用户设定 ──
            SectionTitle("用户设定")
            RuxiTextField(
                value = userSetting,
                onValueChange = { userSetting = it },
                label = "你（用户）在这个故事里的身份、与角色的关系…",
                modifier = Modifier.padding(horizontal = 16.dp),
                minLines = 3,
            )

            // ── 开场白 ──
            SectionTitle("开场白（新对话时自动作为第一句）")
            RuxiTextField(
                value = greeting,
                onValueChange = { greeting = it },
                label = "角色说的第一句话…",
                modifier = Modifier.padding(horizontal = 16.dp),
                minLines = 3,
            )

            // ── 参考图片 ──
            SectionTitle("参考图片（仅首轮注入，可备注）")
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "添加图片",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(images, key = { _, it -> it.id }) { _, img ->
                    Box(modifier = Modifier.width(96.dp)) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { noteEditing = img },
                            ) {
                                AsyncImage(
                                    model = File(img.path),
                                    contentDescription = img.note,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                // 删除角标
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xAA000000))
                                        .clickable {
                                            onRemoveImage(img.path)
                                            images = images.filterNot { it.id == img.id }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "移除",
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = img.note.ifBlank { "点图加备注" },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (img.note.isBlank()) RuxiColors.TextTertiary
                                else RuxiColors.Tertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "提示：备注会作为文字和图片一起发给模型（如「角色一」「场景参考」），" +
                    "方便模型把图片和设定对应起来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // ── 对话背景（按角色卡独立） ──
            SectionTitle("对话背景（仅这个角色）")

            val hasBg = bgPath.isNotBlank() && File(bgPath).exists()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                            model = File(bgPath),
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
                            onRemoveBackground(bgPath)
                            bgPath = ""
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
                Spacer(Modifier.height(8.dp))
                BgSliderRow(
                    label = "压暗程度",
                    value = bgDim.toFloat(),
                    range = 0f..90f,
                    display = "$bgDim%",
                    onChange = { bgDim = it.toInt() },
                )
                BgSliderRow(
                    label = "模糊",
                    value = bgBlur.toFloat(),
                    range = 0f..40f,
                    display = "$bgBlur",
                    onChange = { bgBlur = it.toInt() },
                )
                BgSliderRow(
                    label = "透明度",
                    value = bgOpacity.toFloat(),
                    range = 10f..100f,
                    display = "$bgOpacity%",
                    onChange = { bgOpacity = it.toInt() },
                )
                Text(
                    text = "背景较花时把「压暗」调到 50% 以上、「模糊」调到 10 以上，正文会清楚很多。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val card = (initial ?: CharacterCard()).copy(
                        name = name.ifBlank { "未命名" },
                        subtitle = subtitle,
                        avatar = avatar,
                        systemSetting = systemSetting,
                        userSetting = userSetting,
                        greeting = greeting,
                        images = images,
                        backgroundImage = bgPath,
                        backgroundDim = bgDim,
                        backgroundBlur = bgBlur,
                        backgroundOpacity = bgOpacity,
                    )
                    onSave(card)
                },
                enabled = systemSetting.isNotBlank(),
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
                Text("保存角色卡", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // 备注编辑弹窗
    noteEditing?.let { img ->
        var noteText by remember(img.id) { mutableStateOf(img.note) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { noteEditing = null },
            title = { Text("图片备注") },
            text = {
                Column {
                    AsyncImage(
                        model = File(img.path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                    RuxiTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = "例如：角色一 / 主角外观参考",
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    images = images.map { if (it.id == img.id) it.copy(note = noteText) else it }
                    noteEditing = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { noteEditing = null }) { Text("取消") }
            },
        )
    }
}

/** 带标签与数值回显的滑块行（角色编辑页背景调节用） */
@Composable
private fun BgSliderRow(
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
