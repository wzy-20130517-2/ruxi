package com.ruxi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ruxi.app.ui.theme.RuxiColors
import java.io.File

/**
 * 角色头像。
 *
 * 没有自定义头像时用**名字首字 + 渐变色块**兜底。
 * 渐变按名字哈希取值，保证同一角色每次进来颜色一致（随机会显得很乱）。
 */
@Composable
fun CharacterAvatar(
    name: String,
    avatarPath: String?,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(brush = gradientFor(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarPath != null && File(avatarPath).exists()) {
            AsyncImage(
                model = File(avatarPath),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.trim().take(1).ifEmpty { "戏" },
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val AvatarGradients = listOf(
    listOf(Color(0xFF8B6FF0), Color(0xFFD98FD0)),
    listOf(Color(0xFF5B8DEF), Color(0xFF6EE7D7)),
    listOf(Color(0xFFE07A9E), Color(0xFFF0B47C)),
    listOf(Color(0xFF7C6CF0), Color(0xFF9BE08C)),
    listOf(Color(0xFF6AC5E8), Color(0xFF8F7BF0)),
    listOf(Color(0xFFE09B6A), Color(0xFFE06A9B)),
)

private fun gradientFor(seed: String): Brush {
    val idx = (seed.hashCode().let { if (it < 0) -it else it }) % AvatarGradients.size
    return Brush.linearGradient(AvatarGradients[idx])
}

/** 统一的空状态占位 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = RuxiColors.Primary.copy(alpha = 0.75f),
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** 分区标题（带左侧竖条） */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(RuxiGradientsPrimary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val RuxiGradientsPrimary = Brush.verticalGradient(
    listOf(Color(0xFFB49BFF), Color(0xFFF2A6D8))
)

/** 小标签胶囊 */
@Composable
fun Pill(
    text: String,
    color: Color = RuxiColors.Primary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
