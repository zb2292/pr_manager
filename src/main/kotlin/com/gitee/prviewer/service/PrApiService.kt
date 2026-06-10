package com.gitee.prviewer.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.HttpResponse

class PrApiService(
    private val httpClient: HttpRequestClient,
    private val objectMapper: ObjectMapper,
    private val listUrl: String,
    private val detailUrl: String,
    private val noteListUrl: String,
    private val noteUrl: String,
    private val replyUrl: String,
    private val resolveUrl: String,
    private val reviewUrl: String,
    private val mergeUrl: String,
    private val deletePrUrl: String,
    private val closePrUrl: String,
    private val updatePrUrl: String,
    private val aiReviewPrDetailUrl: String,
    private val aiHandleIssueUrl: String,
    private val aiReviewFileDetailUrl: String,
    private val triggerAiReviewUrl: String,
    private val createPrUrl: String,
    private val developersUrl: String,
    private val repoMemberRoleUrl: String,
    private val canCreatePrUrl: String
) {
    fun fetchPrList(requestBody: String): HttpResponse<String> {
        return executeApi("fetchPrList", listUrl, requestBody)
    }

    fun fetchPrDetail(prId: Long): HttpResponse<String> {
        val payload = mapOf("prId" to prId)
        return executeApi("fetchPrDetail", detailUrl, objectMapper.writeValueAsString(payload))
    }

    fun createNote(prId: Long, commitId: String, filePath: String, context: String, codeLine: Int): HttpResponse<String> {
        val payload = mapOf(
            "prId" to prId,
            "commitId" to commitId,
            "filePath" to filePath,
            "context" to context,
            "codeLine" to codeLine
        )
        return executeApi("createNote", noteUrl, objectMapper.writeValueAsString(payload))
    }

    fun replyNote(
        prId: Long,
        context: String,
        nodeId: String? = null,
        replyNoteId: String? = null,
        replyUserId: Int? = null
    ): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "prId" to prId,
            "context" to context
        )
        if (!nodeId.isNullOrBlank()) {
            payload["nodeId"] = nodeId
        }
        if (!replyNoteId.isNullOrBlank()) {
            payload["replyNoteId"] = replyNoteId
        }
        if (replyUserId != null ) {
            payload["replyUserId"] = replyUserId
        }
        return executeApi("replyNote", replyUrl, objectMapper.writeValueAsString(payload))
    }

    fun resolveNote(prIid: Long, nodeId: String, sshPath: String): HttpResponse<String> {
        val payload = mapOf(
            "prIid" to prIid,
            "resolve" to true,
            "nodeId" to nodeId,
            "sshPath" to sshPath
        )
        return executeApi("resolveNote", resolveUrl, objectMapper.writeValueAsString(payload))
    }

    fun submitPrReview(sshPath: String, iid: Long, comment: String, state: String): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "sshPath" to sshPath,
            "iId" to iid,
            "comment" to comment,
            "state" to state
        )
        return executeApi("submitPrReview", reviewUrl, objectMapper.writeValueAsString(payload))
    }

    fun mergePrByUser(
        sshPath: String,
        number: Long,
        mergeMethod: String,
        commitMessage: String,
        extMessage: String,
        pruneBranch: Boolean
    ): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "sshPath" to sshPath,
            "number" to number,
            "mergeMethod" to mergeMethod,
            "commitMessage" to commitMessage,
            "extMessage" to extMessage,
            "pruneBranch" to pruneBranch
        )
        return executeApi("mergePrByUser", mergeUrl, objectMapper.writeValueAsString(payload))
    }

    fun closePrByUser(sshPath: String, iid: Long): HttpResponse<String> {
        val payload = mapOf(
            "sshPath" to sshPath,
            "iId" to iid
        )
        return executeApi("closePrByUser", closePrUrl, objectMapper.writeValueAsString(payload))
    }

    fun deletePrByUser(sshPath: String, iid: Long): HttpResponse<String> {
        val payload = mapOf(
            "sshPath" to sshPath,
            "iId" to iid
        )
        return executeApi("deletePrByUser", deletePrUrl, objectMapper.writeValueAsString(payload))
    }

    fun updatePrByUser(
        sshPath: String,
        iid: Long,
        title: String,
        body: String,
        assigneesIds: List<Long>,
        assigneesNum: Int,
        primaryAssigneesIds: List<Long>,
        primaryAssigneesNum: Int,
        pruneBranch: Boolean,
        defaultMergeType: String
    ): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "sshPath" to sshPath,
            "iId" to iid,
            "title" to title,
            "body" to body,
            "assigneesIds" to assigneesIds,
            "assigneesNum" to assigneesNum,
            "primaryAssigneesIds" to primaryAssigneesIds,
            "primaryAssigneesNum" to primaryAssigneesNum,
            "pruneBranch" to pruneBranch,
            "defaultMergeType" to defaultMergeType
        )
        return executeApi("updatePrByUser", updatePrUrl, objectMapper.writeValueAsString(payload))
    }

    fun fetchNoteList(sshPath: String, iid: Long): HttpResponse<String> {
        val payload = mapOf(
            "sshPath" to sshPath,
            "iid" to iid
        )
        return executeApi("fetchNoteList", noteListUrl, objectMapper.writeValueAsString(payload))
    }

    fun fetchAiReviewOverview(prId: Long): HttpResponse<String> {
        val payload = mapOf("prId" to prId)
        return executeApi("fetchAiReviewOverview", aiReviewPrDetailUrl, objectMapper.writeValueAsString(payload))
    }

    fun fetchAiReviewDetail(prId: Long, filePath: String): HttpResponse<String> {
        val payload = mapOf(
            "prId" to prId,
            "filePath" to filePath
        )
        return executeApi("fetchAiReviewDetail", aiReviewFileDetailUrl, objectMapper.writeValueAsString(payload))
    }

    fun triggerAiReview(
        prId: Long,
        userOA: String,
        userName: String,
        fileDiffInfos: List<Map<String, String>>
    ): HttpResponse<String> {
        val requestInfo = linkedMapOf<String, Any>(
            "fileDiffInfos" to fileDiffInfos,
            "PrId" to prId,
            "userOA" to userOA,
            "userName" to userName,
            "dataIds" to listOf(67)
        )
        val payload = mapOf("prLLMRequestInfo" to requestInfo)
        return executeApi("triggerAiReview", triggerAiReviewUrl, objectMapper.writeValueAsString(payload))
    }

    fun handleAiReviewIssue(issueId: Long, issueStatus: Int, issueHandleEmpOa: String, issueRemark: String? = null): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "issueId" to issueId,
            "issueStatus" to issueStatus,
            "issueHandleEmpOa" to issueHandleEmpOa
        )
        issueRemark?.trim()?.takeIf { it.isNotEmpty() }?.let {
            payload["issueIgnoreReason"] = it
        }
        return executeApi("handleAiReviewIssue", aiHandleIssueUrl, objectMapper.writeValueAsString(payload))
    }

    fun fetchRepoMemberRole(sshPath: String, userName: String): HttpResponse<String> {
        val payload = mapOf(
            "sshPath" to sshPath,
            "userName" to userName
        )
        return executeApi("fetchRepoMemberRole", repoMemberRoleUrl, objectMapper.writeValueAsString(payload))
    }

    fun fetchDevelopers(sshPath: String, keyword: String = ""): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "sshPath" to sshPath,
            "keyword" to keyword
        )
        return executeApi("fetchDevelopers", developersUrl, objectMapper.writeValueAsString(payload))
    }

    fun canCreatePr(sshPath: String, sourceBranch: String, targetBranch: String): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "sshPath" to sshPath,
            "sourceBranch" to sourceBranch,
            "targetBranch" to targetBranch
        )
        return executeApi("canCreatePr", canCreatePrUrl, objectMapper.writeValueAsString(payload))
    }

    fun createPr(
        sshPath: String,
        title: String,
        head: String,
        base: String,
        body: String,
        assigneesIds: List<Long>,
        assigneesNum: Int,
        primaryAssigneesIds: List<Long>,
        primaryAssigneesNum: Int,
        pruneBranch: Boolean,
        defaultMergeType: String
    ): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "sshPath" to sshPath,
            "title" to title,
            "head" to head,
            "base" to base,
            "body" to body,
            "assigneesIds" to assigneesIds,
            "assigneesNum" to assigneesNum,
            "primaryAssigneesIds" to primaryAssigneesIds,
            "primaryAssigneesNum" to primaryAssigneesNum,
            "pruneBranch" to pruneBranch,
            "defaultMergeType" to defaultMergeType
        )
        return executeApi("createPr", createPrUrl, objectMapper.writeValueAsString(payload))
    }

    private fun executeApi(apiName: String, url: String, requestBody: String): HttpResponse<String> {
        PrManagerFileLogger.info("API[$apiName] request url=$url body=$requestBody")
        return try {
            val response = httpClient.postJson(url, requestBody)
            val bodyContent = response.body().orEmpty()
            PrManagerFileLogger.info("API[$apiName] response status=${response.statusCode()} body=$bodyContent")
            response
        } catch (e: Exception) {
            PrManagerFileLogger.error("API[$apiName] failed url=$url", e)
            throw e
        }
    }
}
