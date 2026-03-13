package com.gitee.prviewer.comment

import com.intellij.diff.util.Side

data class LineComment(
    val id: String,
    val filePath: String,
    val line: Int,
    val side: Side,
    val content: String,
    val author: String,
    val createdAt: Long,
    val parentId: String? = null,
    val resolved: Boolean = false
)
