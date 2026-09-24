package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.support.QrEncoder
import io.github.scdouglas1999.tally.support.TallySupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A port of the plugin's `QrEncoderTests.cs`: an independent decoder reads back what the encoder wrote. */
class QrEncoderTest {
    // ================= Independent decoder (test-only) =================

    private class Info(
        val total: Int,
        val ec: Int,
        val g1: Int,
        val g1d: Int,
        val g2: Int,
        val g2d: Int,
    ) {
        val data: Int get() = g1 * g1d + g2 * g2d
        val blocks: Int get() = g1 + g2
    }

    // Independent copy of the level-M table (index = version).
    private val t =
        arrayOf(
            Info(0, 0, 0, 0, 0, 0),
            Info(26, 10, 1, 16, 0, 0),
            Info(44, 16, 1, 28, 0, 0),
            Info(70, 26, 1, 44, 0, 0),
            Info(100, 18, 2, 32, 0, 0),
            Info(134, 24, 2, 43, 0, 0),
            Info(172, 16, 4, 27, 0, 0),
            Info(196, 18, 4, 31, 0, 0),
            Info(242, 22, 2, 38, 2, 39),
            Info(292, 22, 3, 36, 2, 37),
            Info(346, 26, 4, 43, 1, 44),
            Info(404, 30, 1, 50, 4, 51),
            Info(466, 22, 6, 36, 2, 37),
            Info(532, 22, 8, 37, 1, 38),
            Info(581, 24, 4, 40, 5, 41),
            Info(655, 24, 5, 41, 5, 42),
        )

    private val align =
        arrayOf(
            intArrayOf(),
            intArrayOf(),
            intArrayOf(6, 18),
            intArrayOf(6, 22),
            intArrayOf(6, 26),
            intArrayOf(6, 30),
            intArrayOf(6, 34),
            intArrayOf(6, 22, 38),
            intArrayOf(6, 24, 42),
            intArrayOf(6, 26, 46),
            intArrayOf(6, 28, 50),
            intArrayOf(6, 30, 54),
            intArrayOf(6, 32, 58),
            intArrayOf(6, 34, 62),
            intArrayOf(6, 26, 46, 66),
            intArrayOf(6, 26, 48, 70),
        )

    private fun versionOf(m: Array<BooleanArray>): Int = (m.size - 17) / 4

    // Recompute the function-module map purely from the version.
    private fun functionMap(version: Int): Array<BooleanArray> {
        val size = 17 + 4 * version
        val f =
            Array(size) { y ->
                BooleanArray(size) { x ->
                    // finder+separator blocks plus the format/dark-module strips
                    (x <= 8 && y <= 8) || (x >= size - 8 && y <= 8) || (x <= 8 && y >= size - 8)
                }
            }
        for (i in 0 until size) {
            f[6][i] = true
            f[i][6] = true
        }
        val ap = align[version]
        for (i in ap.indices) {
            for (j in ap.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == ap.lastIndex) || (i == ap.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) f[ap[j] + dy][ap[i] + dx] = true
            }
        }
        if (version >= 7) {
            for (i in 0 until 18) {
                val a = size - 11 + i % 3
                val b = i / 3
                f[b][a] = true
                f[a][b] = true
            }
        }
        return f
    }

    private fun maskBit(
        mask: Int,
        x: Int,
        y: Int,
    ): Boolean =
        when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
            else -> throw IllegalArgumentException("mask")
        }

    private fun readFormat(
        m: Array<BooleanArray>,
        secondCopy: Boolean,
    ): Int {
        val size = m.size
        var bits = 0

        fun bit(
            i: Int,
            v: Boolean,
        ) {
            if (v) bits = bits or (1 shl i)
        }
        if (!secondCopy) {
            for (i in 0..5) bit(i, m[i][8])
            bit(6, m[7][8])
            bit(7, m[8][8])
            bit(8, m[8][7])
            for (i in 9 until 15) bit(i, m[8][14 - i])
        } else {
            for (i in 0 until 8) bit(i, m[8][size - 1 - i])
            for (i in 8 until 15) bit(i, m[size - 15 + i][8])
        }
        return bits
    }

    // Unmask + zig-zag walk -> raw codeword sequence (data and EC interleaved).
    private fun extractCodewords(m: Array<BooleanArray>): IntArray {
        val size = m.size
        val version = versionOf(m)
        val f = functionMap(version)
        val mask = ((readFormat(m, false) xor 0x5412) shr 10) and 7
        val u = Array(size) { y -> BooleanArray(size) { x -> if (!f[y][x] && maskBit(mask, x, y)) !m[y][x] else m[y][x] } }
        val bits = ArrayList<Boolean>()
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val y = if (((right + 1) and 2) == 0) size - 1 - vert else vert
                    if (!f[y][x]) bits.add(u[y][x])
                }
            }
            right -= 2
        }
        val total = t[version].total
        val cw = IntArray(total)
        for (i in 0 until total * 8) if (bits[i]) cw[i shr 3] = cw[i shr 3] or (0x80 ushr (i and 7))
        return cw
    }

    // De-interleave into per-block data codewords.
    private fun deinterleave(
        cw: IntArray,
        version: Int,
    ): List<IntArray> {
        val info = t[version]
        val nb = info.blocks
        val lens = IntArray(nb) { b -> if (b < info.g1) info.g1d else info.g2d }
        val blocks = List(nb) { IntArray(lens[it]) }
        var idx = 0
        val maxLen = maxOf(info.g1d, info.g2d)
        for (i in 0 until maxLen) for (b in 0 until nb) if (i < lens[b]) blocks[b][i] = cw[idx++]
        idx += nb * info.ec // skip interleaved EC codewords
        assertEquals(cw.size, idx)
        return blocks
    }

    private fun decode(m: Array<BooleanArray>): String {
        val version = versionOf(m)
        val stream = deinterleave(extractCodewords(m), version).flatMap { it.toList() }
        var pos = 0

        fun getBits(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((stream[pos shr 3] shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        assertEquals(0b0100, getBits(4)) // byte mode
        val count = getBits(if (version <= 9) 8 else 16)
        val payload = ByteArray(count) { getBits(8).toByte() }
        return String(payload, Charsets.UTF_8)
    }

    // GF(256) helpers for the test (independent of the encoder).
    private fun gfMul(
        a0: Int,
        b0: Int,
    ): Int {
        var a = a0
        var b = b0
        var p = 0
        while (b > 0) {
            if ((b and 1) != 0) p = p xor a
            a = a shl 1
            if (a >= 256) a = a xor 0x11D
            b = b shr 1
        }
        return p
    }

    private fun rsGenerator(degree: Int): IntArray {
        val gen = IntArray(degree)
        gen[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                gen[j] = gfMul(gen[j], root)
                if (j + 1 < degree) gen[j] = gen[j] xor gen[j + 1]
            }
            root = gfMul(root, 2)
        }
        return gen
    }

    private fun stringOf(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz-._~:/?#[]@!$&'()*+,;="
        return buildString { repeat(len) { append(chars[it % chars.length]) } }
    }

    // ================= Tests =================

    @Test
    fun `size matches version`() {
        val m = QrEncoder.encode("HELLO")
        assertEquals(21, m.size)
        assertEquals(21, m[0].size)
        // 100 ASCII bytes -> version 6 (capacities: v5-M=84 < 100 <= v6-M=106)
        val prefix = "http://example.com/"
        val m6 = QrEncoder.encode(prefix + "a".repeat(100 - prefix.length))
        assertEquals(41, m6.size)
        assertEquals(m6.size, 17 + 4 * versionOf(m6))
    }

    @Test
    fun `function patterns are correct`() {
        val m = QrEncoder.encode("HELLO")
        val n = m.size
        val v = versionOf(m)
        for ((ox, oy) in listOf(0 to 0, (n - 7) to 0, 0 to (n - 7))) {
            for (y in 0 until 7) {
                for (x in 0 until 7) {
                    val d = maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3))
                    assertEquals(d != 2, m[oy + y][ox + x])
                }
            }
        }
        for (x in 8..n - 9) assertEquals(x % 2 == 0, m[6][x])
        for (y in 8..n - 9) assertEquals(y % 2 == 0, m[y][6])
        assertTrue(m[4 * v + 9][8]) // dark module
    }

    @Test
    fun `format info is valid BCH at level M`() {
        val m = QrEncoder.encode("HELLO")
        val fmt1 = readFormat(m, false)
        val fmt2 = readFormat(m, true)
        assertEquals(fmt1, fmt2)
        val unmasked = fmt1 xor 0x5412
        var r = unmasked
        for (i in 14 downTo 10) if (((r shr i) and 1) != 0) r = r xor (0x537 shl (i - 10))
        assertEquals(0, r)
        assertEquals(0, (unmasked shr 13) and 3) // EC level bits = 00 (M)
        assertTrue(((unmasked shr 10) and 7) in 0..7)
    }

    @Test
    fun `round trip of fixed strings`() {
        listOf("HELLO", "http://192.168.1.50:8096/JellyTV/Get", "Zoë’s TV – ¡vamos!", TallySupport.PATREON_URL)
            .forEach { assertEquals(it, decode(QrEncoder.encode(it))) }
    }

    @Test
    fun `round trip of a 150 character url`() {
        val url = ("http://192.168.1.50:8096/JellyTV/Get?q=" + "x".repeat(200)).substring(0, 150)
        val m = QrEncoder.encode(url)
        assertEquals(49, m.size) // version 8 (v7-M cap 122 < 150 <= v8-M cap 152)
        assertEquals(url, decode(m))
    }

    @Test
    fun `round trip at every version`() {
        // Max byte-mode payload for level M: largest n with 4 + cci + 8n + 4 <= dataCw*8
        for (v in 1..15) {
            val cci = if (v <= 9) 8 else 16
            val cap = (t[v].data * 8 - 8 - cci) / 8
            val s = stringOf(cap)
            val m = QrEncoder.encode(s)
            assertEquals(17 + 4 * v, m.size)
            assertEquals(s, decode(m))
        }
    }

    @Test
    fun `reed solomon remainder is zero`() {
        // Version 1-M: single block of 16 data + 10 EC codewords.
        val cw = extractCodewords(QrEncoder.encode("HELLO"))
        assertEquals(26, cw.size)
        val gen = rsGenerator(10)
        val work = cw.copyOf()
        var i = 0
        while (i + 10 < work.size) {
            val factor = work[i]
            if (factor != 0) {
                work[i] = 0
                for (j in 0 until 10) work[i + 1 + j] = work[i + 1 + j] xor gfMul(gen[j], factor)
            }
            i++
        }
        assertTrue(work.all { it == 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too long throws`() {
        QrEncoder.encode("x".repeat(3000))
    }

    @Test
    fun `release notes are cut at the support marker`() {
        val notes = "Tally 2.1.0\n\n- Downloads\n\n<!-- tally-support -->\n---\n*Tally is free.*"
        val cut = TallySupport.cutReleaseNotes(notes)
        assertEquals("Tally 2.1.0\n\n- Downloads", cut)
        assertFalse(cut.contains("Patreon"))
        assertEquals("No marker here", TallySupport.cutReleaseNotes("No marker here"))
    }
}
