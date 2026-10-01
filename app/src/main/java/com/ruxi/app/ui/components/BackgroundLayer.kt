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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import java.io.File

/**
 * 自定义背景层。
 *
 * ## 三件事必须一起做，否则背景图会毁掉可读性
 * 1. **模糊** —— 背景的细节会跟正文抢注意力，糊掉才能突出内容
 * 2. **压暗** —— 深色文字压在亮图上完全看不清；反之浅色主题下要提亮
 * 3. **透明度** —— 用户可能只想让背景"若隐若现"
 *
 * ## 背景按角色卡走
 * 参数全部来自角色卡（见 [com.ruxi.app.data.model.CharacterCard]），不是全局设置。
 * 一个古风角色配水墨、一个赛博角色配霓虹，各自独立 —— 这才是这个功能的意义。
 * 角色卡没设背景时，退回纯色底（而不是"用上一个角色的图"，那会很怪）。
 *
 * ## 模糊的实现取舍
 * Android 12+（API 31）用 [RenderEffect] 做 GPU 实时模糊，效果最好。
 * 低版本没有这个 API，退化成"预缩放位图"的伪模糊 ——
 * 把图缩到很小再放大，等价于低通滤波。比不模糊强，且零依赖。
 */
@Composable
fun BackgroundLayer(
    imagePath: String,
    dim: Int,
    blur: Int,
    opacity: Int,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    // 底层：纯色底（没有背景图时就是它）
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF0D0918) else Color(0xFFF7F4FC))
    )

    val exists = imagePath.isNotBlank() && File(imagePath).exists()
    if (!exists) return

    val blurPx = blur.coerceIn(0, 40)
    val bitmap = remember(imagePath, blurPx) {
        runCatching {
            val f = File(imagePath)
            if (!f.exists()) return@runCatching null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0) {
                // API 31+：原图交给 RenderEffect 处理（下面用 graphicsLayer 应用）
                BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
            } else if (blurPx > 0) {
                // 低版本：预缩放做伪模糊。缩小 1/(1+blur/4) 倍再拉大。
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imagePath, opts)
                val factor = (1f + blurPx / 4f).coerceAtLeast(1f)
                val sample = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    inScaled = true
                    inTargetDensity = (opts.outWidth / factor).toInt().coerceAtLeast(8)
                    inDensity = opts.outWidth.coerceAtLeast(1)
                }
                BitmapFactory.decodeFile(imagePath, sample)?.asImageBitmap()
            } else {
                BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
            }
        }.getOrNull()
    }

    bitmap ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha((opacity.coerceIn(0, 100)) / 100f)
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
                                // Compose 官方扩展：把 android.graphics.RenderEffect
                                // 转成 compose 的。⚠️ 不要自己 new，构造函数是 protected。
                                .asComposeRenderEffect()
                        }
                    } else Modifier
                ),
        )
    }

    // 压暗/提亮层：深色主题压暗，浅色主题提亮，保证正文对比度
    val dimRatio = dim.coerceIn(0, 90) / 100f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDark) Color.Black.copy(alpha = dimRatio)
                else Color.White.copy(alpha = dimRatio * 0.75f)
            )
    )
}
