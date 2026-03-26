package com.gitee.prviewer.comment

object PrIssueCache {
    private val cache = mutableMapOf<Long, IssueStats>()

    fun put(prId: Long, stats: IssueStats) {
        cache[prId] = stats
    }

    fun replaceAll(prId: Long, stats: IssueStats) {
        cache.clear()
        cache[prId] = stats
    }

    fun get(prId: Long): IssueStats? = cache[prId]

    fun clear(prId: Long) {
        cache.remove(prId)
    }

    fun clearAll() {
        cache.clear()
    }
}

data class IssueStats(
    val total: Int,
    val unresolved: Int,
    val issues: List<IssueItem>
)

data class IssueItem(
    val id: String,
    val number: String,
    val createBy: String,
    val msg: String,
    val createTime: String,
    val file: String,
    val line: Int,
    val status: String,
    val replies: List<IssueReply>
)

data class IssueReply(
    val num: String,
    val createBy: String,
    val msg: String,
    val createTime: String,
    val responseTo: String
)
