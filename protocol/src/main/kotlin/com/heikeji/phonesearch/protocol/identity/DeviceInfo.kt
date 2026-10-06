package com.heikeji.phonesearch.protocol.identity

import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.core.codec.Base64NoWrap
import com.heikeji.phonesearch.protocol.core.crypto.Rc4

/**
 * 设备信息上报负载，对应官方 `com.zuoyebang.baseutil.PackageHelper` /
 * `com.baidu.device.DeviceIdHelper.getDeviceInfo`。
 *
 * Android 侧采集原始事实（[Facts]），本类负责组装官方字段结构的 JSON、
 * 用官方 ENTRY_KEY 做 RC4 加密并 Base64 编码，作为 [Getdid] 的 `param`。
 */
object DeviceInfo {

    /** 官方 `PackageHelper.ENTRY_KEY`（getdid 上报负载的 RC4 密钥）。 */
    const val ENTRY_KEY = "msyx6nw\$jwk12.76alvkf"

    /** Android 侧采集的设备事实（拿不到的一律空串/0，与官方语义一致）。 */
    data class Facts(
        val appId: String,
        val osVersion: String,
        val language: String,
        val country: String,
        val brand: String,
        val model: String,
        val sn: String,
        val androidId: String,
        val typewriting: String,
        val powerOnTime: Long,
        val memoryGb: Long,
        val hardDiskBytes: Long,
        val screen: String,
    )

    /** 官方 `DeviceIdHelper.getDeviceInfo` 的字段结构，经 RC4(ENTRY_KEY) 加密后的 Base64。 */
    fun buildPayload(facts: Facts): String {
        val json = JsonObject()
        json.addProperty("did", "")
        json.addProperty("os", "android")
        json.addProperty("appId", facts.appId)
        json.addProperty("imei1", "")
        json.addProperty("imei2", "")
        json.addProperty("oaid", "")
        json.addProperty("sn", facts.sn)
        json.addProperty("androidId", facts.androidId)
        json.addProperty("user", "")
        json.addProperty("osVersion", facts.osVersion)
        json.addProperty("language", facts.language)
        json.addProperty("typewriting", facts.typewriting)
        json.addProperty("browser", "")
        json.addProperty("powerOnTime", facts.powerOnTime)
        json.addProperty("sysUpdateTime", 0)
        json.addProperty("uid", -1)
        json.addProperty("operator", "")
        json.addProperty("country", facts.country)
        json.addProperty("brand", facts.brand)
        json.addProperty("model", facts.model)
        json.addProperty("memory", facts.memoryGb)
        json.addProperty("cpu", "armeabi-v7a")
        json.addProperty("hardDisk", facts.hardDiskBytes)
        json.addProperty("sdkVersion", "4")
        json.addProperty("uidStr", "")
        json.addProperty("screen", facts.screen)

        val encrypted = Rc4.apply(json.toString().toByteArray(Charsets.UTF_8), ENTRY_KEY)
        return Base64NoWrap.encode(encrypted)
    }
}
