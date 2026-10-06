package com.heikeji.phonesearch.protocol.core.sign

import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.protocol.core.crypto.DesCodec
import com.heikeji.phonesearch.protocol.invalidMaterial
import java.security.SecureRandom

/**
 * 设备签名材料初始化（原 P0.e.d 与 P0.e.c）。
 *
 * signA = DES.encode("8&%d*##" + random10 + "##" + certificateDigest + "##" + cuid, key="@fG2SuLA")
 * 服务端返回 signB，用 key = random10[0..4] + "#G4" 解开后校验 random10 前缀，取第 12 位起的 deviceSecret。
 */
object SignA {

    private val RANDOM10_REGEX = Regex("[A-Za-z0-9]{10}")
    private val DIGEST_REGEX = Regex("[0-9a-f]{32}")

    private val random = SecureRandom()

    /** 生成 10 位随机串，字符集 [A-Za-z0-9]。 */
    fun random10(): String {
        val alphabet = NetConfig.RANDOM10_ALPHABET
        val chars = CharArray(NetConfig.RANDOM10_LENGTH)
        for (i in chars.indices) {
            chars[i] = alphabet[random.nextInt(alphabet.length)]
        }
        return String(chars)
    }

    /** 构造并加密 signA。 */
    fun build(cuid: String, random10: String): String {
        requireCuid(cuid)
        if (!RANDOM10_REGEX.matches(random10)) throw invalidMaterial()

        val plain = NetConfig.SIGN_A_PLAIN_PREFIX +
            random10 +
            "##" +
            NetConfig.CERTIFICATE_DIGEST +
            "##" +
            cuid
        DesCodec.requirePrintableAscii(plain, requireNonEmpty = true)
        return DesCodec.encode(plain, NetConfig.SIGN_A_KEY)
    }

    /**
     * 解开 signA 与 signB，校验身份绑定并返回 deviceSecret。
     *
     * @throws com.heikeji.phonesearch.protocol.ProtocolException 任何一步不匹配
     */
    fun parseDeviceSecret(cuid: String, signA: String, signB: String): String {
        requireCuid(cuid)

        val decodedA = DesCodec.decode(signA, NetConfig.SIGN_A_KEY)
        if (decodedA.length < 53 ||
            !decodedA.startsWith(NetConfig.SIGN_A_PLAIN_PREFIX) ||
            decodedA.substring(17, 19) != "##" ||
            decodedA.substring(51, 53) != "##" ||
            decodedA.substring(53) != cuid ||
            decodedA.substring(19, 51) != NetConfig.CERTIFICATE_DIGEST
        ) {
            throw invalidMaterial()
        }

        val embeddedRandom10 = decodedA.substring(7, 17)
        if (!RANDOM10_REGEX.matches(embeddedRandom10)) throw invalidMaterial()

        val decodedB = DesCodec.decode(
            signB,
            embeddedRandom10.substring(0, 5) + NetConfig.SIGN_B_KEY_SUFFIX,
        )
        if (decodedB.length == 22 && decodedB.substring(0, 10) == embeddedRandom10) {
            return decodedB.substring(12)
        }
        throw invalidMaterial()
    }

    private fun requireCuid(cuid: String) {
        DesCodec.requirePrintableAscii(cuid, requireNonEmpty = true)
        if (cuid.length > 1024) throw invalidMaterial()
        if (!DIGEST_REGEX.matches(NetConfig.CERTIFICATE_DIGEST)) throw invalidMaterial()
    }
}
