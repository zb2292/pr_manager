package com.gitee.prviewer.service

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpRequestClient(
    connectTimeoutSeconds: Long = 8,
    private val pluginAuthorProvider: (() -> String?)? = null
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .build()

    fun postJson(url: String, body: String, timeoutSeconds: Long = 12): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")

        pluginAuthorProvider?.invoke()?.takeIf { it.isNotBlank() }?.let {
            builder.header("userName", it)
        }

        val request = builder
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
