package com.heikeji.phonesearch.net

import com.heikeji.phonesearch.protocol.ProtocolException
import java.io.ByteArrayOutputStream

/**
 * multipart/form-data 构造。
 *
 * 搜题的拍照接口和快问 AI 的拍照接口都用它；**图片必须是第一部分**，
 * 服务端按流式解析，字段顺序不能改。
 */
internal object Multipart {

    /** 表单字段名只允许这些字符，防止构造出畸形分段。 */
    private val FIELD_NAME = Regex("[A-Za-z0-9_]+")

    fun build(
        boundary: String,
        image: ByteArray,
        imageFieldName: String = "image",
        params: Map<String, String?>,
    ): ByteArray {
        if (!FIELD_NAME.matches(imageFieldName)) throw ProtocolException("表单字段无效")

        val out = ByteArrayOutputStream(image.size + 4096)
        out.write(
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"$imageFieldName\"; filename=\"image\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        out.write(image)
        out.write("\r\n".toByteArray(Charsets.UTF_8))

        for ((key, value) in params) {
            if (!FIELD_NAME.matches(key)) throw ProtocolException("表单字段无效")
            out.write(
                (
                    "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"$key\"\r\n" +
                        "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8),
            )
            out.write((value ?: "").toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }
}
