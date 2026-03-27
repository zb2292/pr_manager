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
    private val mergeUrl: String
) {
    fun fetchPrList(requestBody: String): HttpResponse<String> {
        return httpClient.postJson(listUrl, requestBody)
    }

    fun fetchPrDetail(prId: Long): HttpResponse<String> {
        val payload = mapOf("prId" to prId)
        return httpClient.postJson(detailUrl, objectMapper.writeValueAsString(payload))
    }

    fun createNote(prId: Long, commitId: String, filePath: String, context: String, codeLine: Int): HttpResponse<String> {
        val payload = mapOf(
            "prId" to prId,
            "commitId" to commitId,
            "filePath" to filePath,
            "context" to context,
            "codeLine" to codeLine
        )
        return httpClient.postJson(noteUrl, objectMapper.writeValueAsString(payload))
    }

    fun replyNote(
        prId: Long,
        context: String,
        filePath: String,
        codeLine: Int,
        nodeId: String? = null,
        replyFloorNum: Int? = null
    ): HttpResponse<String> {
        val payload = linkedMapOf<String, Any>(
            "prId" to prId,
            "context" to context,
            "filePath" to filePath,
            "codeLine" to codeLine
        )
        if (!nodeId.isNullOrBlank()) {
            payload["nodeId"] = nodeId
        }
        if (replyFloorNum != null) {
            payload["replyFloorNum"] = replyFloorNum
        }
        return httpClient.postJson(replyUrl, objectMapper.writeValueAsString(payload))
    }

    fun resolveNote(prIid: Long, nodeId: String, sshPath: String): HttpResponse<String> {
        val payload = mapOf(
            "prIid" to prIid,
            "resolve" to true,
            "nodeId" to nodeId,
            "sshPath" to sshPath
        )
        return httpClient.postJson(resolveUrl, objectMapper.writeValueAsString(payload))
    }

    fun reviewPass(id: Long): HttpResponse<String> {
        val payload = mapOf("id" to id)
        return httpClient.postJson(reviewUrl, objectMapper.writeValueAsString(payload))
    }

    fun mergePr(id: Long, commitMsg: String, extMsg: String, deleteBranchAfterMerged: Boolean): HttpResponse<String> {
        val payload = mapOf(
            "id" to id,
            "action" to "delete",
            "commitMsg" to commitMsg,
            "extMsg" to extMsg,
            "deleteBranchAfterMerged" to deleteBranchAfterMerged
        )
        return httpClient.postJson(mergeUrl, objectMapper.writeValueAsString(payload))
    }

    fun fetchNoteList(sshPath: String, iid: Long): HttpResponse<String> {
        val payload = mapOf(
            "sshPath" to sshPath,
            "iid" to iid
        )
        return httpClient.postJson(noteListUrl, objectMapper.writeValueAsString(payload))
    }
}
