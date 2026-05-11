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
    private val aiReviewDetailUrl: String,
    private val aiHandleIssueUrl: String
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

    fun reviewPass(id: Long): HttpResponse<String> {
        val payload = mapOf("id" to id)
        return executeApi("reviewPass", reviewUrl, objectMapper.writeValueAsString(payload))
    }

    fun mergePr(id: Long, commitMsg: String, extMsg: String, deleteBranchAfterMerged: Boolean): HttpResponse<String> {
        val payload = mapOf(
            "id" to id,
            "action" to "delete",
            "commitMsg" to commitMsg,
            "extMsg" to extMsg,
            "deleteBranchAfterMerged" to deleteBranchAfterMerged
        )
        return executeApi("mergePr", mergeUrl, objectMapper.writeValueAsString(payload))
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
        return executeApi("fetchAiReviewOverview", aiReviewDetailUrl, objectMapper.writeValueAsString(payload))
    }

    fun fetchAiReviewDetail(prId: Long, filePath: String): HttpResponse<String> {
        val payload = mapOf(
            "prId" to prId,
            "filePath" to filePath
        )
        return executeApi("fetchAiReviewDetail", aiReviewDetailUrl, objectMapper.writeValueAsString(payload))
    }

    fun handleAiReviewIssue(issueId: Long, issueStatus: Int, issueHandleEmpOa: String): HttpResponse<String> {
        val payload = mapOf(
            "issueId" to issueId,
            "issueStatus" to issueStatus,
            "issueHandleEmpOa" to issueHandleEmpOa
        )
        return executeApi("handleAiReviewIssue", aiHandleIssueUrl, objectMapper.writeValueAsString(payload))
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
