package com.ruxi.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 附加图片（角色卡提示词图 / 聊天图片共用）。
 *
 * [path] 存的是**应用私有目录**下的绝对路径，不是相册 Uri —— SAF 授予的
 * 临时读权限在进程重启后会失效，直接存 Uri 会导致历史消息里的图变成空白。
 */
@Serializable
data class AttachedImage(
    val id: String = UUID.randomUUID().toString(),
    val path: String,
    /** 图片备注。提示词图片的备注会作为文本一起注入（如「角色一」）。 */
    val note: String = "",
)
