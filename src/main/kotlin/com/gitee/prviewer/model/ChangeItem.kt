package com.gitee.prviewer.model

data class ChangeItem(
    val filePath: String,
    val changeType: String,
    val fromFilePath: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0
)
