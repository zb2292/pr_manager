package com.gitee.prviewer.model

data class CompareResult(
    val changes: List<ChangeItem>,
    val error: String? = null
)
