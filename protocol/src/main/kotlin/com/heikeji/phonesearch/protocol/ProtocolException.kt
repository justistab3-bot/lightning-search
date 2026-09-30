package com.heikeji.phonesearch.protocol

/**
 * 协议层统一异常。
 *
 * 原 APK 在各处抛 IOException；这里用独立类型，便于 :app 区分「协议/业务失败」与「网络失败」。
 * 文案沿用原实现，方便对照反编译结果排查。
 */
class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 协议材料/参数不合法（对应原实现的 P0.e.g()）。 */
internal fun invalidMaterial(): ProtocolException =
    ProtocolException("设备签名材料无效或与当前身份不匹配")
