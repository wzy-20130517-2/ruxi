package com.ruxi.app.ui.components

import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import com.ruxi.app.data.model.UiSettings
import java.io.File

/**
 * 自定义背景层。
 *
 * ## 三件事必须一起做，否则背景图会毁掉可读性
 * 1. **模糊** —— 背景的细节会跟正文抢注意力，糊掉才能突出内容
 * 2. **压暗** —— 深色文字压在亮图上完全看不清；反之浅色主题下要提亮
 * 3. **透明度** —— 用户可能只想让背景"若隐若现"
 *
 * ## 模糊的实现取舍
 * Android 12+（API 31）用 [RenderEffect] 做 GPU 实时模糊，效果最好。
 * 低版本没有这个 API，退化成"预缩放位图"的伪模糊 ——
 * 把图缩到很小再放大，等价于低通滤波。比不模糊强，且零依赖。
 */
@Composable
fun BackgroundLayer(
    settings: UiSettings,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val path = settings.backgroundImage
    val exists = path.isNotBlank() && File(path).exists()

    // 底层：纯色底（没有背景图时就是它）
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF0D0918) else Color(0xFFF7F4FC))
    )

    if (!exists) return

    // 模糊半径：按设置值缩放。预缩放倍数越大，视觉上越糊。
    val blurPx = settings.backgroundBlur.coerceIn(0, 40)
    val bitmap = remember(path, blurPx) {
        runCatching {
            val f = File(path)
            if (!f.exists()) return@runCatching null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0) {
                // API 31+：原图交给 RenderEffect 处理（在下面用 graphicsLayer 应用）
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } else if (blurPx > 0) {
                // 低版本：预缩放做伪模糊。缩小 1/(1+blur/4) 倍再拉大。
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                val factor = (1f + blurPx / 4f).coerceAtLeast(1f)
                val sw = (opts.outWidth / factor).toInt().coerceAtLeast(8)
                val sh = (opts.outHeight / factor).toInt().coerceAtLeast(8)
                val sample = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    inScaled = true
                    inTargetDensity = sw
                    inDensity = opts.outWidth.coerceAtLeast(1)
                }
                BitmapFactory.decodeFile(path, sample)?.asImageBitmap()
            } else {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }.getOrNull()
    }

    bitmap ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha((settings.backgroundOpacity.coerceIn(0, 100)) / 100f)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0) {
                        Modifier.graphicsLayer {
                            renderEffect = RenderEffect
                                .createBlurEffect(
                                    blurPx.toFloat(),
                                    blurPx.toFloat(),
                                    Shader.TileMode.CLAMP,
                                )
                                .asComposeRenderEffect()
                        }
                    } else Modifier
                ),
        )
    }

    // 压暗/提亮层：深色主题压暗，浅色主题提亮，保证正文对比度
    val dim = settings.backgroundDim.coerceIn(0, 90) / 100f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDark) Color.Black.copy(alpha = dim)
                else Color.White.copy(alpha = dim * 0.75f)
            )
    )
}

/** RenderEffect -> Compose RenderEffect 的桥接 */
private fun android.graphics.RenderEffect.asComposeRenderEffect() =
    androidx.compose.ui.graphics.RenderEffect(this)
