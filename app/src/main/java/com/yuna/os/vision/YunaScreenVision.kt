package com.yuna.os.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class YunaScreenVision(private val context: Context, private val cloudBaseUrl: String, private val appToken: String) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    fun initProjection(resultCode: Int, data: Intent) {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        val dm = context.resources.displayMetrics
        val w = 720
        val h = (dm.heightPixels * 720f / dm.widthPixels).toInt().coerceAtLeast(720)
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "YunaVision", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun captureAndAskVision(prompt: String): String {
        val img = imageReader?.acquireLatestImage() ?: return ""
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            bmp.recycle()

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 68, out)
            cropped.recycle()
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            val conn = (URL("${cloudBaseUrl.trimEnd('/')}/vision").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 12000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Yuna-Token", appToken)
                doOutput = true
            }
            val payload = JSONObject().put("prompt", prompt).put("image_base64", b64).toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(raw).optString("reply", "")
        } catch (_: Exception) {
            ""
        } finally {
            img.close()
        }
    }

    fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null; imageReader = null; projection = null
    }
}
