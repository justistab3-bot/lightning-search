package com.heikeji.phonesearch.protocol.core.crypto

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.core.NetConfig

/**
 * 由 deviceSecret 派生 responseKey（原 P0.f.c）。
 *
 * 结果必须是 **128 个小写十六进制字符**，作为后续标准 RC4 的 UTF-8 密钥。
 * 步骤（不能调换顺序）：
 * ```
 * a = md5Lower("[deviceSecret]@")            // 32
 * a = swapOuterPairs(a, 15)
 * b = md5Lower("@#AIjd83#@6B") + md5Lower("1170") + a   // 96
 * b = swapOuterPairs(b, 3)
 * key = b + md5Lower(b)                      // 128
 * key = swapOuterPairs(key, 60)
 * ```
 */
object ResponseKey {

    private val HEX_128 = Regex("[0-9a-f]{128}")

    fun derive(deviceSecret: String): String {
        val a = Digests.md5Lower("[$deviceSecret]@").toCharArray()
        PairSwap.swap(a, 15)

        val b = (
            Digests.md5Lower(NetConfig.RC4_KEY_SALT) +
                Digests.md5Lower(NetConfig.VC) +
                String(a)
            ).toCharArray()
        PairSwap.swap(b, 3)

        val middle = String(b)
        val key = (middle + Digests.md5Lower(middle)).toCharArray()
        PairSwap.swap(key, 60)

        val result = String(key)
        if (!HEX_128.matches(result)) {
            throw ProtocolException("设备签名材料未生成有效的响应解密密钥")
        }
        return result
    }
}
