package com.heikeji.phonesearch.net

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import com.heikeji.phonesearch.protocol.ProtocolProfile
import java.util.Locale
import java.util.UUID

/**
 * 设备身份与公共参数（原 P0.a）。
 *
 * CUID 首次运行生成 `uppercase(UUID 去连字符) + "|0"` 并用 commit() 落盘，
 * 之后长期稳定，绝不每次启动重建——否则服务端签名材料会全部失配。
 */
class DeviceIdentity(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val cuid: String = synchronized(LOCK) {
        val existing = prefs.getString(KEY_CUID, null)
        if (!existing.isNullOrEmpty() && existing.endsWith(CUID_SUFFIX)) {
            existing
        } else {
            val created = UUID.randomUUID().toString()
                .replace("-", "")
                .uppercase(Locale.ROOT) + CUID_SUFFIX
            if (!prefs.edit().putString(KEY_CUID, created).commit()) {
                throw IllegalStateException("无法保存设备标识")
            }
            created
        }
    }

    /** 每个普通 API 都会带上的公共参数（保持插入顺序）。 */
    fun publicParams(): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        params["cuid"] = cuid
        params["channel"] = ProtocolProfile.CHANNEL
        params["token"] = ProtocolProfile.TOKEN
        params["vc"] = ProtocolProfile.VC
        params["vcname"] = ProtocolProfile.VC_NAME
        params["os"] = ProtocolProfile.OS
        params["sdk"] = Build.VERSION.SDK_INT.toString()
        params["operatorid"] = ProtocolProfile.OPERATOR_ID
        params["device"] = Build.MODEL
        params["pkgName"] = ProtocolProfile.PKG_NAME
        params["appId"] = ProtocolProfile.APP_ID
        params["province"] = ""
        params["city"] = ""
        params["area"] = ""
        params["osVersion"] = Build.VERSION.RELEASE
        params["brand"] = Build.BRAND
        params["abis"] = if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) "1" else "0"
        params["appBit"] = if (is64Bit()) "64" else "32"
        params["adid"] = ""
        params["phoneDevice"] = Build.DEVICE
        params["identityIdV2"] = "0"
        params["occupationType"] = "0"
        params["isPad"] = ProtocolProfile.IS_PAD
        params["digGrade"] = ProtocolProfile.DIG_GRADE
        return params
    }

    /**
     * 当前进程是不是 64 位。
     *
     * `Process.is64Bit()` 是 API 23 才有的，本应用最低支持 5.0（API 21），
     * 所以在老系统上退回到「设备支持哪些 ABI」来判断。
     */
    private fun is64Bit(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Process.is64Bit()
        } else {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        }

    /** Wi-Fi 为 "wifi"，其他为 "mobile"（原 P0.a.f）。 */
    fun networkType(): String = try {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            "mobile"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            if (capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ) {
                "wifi"
            } else {
                "mobile"
            }
        } else {
            // getActiveNetwork() 是 API 23 才有的；5.0/5.1 用老接口。
            // getActiveNetworkInfo 虽已废弃，但在 minSdk 21 上仍可用。
            @Suppress("DEPRECATION")
            val info = manager.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (info != null && info.type == ConnectivityManager.TYPE_WIFI) "wifi" else "mobile"
        }
    } catch (e: SecurityException) {
        "mobile"
    }

    private companion object {
        const val PREFS_NAME = "device_identity"
        const val KEY_CUID = "cuid"
        const val CUID_SUFFIX = "|0"
        val LOCK = Any()
    }
}
