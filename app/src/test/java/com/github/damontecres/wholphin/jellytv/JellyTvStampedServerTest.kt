package com.github.damontecres.wholphin.jellytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JellyTvStampedServerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun pair(
        id: Int,
        value: ByteArray,
    ): ByteArray =
        le(12 + value.size)
            .putLong(4L + value.size)
            .putInt(id)
            .put(value)
            .array()

    /** Entries, a signing block holding [pairs], a central directory and an end record, like a signed APK. */
    private fun apk(
        vararg pairs: ByteArray,
        comment: String = "",
    ): java.io.File {
        val entries = "PK-entries".toByteArray()
        val body = pairs.fold(ByteArray(0)) { acc, it -> acc + it }
        val size = body.size + 24L
        val block = le(8).putLong(size).array() + body + le(8).putLong(size).array() + "APK Sig Block 42".toByteArray()
        val cd = "central-directory".toByteArray()
        val eocd =
            le(22 + comment.length)
                .putInt(0x06054b50)
                .putShort(0)
                .putShort(0)
                .putShort(1)
                .putShort(1)
                .putInt(cd.size)
                .putInt(entries.size + block.size)
                .putShort(comment.length.toShort())
                .put(comment.toByteArray())
                .array()
        val out = ByteArrayOutputStream()
        listOf(entries, block, cd, eocd).forEach(out::write)
        return folder.newFile().also { it.writeBytes(out.toByteArray()) }
    }

    private val signature = pair(0x7109871a, ByteArray(64) { it.toByte() })

    @Test
    fun `the stamped address is found after the signature pair`() =
        assertEquals(
            "http://203.0.113.7:9000",
            JellyTvStampedServer.read(apk(signature, pair(0x4A54560A, "http://203.0.113.7:9000".toByteArray()))),
        )

    @Test
    fun `an end record with a comment does not hide it`() =
        assertEquals(
            "https://tv.example.org",
            JellyTvStampedServer.read(apk(signature, pair(0x4A54560A, "https://tv.example.org".toByteArray()), comment = "ci build")),
        )

    @Test
    fun `an ordinary release has no stamp`() = assertNull(JellyTvStampedServer.read(apk(signature)))

    @Test
    fun `only a web address is accepted`() =
        assertNull(JellyTvStampedServer.read(apk(signature, pair(0x4A54560A, "file:///data/x".toByteArray()))))

    @Test
    fun `a file that is not an apk is ignored`() =
        assertNull(
            JellyTvStampedServer.read(
                folder.newFile().also {
                    it.writeText("not a zip at all, but long enough to look in")
                },
            ),
        )
}
