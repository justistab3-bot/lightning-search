package com.heikeji.phonesearch.protocol.core.crypto

import com.heikeji.phonesearch.protocol.invalidMaterial
import java.nio.charset.StandardCharsets

/**
 * 原实现 P0.e 的逐行移植：**自定义位序 DES**。
 *
 * 这不是标准 JCE DES，以下细节都不能"顺手优化掉"，否则服务端会拒绝签名：
 *  - 输入按 US-ASCII 处理，只接受可打印字符（0x20..0x7E）；
 *  - 位提取是**低位在前**：`(byte >>> (i % 8)) & 1`；
 *  - 密文每字节编码成**四个字符**：`'0' + reverse4(lowNibble) + '0' + reverse4(highNibble)`；
 *  - 补齐：补到 8 的倍数，中间补 0，**最后一个字节写补齐长度**（1..8；整除时补满 8 字节）；
 *  - 密钥调度里的 PC-2 表含一个非标准项（第 6 组为 `...,32,46`，标准 DES 为 `...,32,47`），保持原样。
 *
 * 表名沿用标准 DES 命名：PC1 作用于密钥，IP/FP 作用于数据块（原实现里这两张表的下标顺序相反）。
 *
 * 正确性由 tools/des-oracle 下的独立 Java oracle 固定向量保证，见 DesCodecTest。
 */
object DesCodec {

    private val HEX = "0123456789abcdef".toCharArray()

    /** 密钥置换 PC-1（原 f711e），64 -> 56。 */
    private val PC1 = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 62, 54, 46, 38,
        30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36,
        28, 20, 12, 4, 27, 19, 11, 3,
    )

    /** 初始置换 IP（原 f709c），作用于数据块。 */
    private val IP = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    )

    /** 逆初始置换 FP（原 f710d），作用于数据块。 */
    private val FP = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )

    /**
     * 压缩置换 PC-2（原 f），56 -> 48。
     * 注意第 6 组末尾是 46 而非标准 DES 的 47 —— 这是原实现的非标准处，必须保留。
     */
    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
        22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 46,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    /** 每轮左移位数（原 f712g）。 */
    private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    /** 扩展置换 E（原 f713h），32 -> 48。 */
    private val E = intArrayOf(
        31, 0, 1, 2, 3, 4, 3, 4, 5, 6, 7, 8,
        7, 8, 9, 10, 11, 12, 11, 12, 13, 14, 15, 16,
        15, 16, 17, 18, 19, 20, 19, 20, 21, 22, 23, 24,
        23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31, 0,
    )

    /** 轮函数末置换 P（原 f714i），32 -> 32。 */
    private val P = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25,
        4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8,
        18, 12, 29, 5, 21, 10, 3, 24,
    )

    /** S 盒（原 f715j），8 组 x 64 项。 */
    private val SBOXES = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    /**
     * 对任意 ASCII 明文做加密编码（等价于原 P0.e.d 去掉 random10/明文拼装后的部分）。
     *
     * 与原实现一致：明文必须**非空**且全部是可打印 ASCII。
     *
     * @return 长度为 `4 * 8 * (plain.length / 8 + 1)` 的字符串（整除时也会补满一整块），
     *         字符集为 `0-9a-f`。
     */
    fun encode(plain: String, key: String): String {
        requirePrintableAscii(plain, requireNonEmpty = true)
        val padded = pad(plain.toByteArray(StandardCharsets.US_ASCII))
        val encrypted = des(padded, key, decrypt = false)

        val out = CharArray(encrypted.size * 4)
        for (i in encrypted.indices) {
            val base = i * 4
            val b = encrypted[i].toInt() and 0xFF
            out[base] = '0'
            out[base + 1] = HEX[reverse4(b and 0x0F)]
            out[base + 2] = '0'
            out[base + 3] = HEX[reverse4((b ushr 4) and 0x0F)]
        }
        return String(out)
    }

    /**
     * 解密（等价于原 P0.e.f）。校验分组格式、补齐长度与补零，并拒绝非可打印 ASCII 结果。
     */
    fun decode(cipher: String, key: String): String {
        if (cipher.isEmpty() || cipher.length > 16_384 || cipher.length % 32 != 0) {
            throw invalidMaterial()
        }
        val size = cipher.length / 4
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            val base = i * 4
            val low = hexValue(cipher[base + 1])
            val high = hexValue(cipher[base + 3])
            if (cipher[base] != '0' || cipher[base + 2] != '0' || low < 0 || high < 0) {
                throw invalidMaterial()
            }
            bytes[i] = (reverse4(low) or (reverse4(high) shl 4)).toByte()
        }

        val decrypted = des(bytes, key, decrypt = true)
        val padLength = decrypted[decrypted.size - 1].toInt() and 0xFF
        if (padLength < 1 || padLength > 8 || padLength > decrypted.size) {
            throw invalidMaterial()
        }
        val dataLength = decrypted.size - padLength
        for (i in dataLength until decrypted.size - 1) {
            if (decrypted[i] != 0.toByte()) throw invalidMaterial()
        }
        val text = String(decrypted, 0, dataLength, StandardCharsets.US_ASCII)
        requirePrintableAscii(text, requireNonEmpty = true)
        return text
    }

    // ---------------------------------------------------------------- 内部实现

    /** 原 P0.e.i：拒绝空串（可选）与非可打印 ASCII。 */
    internal fun requirePrintableAscii(value: String?, requireNonEmpty: Boolean) {
        if (value == null || (requireNonEmpty && value.isEmpty())) throw invalidMaterial()
        for (c in value) {
            if (c < ' ' || c > '~') throw invalidMaterial()
        }
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val padLength = 8 - (bytes.size % 8)
        val out = bytes.copyOf(bytes.size + padLength)
        out[out.size - 1] = padLength.toByte()
        return out
    }

    /** 原 P0.e.e：16 轮 DES。decrypt=true 时逆序使用子密钥。 */
    private fun des(block: ByteArray, key: String, decrypt: Boolean): ByteArray {
        val keyBits = permute(bits(key.toByteArray(StandardCharsets.US_ASCII), 0), PC1)

        val subkeys = Array(16) { IntArray(48) }
        var current = keyBits
        for (round in 0 until 16) {
            val shifted = IntArray(56)
            for (i in 0 until 56) {
                shifted[i] = current[(((i % 28) + SHIFTS[round]) % 28) + ((i / 28) * 28)]
            }
            subkeys[round] = permute(shifted, PC2)
            current = shifted
        }

        val out = ByteArray(block.size)
        var offset = 0
        while (offset < block.size) {
            val blockBits = permute(bits(block, offset), IP)
            var left = blockBits.copyOfRange(0, 32)
            var right = blockBits.copyOfRange(32, 64)

            for (round in 0 until 16) {
                val expanded = permute(right, E)
                val subkey = subkeys[if (decrypt) 15 - round else round]
                for (i in 0 until 48) {
                    expanded[i] = expanded[i] xor subkey[i]
                }

                val substituted = IntArray(32)
                for (box in 0 until 8) {
                    val b = box * 6
                    val row = expanded[b] * 2 + expanded[b + 5]
                    val col = expanded[b + 3] * 2 + expanded[b + 2] * 4 +
                        expanded[b + 1] * 8 + expanded[b + 4]
                    val value = SBOXES[box][row * 16 + col]
                    for (k in 0 until 4) {
                        substituted[box * 4 + k] = (value ushr (3 - k)) and 1
                    }
                }

                val permuted = permute(substituted, P)
                for (i in 0 until 32) {
                    left[i] = left[i] xor permuted[i]
                }

                // 原实现：最后一轮不交换左右半区。
                if (round != 15) {
                    val tmp = right
                    right = left
                    left = tmp
                }
            }

            val combined = IntArray(64)
            System.arraycopy(left, 0, combined, 0, 32)
            System.arraycopy(right, 0, combined, 32, 32)

            val finalBits = permute(combined, FP)
            for (i in 0 until 64) {
                val index = (i / 8) + offset
                out[index] = (out[index].toInt() or (finalBits[i] shl (i % 8))).toByte()
            }
            offset += 8
        }
        return out
    }

    /** 原 P0.e.h：按表做位置置换。 */
    private fun permute(input: IntArray, table: IntArray): IntArray {
        val out = IntArray(table.size)
        for (i in table.indices) {
            out[i] = input[table[i]]
        }
        return out
    }

    /** 原 P0.e.k：取 8 字节为 64 位，**低位在前**。 */
    private fun bits(data: ByteArray, offset: Int): IntArray {
        val out = IntArray(64)
        for (i in 0 until 64) {
            out[i] = (data[(i / 8) + offset].toInt() ushr (i % 8)) and 1
        }
        return out
    }

    /** 原 P0.e.j：把一个 4 位值按位反转。 */
    private fun reverse4(value: Int): Int =
        ((value and 8) ushr 3) or ((value and 1) shl 3) or
            ((value and 2) shl 1) or ((value and 4) ushr 1)

    /** 原 P0.e.f 中的字符取值：只接受 0-9a-f。 */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'W'
        else -> -1
    }

    init {
        // 表长度自检，避免移植时漏项。
        check(PC1.size == 56) { "PC1 size" }
        check(IP.size == 64) { "IP size" }
        check(FP.size == 64) { "FP size" }
        check(PC2.size == 48) { "PC2 size" }
        check(SHIFTS.size == 16) { "SHIFTS size" }
        check(E.size == 48) { "E size" }
        check(P.size == 32) { "P size" }
        check(SBOXES.size == 8 && SBOXES.all { it.size == 64 }) { "SBOXES size" }
    }
}
