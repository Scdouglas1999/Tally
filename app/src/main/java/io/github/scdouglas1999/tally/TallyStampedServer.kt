package io.github.scdouglas1999.tally

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Tally server plugin hands out this app with its own address written into the APK Signing Block (an extra
 * ID-value pair, which the v2/v3 signature does not cover), so a first launch can add the server without anyone
 * typing an address with a remote. An APK from anywhere else (GitHub, a self-update) simply has no stamp.
 */
object TallyStampedServer {
    /** "JTV\n": must match ApkStamper.PairId in the plugin. */
    private const val PAIR_ID = 0x4A54560A
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val MAX_BLOCK = 8 * 1024 * 1024
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    fun read(context: Context): String? =
        try {
            read(File(context.applicationInfo.sourceDir))
        } catch (e: Exception) {
            Timber.w(e, "Could not look for a stamped server address")
            null
        }

    /** The stamped http(s) address in [apk], or null. Reads a few kilobytes, never the whole file. */
    fun read(apk: File): String? =
        RandomAccessFile(apk, "r").use { file ->
            val length = file.length()
            if (length < EOCD_MIN) return null
            val tailSize = minOf(length, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
            val tail = file.bytes(length - tailSize, tailSize)
            var eocd = -1
            for (i in tailSize - EOCD_MIN downTo 0) {
                if (tail.getInt(i) == EOCD_SIGNATURE && i + EOCD_MIN + (tail.getShort(i + 20).toInt() and 0xFFFF) == tailSize) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) return null
            val cdOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            if (cdOffset < 32 || cdOffset > length) return null

            val footer = file.bytes(cdOffset - 24, 24)
            val magic =
                ByteArray(16).also {
                    footer.position(8)
                    footer.get(it)
                }
            if (!magic.contentEquals(MAGIC)) return null
            val blockSize = footer.getLong(0)
            if (blockSize < 24 || blockSize > MAX_BLOCK || blockSize + 8 > cdOffset) return null

            val pairs = file.bytes(cdOffset - blockSize, (blockSize - 24).toInt())
            var p = 0
            while (p + 12 <= pairs.limit()) {
                val pairLength = pairs.getLong(p)
                if (pairLength < 4 || p + 8 + pairLength > pairs.limit()) return null
                if (pairs.getInt(p + 8) == PAIR_ID) {
                    val value =
                        ByteArray((pairLength - 4).toInt()).also {
                            pairs.position(p + 12)
                            pairs.get(it)
                        }
                    return String(value, Charsets.UTF_8).trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
                }
                p += (8 + pairLength).toInt()
            }
            null
        }

    private fun RandomAccessFile.bytes(
        offset: Long,
        count: Int,
    ): ByteBuffer {
        val buffer = ByteArray(count)
        seek(offset)
        readFully(buffer)
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
    }
}
