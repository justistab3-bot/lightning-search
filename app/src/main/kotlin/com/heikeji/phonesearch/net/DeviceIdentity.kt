package com.heikeji.phonesearch.net

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.protocol.identity.DeviceInfo
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

    /** 学段（digGrade）。官方经 /kdapi/device/getdiggrade 回填；默认 6。 */
    @Volatile
    var digGrade: String = NetConfig.DIG_GRADE

    /** getdiggrade 返回的 digGrade 回填。 */
    fun updateDigGrade(value: String) {
        if (value.isNotEmpty()) digGrade = value
    }

    /** 服务器下发的设备 ID（did），持久化。 */
    @Volatile
    var did: String = synchronized(LOCK) {
        prefs.getString(KEY_DID, "") ?: ""
    }

    fun updateDid(value: String) {
        if (value.isEmpty()) return
        did = value
        prefs.edit().putString(KEY_DID, value).apply()
    }

    /** 每个普通 API 都会带上的公共参数（保持插入顺序）。 */
    fun publicParams(): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        params["cuid"] = cuid
        params["channel"] = NetConfig.CHANNEL
        params["token"] = NetConfig.TOKEN
        params["vc"] = NetConfig.VC
        params["vcname"] = NetConfig.VC_NAME
        params["os"] = NetConfig.OS
        params["sdk"] = Build.VERSION.SDK_INT.toString()
        params["operatorid"] = NetConfig.OPERATOR_ID
        params["device"] = Build.MODEL
        params["pkgName"] = NetConfig.PKG_NAME
        params["appId"] = NetConfig.APP_ID
        params["province"] = ""
        params["city"] = ""
        params["area"] = ""
        params["osVersion"] = Build.VERSION.RELEASE
        params["brand"] = Build.BRAND
        params["abis"] = if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) "1" else "0"
        params["appBit"] = if (is64Bit()) "64" else "32"
        params["adid"] = ""
        params["phoneDevice"] = Build.DEVICE
        params["identityIdV2"] = "1"
        params["occupationType"] = "0"
        params["isPad"] = NetConfig.IS_PAD
        params["digGrade"] = digGrade
        params["did"] = did
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

    /**
     * Getdid 上报负载：设备信息 JSON 经 RC4（官方 ENTRY_KEY）加密后的 Base64。
     *
     * 官方标准：事实采集在本类（Android），字段结构与加密在
     * `protocol.identity.DeviceInfo`（官方 PackageHelper/DeviceIdHelper 对应）。
     */
    fun didPayload(): String = DeviceInfo.buildPayload(
        DeviceInfo.Facts(
            appId = NetConfig.APP_ID,
            osVersion = Build.VERSION.RELEASE,
            language = Locale.getDefault().language,
            country = Locale.getDefault().country,
            brand = Build.BRAND,
            model = Build.MODEL,
            sn = serialOrEmpty(),
            androidId = runCatching {
                Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.ANDROID_ID,
                )
            }.getOrDefault(""),
            typewriting = runCatching {
                Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                )
            }.getOrDefault(""),
            powerOnTime = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            memoryGb = totalMemoryGb(),
            hardDiskBytes = totalDiskBytes(),
            screen = screenWh(),
        ),
    )

    /** 设备序列号：拿不到时返回 "unknown"（官方 DeviceIdHelper 同语义）。 */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun serialOrEmpty(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Build.getSerial()
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL
        }
    }.getOrDefault("")

    private fun totalMemoryGb(): Long = runCatching {        val info = android.app.ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(info)
        info.totalMem / (1024L * 1024L * 1024L)
    }.getOrDefault(0L)

    private fun totalDiskBytes(): Long = runCatching {
        android.os.StatFs(android.os.Environment.getDataDirectory().path).totalBytes
    }.getOrDefault(0L)

    private fun screenWh(): String = runCatching {
        val metrics = appContext.resources.displayMetrics
        "${metrics.widthPixels}*${metrics.heightPixels}"
    }.getOrDefault("0*0")

    private companion object {
        const val PREFS_NAME = "device_identity"
        const val KEY_CUID = "cuid"
        const val KEY_DID = "did"
        const val CUID_SUFFIX = "|0"
        val LOCK = Any()
    }
}
