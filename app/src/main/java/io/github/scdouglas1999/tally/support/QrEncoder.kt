package io.github.scdouglas1999.tally.support

/**
 * Minimal dependency-free QR Code encoder (ISO/IEC 18004), byte mode, error-correction level M, versions 1 through
 * 15. A port of the plugin's `server/Jellyfin.Plugin.Tally/Services/QrEncoder.cs` (same tables, same mask choice).
 */
object QrEncoder {
    private class EcInfo(
        val totalCodewords: Int,
        val ecPerBlock: Int,
        val group1Blocks: Int,
        val group1Data: Int,
        val group2Blocks: Int,
        val group2Data: Int,
    ) {
        val dataCodewords: Int get() = group1Blocks * group1Data + group2Blocks * group2Data
        val numBlocks: Int get() = group1Blocks + group2Blocks
    }

    // Level-M block structure for versions 1..15 (index = version):
    //   version: total codewords, ec codewords per block, g1 blocks x data cw, g2 blocks x data cw
    private val table =
        arrayOf(
            EcInfo(0, 0, 0, 0, 0, 0),
            EcInfo(26, 10, 1, 16, 0, 0),
            EcInfo(44, 16, 1, 28, 0, 0),
            EcInfo(70, 26, 1, 44, 0, 0),
            EcInfo(100, 18, 2, 32, 0, 0),
            EcInfo(134, 24, 2, 43, 0, 0),
            EcInfo(172, 16, 4, 27, 0, 0),
            EcInfo(196, 18, 4, 31, 0, 0),
            EcInfo(242, 22, 2, 38, 2, 39),
            EcInfo(292, 22, 3, 36, 2, 37),
            EcInfo(346, 26, 4, 43, 1, 44),
            EcInfo(404, 30, 1, 50, 4, 51),
            EcInfo(466, 22, 6, 36, 2, 37),
            EcInfo(532, 22, 8, 37, 1, 38),
            EcInfo(581, 24, 4, 40, 5, 41),
            EcInfo(655, 24, 5, 41, 5, 42),
        )

    // Alignment pattern center coordinates, index = version (empty for v1).
    private val alignmentPositions =
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

    /**
     * Encodes [text] (UTF-8, byte mode) at EC level M, choosing the smallest version 1..15 that fits and the best of
     * the 8 mask patterns by the standard penalty rules. Returns the module matrix WITHOUT quiet zone:
     * `result[y][x] == true` means a dark module.
     *
     * @throws IllegalArgumentException when the text does not fit in version 15.
     */
    fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val version =
            (1..15).firstOrNull { v ->
                val ccBits = if (v <= 9) 8 else 16
                4 + ccBits + data.size * 8 <= table[v].dataCodewords * 8
            } ?: throw IllegalArgumentException("Text does not fit in a version-15 QR code (${data.size} bytes).")
        return buildMatrix(addErrorCorrection(buildDataCodewords(data, version), version), version)
    }

    // ---------- Data encoding ----------

    private fun buildDataCodewords(
        data: ByteArray,
        version: Int,
    ): ByteArray {
        val info = table[version]
        val capacityBits = info.dataCodewords * 8
        val bb = BitBuffer()
        bb.append(0b0100, 4) // byte mode
        bb.append(data.size, if (version <= 9) 8 else 16) // char count indicator
        data.forEach { bb.append(it.toInt() and 0xFF, 8) }
        bb.append(0, minOf(4, capacityBits - bb.count)) // terminator
        while (bb.count % 8 != 0) bb.append(0, 1)
        var pad = 0xEC
        while (bb.count < capacityBits) {
            bb.append(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        return bb.toBytes()
    }

    private fun addErrorCorrection(
        dataCodewords: ByteArray,
        version: Int,
    ): ByteArray {
        val info = table[version]
        val numBlocks = info.numBlocks
        val ecLen = info.ecPerBlock
        val gen = rsGenerator(ecLen)
        val blocks = arrayOfNulls<IntArray>(numBlocks)
        val ecs = arrayOfNulls<IntArray>(numBlocks)
        var offset = 0
        for (b in 0 until numBlocks) {
            val len = if (b < info.group1Blocks) info.group1Data else info.group2Data
            val blk = IntArray(len) { dataCodewords[offset + it].toInt() and 0xFF }
            offset += len
            blocks[b] = blk
            ecs[b] = rsRemainder(blk, gen)
        }
        val result = ByteArray(info.totalCodewords)
        var k = 0
        val maxLen = maxOf(info.group1Data, info.group2Data)
        for (i in 0 until maxLen) {
            for (b in 0 until numBlocks) {
                val blk = blocks[b]!!
                if (i < blk.size) result[k++] = blk[i].toByte()
            }
        }
        for (i in 0 until ecLen) {
            for (b in 0 until numBlocks) result[k++] = ecs[b]!![i].toByte()
        }
        return result
    }

    // ---------- Matrix construction ----------

    private fun buildMatrix(
        codewords: ByteArray,
        version: Int,
    ): Array<BooleanArray> {
        val size = 17 + 4 * version
        val m = Array(size) { BooleanArray(size) }
        val f = Array(size) { BooleanArray(size) } // function modules

        fun set(
            x: Int,
            y: Int,
            dark: Boolean,
        ) {
            m[y][x] = dark
            f[y][x] = true
        }

        fun drawFinder(
            cx: Int,
            cy: Int,
        ) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        set(x, y, d != 2 && d != 4)
                    }
                }
            }
        }

        fun drawFormat(mask: Int) {
            val data = mask // EC level M => format bits 00
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) set(8, i, ((bits shr i) and 1) != 0)
            set(8, 7, ((bits shr 6) and 1) != 0)
            set(8, 8, ((bits shr 7) and 1) != 0)
            set(7, 8, ((bits shr 8) and 1) != 0)
            for (i in 9 until 15) set(14 - i, 8, ((bits shr i) and 1) != 0)
            for (i in 0 until 8) set(size - 1 - i, 8, ((bits shr i) and 1) != 0)
            for (i in 8 until 15) set(8, size - 15 + i, ((bits shr i) and 1) != 0)
            set(8, size - 8, true) // dark module
        }

        fun drawVersion() {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = ((bits shr i) and 1) != 0
                val a = size - 11 + i % 3
                val c = i / 3
                set(a, c, b)
                set(c, a, b)
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (!f[y][x] && maskBit(mask, x, y)) m[y][x] = !m[y][x]
                }
            }
        }

        // Timing patterns
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        // Finder patterns + separators (centers)
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        // Alignment patterns (skip the three finder corners)
        val ap = alignmentPositions[version]
        for (i in ap.indices) {
            for (j in ap.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == ap.lastIndex) || (i == ap.lastIndex && j == 0)) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        set(ap[i] + dx, ap[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                    }
                }
            }
        }
        // Reserve format areas (drawn for real later, once per candidate mask)
        drawFormat(0)
        if (version >= 7) drawVersion()

        // Zig-zag data placement, skipping the vertical timing column
        var bit = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val y = if (((right + 1) and 2) == 0) size - 1 - vert else vert
                    if (!f[y][x] && bit < totalBits) {
                        m[y][x] = ((codewords[bit shr 3].toInt() shr (7 - (bit and 7))) and 1) != 0
                        bit++
                    }
                }
            }
            right -= 2
        }

        // Pick the mask with the lowest penalty (ties -> lowest mask index)
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            applyMask(mask)
            drawFormat(mask)
            val score = penaltyScore(m)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
            }
            applyMask(mask)
        }
        applyMask(bestMask)
        drawFormat(bestMask)
        return m
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
            else -> false
        }

    // ---------- Penalty rules (ISO/IEC 18004) ----------
    // Run-length based N1/N3 with finder-ratio (k:k:3k:k:k) detection, matching libqrencode:
    // the quiet zone beyond each edge counts as a light run for N3.

    private fun penaltyScore(m: Array<BooleanArray>): Int {
        val n = m.size
        var score = 0
        // N2: 2x2 same-color blocks
        for (y in 0 until n - 1) {
            for (x in 0 until n - 1) {
                if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) score += 3
            }
        }
        val runs = IntArray(n + 1)
        for (pass in 0 until 2) {
            for (a in 0 until n) {
                // Run lengths: even indices = light runs, odd = dark. runs[0] = -1 when the line starts dark.
                var head = 0
                runs[0] = 1
                for (i in 0 until n) {
                    val dark = if (pass == 0) m[a][i] else m[i][a]
                    if (i == 0 && dark) {
                        runs[0] = -1
                        head = 1
                        runs[head] = 1
                    } else if (i > 0) {
                        val prev = if (pass == 0) m[a][i - 1] else m[i - 1][a]
                        if (dark != prev) {
                            head++
                            runs[head] = 0
                        }
                        runs[head]++
                    } else {
                        runs[head]++
                    }
                }
                val length = head + 1
                for (i in 0 until length) {
                    if (runs[i] >= 5) score += 3 + runs[i] - 5 // N1
                    if ((i and 1) != 0 && i >= 3 && i < length - 2 && runs[i] % 3 == 0) {
                        val fact = runs[i] / 3
                        if (runs[i - 2] == fact && runs[i - 1] == fact && runs[i + 1] == fact && runs[i + 2] == fact) {
                            if (i == 3 || runs[i - 3] >= 4 * fact) {
                                score += 40 // N3, preceded
                            } else if (i + 4 >= length || runs[i + 3] >= 4 * fact) {
                                score += 40 // N3, followed
                            }
                        }
                    }
                }
            }
        }
        // N4: balance of dark modules (integer percentage)
        val darkCount = m.sumOf { row -> row.count { it } }
        score += kotlin.math.abs(100 * darkCount / (n * n) - 50) / 5 * 10
        return score
    }

    // ---------- Reed-Solomon over GF(256), polynomial 0x11D ----------

    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    private fun gfMul(
        a: Int,
        b: Int,
    ): Int = if (a == 0 || b == 0) 0 else gfExp[gfLog[a] + gfLog[b]]

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

    private fun rsRemainder(
        data: IntArray,
        gen: IntArray,
    ): IntArray {
        val rem = IntArray(gen.size)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, rem.size - 1)
            rem[rem.size - 1] = 0
            for (i in rem.indices) rem[i] = rem[i] xor gfMul(gen[i], factor)
        }
        return rem
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val count: Int get() = bits.size

        fun append(
            value: Int,
            length: Int,
        ) {
            for (i in length - 1 downTo 0) bits.add(((value shr i) and 1) != 0)
        }

        fun toBytes(): ByteArray {
            val res = ByteArray((bits.size + 7) / 8)
            for (i in bits.indices) {
                if (bits[i]) res[i shr 3] = (res[i shr 3].toInt() or (0x80 ushr (i and 7))).toByte()
            }
            return res
        }
    }
}
