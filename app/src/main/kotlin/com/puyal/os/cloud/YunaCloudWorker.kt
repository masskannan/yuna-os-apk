package com.puyal.os.cloud

import android.content.Context
import androidx.work.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class YunaCloudWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    companion object {
        fun enqueue(ctx: Context, prompt: String) {
            val req = OneTimeWorkRequestBuilder<YunaCloudWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("prompt" to prompt))
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }

    override fun doWork(): Result {
        val prompt = inputData.getString("prompt") ?: return Result.success()
        return try {
            val conn = (URL("https://puyal-edge-gateway.masskannan719.workers.dev/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 8000; readTimeout = 8000; doOutput = true
            }
            OutputStreamWriter(conn.outputStream).use { it.write("{\"prompt\":${org.json.JSONObject.quote(prompt)}}") }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Result.success() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
}
