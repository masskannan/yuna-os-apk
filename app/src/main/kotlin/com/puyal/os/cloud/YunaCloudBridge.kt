package com.puyal.os.cloud

import java.net.HttpURLConnection
import java.net.URL

object YunaCloudBridge {
    private const val GATEWAY = "https://puyal-edge-gateway.masskannan719.workers.dev/api/health"

    fun pingCloudBrain(): String = try {
        val conn = (URL(GATEWAY).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().readText().take(240)
        "HTTP $code: $body"
    } catch (e: Exception) {
        "Offline / Cloud Unreachable: ${e.message}"
    }
}
