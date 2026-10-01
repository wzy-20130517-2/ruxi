package com.ruxi.app.data.store

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.ruxi.app.data.model.AttachedImage
import java.io.File
import java.util.UUID

/**
 * 图片本地化。
 *
 * ## 为什么必须复制一份
 * 相册选图返回的是 SAF 的 content:// Uri，读权限是**随 Intent 临时授予**的。
 * 进程重启后权限失效，直接存 Uri 会让历史消息里的图全变成空白 ——
 * 而且这种 bug 只在「杀进程重进」时才复现，开发时很难注意到。
 *
 * 所以选图后立刻复制到应用私有目录，之后只认文件路径。
 */
class ImageStore(context: Context) {

    private val dir: File = File(context.filesDir, "images").apply { mkdirs() }

    /** 把 Uri 指向的图片复制进私有目录，返回落盘路径；失败返回 null。 */
    fun import(context: Context, uri: Uri): String? {
        return try {
            val ext = guessExt(context, uri)
            val target = File(dir, "${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
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

    /** 删除文件（角色卡/消息被删时调用） */
    fun delete(path: String) {
        runCatching { File(path).takeIf { it.exists() && it.parentFile == dir }?.delete() }
    }

    fun fileOf(path: String): File = File(path)

    private fun guessExt(context: Context, uri: Uri): String {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
        return when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            mime.contains("bmp") -> "bmp"
            else -> "jpg"
        }
    }

    companion object {
        /** content:// 还是 file:// 都能转成 Uri */
        fun toUri(path: String): Uri = path.toUri()
    }
}
