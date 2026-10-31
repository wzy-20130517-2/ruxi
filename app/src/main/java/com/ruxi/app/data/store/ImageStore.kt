package com.ruxi.app.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import com.ruxi.app.data.model.AttachedImage
import java.io.File
import java.util.UUID

/**
 * 图片本地化 + 压缩。
 *
 * ## 为什么必须复制一份
 * 相册选图返回的是 SAF 的 content:// Uri，读权限是**随 Intent 临时授予**的。
 * 进程重启后权限失效，直接存 Uri 会让历史消息里的图全变成空白 ——
 * 而且这种 bug 只在「杀进程重进」时才复现，开发时很难注意到。
 *
 * ## 为什么必须压缩
 * 相册原图动辄 2~4MB。这些图会以 base64 塞进请求体发给模型
 * （base64 还要再膨胀 33%），实测单张 3MB 的图上传要 130 秒，
 * 首字延迟被拖到几十秒 —— 而模型看图只需要 1024px 左右的分辨率。
 *
 * 所以落盘前统一缩放 + 转 JPEG。典型 3MB 原图压到 200KB 上下，
 * 画质对「参考设定」这个用途完全够用。
 */
class ImageStore(context: Context) {

    private val dir: File = File(context.filesDir, "images").apply { mkdirs() }

    /**
     * 把 Uri 指向的图片压缩后存进私有目录，返回落盘路径；失败返回 null。
     *
     * ⚠️ 这是**同步 IO**（读原图 + 解码 + 重编码 + 写盘），调用方必须在
     * IO 线程上跑。相册大图在主线程解码会直接卡住 UI。
     */
    fun import(context: Context, uri: Uri): String? {
        return try {
            val bitmap = decodeScaled(context, uri) ?: return null
            val target = File(dir, "${UUID.randomUUID()}.jpg")
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** 批量导入 */
    fun importAll(context: Context, uris: List<Uri>): List<AttachedImage> =
        uris.mapNotNull { uri ->
            import(context, uri)?.let { path -> AttachedImage(path = path) }
        }

    /** 批量导入，同时把失败的个数带出来（UI 要能告诉用户「有几张没成功」） */
    fun importAllCounting(context: Context, uris: List<Uri>): Pair<List<AttachedImage>, Int> {
        val ok = mutableListOf<AttachedImage>()
        var failed = 0
        uris.forEach { uri ->
            val p = import(context, uri)
            if (p != null) ok += AttachedImage(path = p) else failed++
        }
        return ok to failed
    }

    /**
     * 把已存在的图片文件就地压缩（一次性迁移用）。
     *
     * 返回压缩后的新路径；本来就够小或压缩失败时返回原路径。
     *
     * ⚠️ 原文件**不能删**。同一张图可能被角色卡和消息同时引用，
     * 迁移按引用逐条处理，第一次压缩后如果删了原文件，第二次再遇到
     * 同一个路径就找不到源文件、只能返回旧路径 —— 结果是引用指向一个
     * 已被删除的文件，图片变成空白。
     *
     * 所以这里只生成新文件、不动原文件，由迁移结束后统一清理没人引用的旧文件。
     */
    fun compressExisting(path: String): String {
        val src = File(path)
        if (!src.exists() || src.parentFile != dir) return path
        if (src.length() <= COMPRESS_THRESHOLD_BYTES) return path
        return try {
            val bitmap = decodeScaledFile(src) ?: return path
            val target = File(dir, "${UUID.randomUUID()}.jpg")
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            // 压缩没变小就保留原图（某些已优化过的小图重编码反而变大）
            if (target.length() >= src.length()) {
                target.delete()
                return path
            }
            target.absolutePath
        } catch (e: Exception) {
            path
        }
    }

    /** 删除文件（角色卡/消息被删时调用） */
    fun delete(path: String) {
        runCatching { File(path).takeIf { it.exists() && it.parentFile == dir }?.delete() }
    }

    fun fileOf(path: String): File = File(path)

    // ─────────────────────── 解码 ───────────────────────

    /**
     * 读 Uri 指向的图，按长边上限采样解码。
     *
     * ⚠️ 必须**只打开一次输入流**。
     * `GetMultipleContents` 返回的 Uri 往往是「一次性」的（部分相册/文件管理器的
     * provider 只保证单次可读），第一遍读尺寸、第二遍读像素的话，第二次 openInputStream
     * 直接失败 —— 表现为「选图没反应、图加不上」。
     *
     * 所以先把整个字节读进内存，再从内存数组解码两次（尺寸探测 + 实际像素）。
     * 代价是原图会完整进一次内存，但这是唯一稳妥的做法。
     */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun decodeScaledFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /**
     * 计算 inSampleSize —— 只能取 2 的幂，且**不能直接按目标尺寸算**。
     *
     * 先按 2 的幂缩到「不小于目标」的档位，剩下的交给 [MAX_EDGE] 之后的
     * 二次缩放在编码前做（这里图省事只做采样，长边可能略大于 MAX_EDGE，
     * 但已足够把 4MB 压到 200KB 级别）。
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        return sample
    }

    companion object {
        /** 长边目标上限（像素）。模型看图不需要原图分辨率 */
        private const val MAX_EDGE = 1280

        /** JPEG 质量。85 在「参考图」这个用途上肉眼看不出差别 */
        private const val JPEG_QUALITY = 85

        /** 小于这个大小的文件不折腾（已经是优化过的图） */
        private const val COMPRESS_THRESHOLD_BYTES = 300L * 1024

        /** content:// 还是 file:// 都能转成 Uri */
        fun toUri(path: String): Uri = path.toUri()
    }
}
