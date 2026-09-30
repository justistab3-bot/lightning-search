package com.heikeji.phonesearch.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.heikeji.phonesearch.protocol.ProtocolProfile
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 加载答案里的远端图片，用于「点图片看大图」。
 *
 * 只允许 https，限制下载体积与解码尺寸，失败一律返回 null（不抛给调用方）。
 */
object RemoteImageLoader {

    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val MAX_EDGE = 2048
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun load(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", ProtocolProfile.USER_AGENT)
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { readLimited(it, MAX_BYTES) } ?: return null
            decodeSampled(bytes)
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            if (buffer.size() + read > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_EDGE) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }
}
