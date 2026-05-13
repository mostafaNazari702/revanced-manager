package app.revanced.manager.network.api

import android.app.Application
import android.util.Log
import app.revanced.manager.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ApiResponseCache internal constructor(
    private val root: File,
    private val defaultFreshnessMillis: Long,
    private val clock: () -> Long,
) {
    constructor(app: Application) : this(
        root = File(app.filesDir, "api_cache").apply { mkdirs() },
        defaultFreshnessMillis = DEFAULT_FRESHNESS_MILLIS,
        clock = System::currentTimeMillis,
    )

    data class Entry(val body: String, val isFresh: Boolean, val ageMillis: Long)

    suspend fun read(key: String): Entry? = withContext(Dispatchers.IO) {
        val bodyFile = bodyFile(key)
        if (!bodyFile.exists() || bodyFile.length() == 0L) return@withContext null

        val body = runCatching { bodyFile.readText(Charsets.UTF_8) }
            .onFailure { Log.w(tag, "ApiResponseCache: failed to read body for $key", it) }
            .getOrNull() ?: return@withContext null

        val meta = readMeta(metaFile(key))
        val now = clock()
        val writtenAt = meta?.writtenAt ?: bodyFile.lastModified()
        val freshUntil = meta?.freshUntil ?: (writtenAt + defaultFreshnessMillis)
        Entry(
            body = body,
            isFresh = now <= freshUntil,
            ageMillis = (now - writtenAt).coerceAtLeast(0L),
        )
    }


    suspend fun write(key: String, body: String, freshForMillis: Long? = null) =
        withContext(Dispatchers.IO) {
            val now = clock()
            val freshUntil = now + (freshForMillis ?: defaultFreshnessMillis).coerceAtLeast(0L)
            runCatching {
                writeAtomically(bodyFile(key), body)
                writeAtomically(metaFile(key), encodeMeta(now, freshUntil))
            }.onFailure { Log.w(tag, "ApiResponseCache: failed to write $key", it) }
            Unit
        }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun bodyFile(key: String): File = File(root, hash(key) + ".body")
    private fun metaFile(key: String): File = File(root, hash(key) + ".meta")

    private data class Meta(val writtenAt: Long, val freshUntil: Long)

    private fun encodeMeta(writtenAt: Long, freshUntil: Long): String =
        "$writtenAt,$freshUntil"

    private fun readMeta(file: File): Meta? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val parts = file.readText(Charsets.UTF_8).trim().split(',')
            if (parts.size != 2) return null
            Meta(parts[0].toLong(), parts[1].toLong())
        } catch (t: IOException) {
            null
        } catch (t: NumberFormatException) {
            null
        }
    }

    private fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    companion object {
        const val DEFAULT_FRESHNESS_MILLIS: Long = 5L * 60L * 1000L
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
