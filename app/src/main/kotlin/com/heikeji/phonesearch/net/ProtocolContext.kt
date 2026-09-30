package com.heikeji.phonesearch.net

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.crypto.Digests
import com.heikeji.phonesearch.protocol.crypto.ResponseKey
import com.heikeji.phonesearch.protocol.sign.SignA

/**
 * 协议上下文（原 P0.f）：设备签名材料的缓存、校验、responseKey 派生与校时。
 *
 * 这是所有普通 API 的前置条件，**必须在后台线程初始化**（涉及网络与磁盘 IO）。
 */
class ProtocolContext(
    context: Context,
    private val identity: DeviceIdentity,
    private val transport: HttpTransport,
) {

    private val lock = Any()
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var deviceSecret: String? = null
    private var deviceSecretDigestValue: String = ""
    private var responseKeyValue: String = ""

    /** kakorrhaphiophobia：ProtocolContext 就绪那一刻的 elapsedRealtime。 */
    private var uptimeAtReady: Long = 0L

    private var serverDateMillis: Long = 0L
    private var serverDateUptime: Long = 0L

    /** 幂等；需要网络与磁盘，禁止在主线程调用。 */
    fun ensureInitialized() {
        synchronized(lock) {
            if (deviceSecret != null) return

            val cachedCuid = prefs.getString(KEY_CUID, null)
            val cachedDigest = prefs.getString(KEY_CERTIFICATE_DIGEST, null)
            val cachedSignA = prefs.getString(KEY_SIGN_A, null)
            val cachedSignB = prefs.getString(KEY_SIGN_B, null)
            if (cachedCuid == identity.cuid &&
                cachedDigest == ProtocolProfile.CERTIFICATE_DIGEST &&
                !cachedSignA.isNullOrEmpty() && !cachedSignB.isNullOrEmpty()
            ) {
                try {
                    applyDeviceSecret(SignA.parseDeviceSecret(identity.cuid, cachedSignA, cachedSignB))
                    return
                } catch (e: Exception) {
                    // 缓存材料与当前身份不匹配：清掉后走完整初始化。
                    prefs.edit().clear().apply()
                }
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw ProtocolException("签名初始化需要在后台线程执行")
            }

            val random10 = SignA.random10()
            val signA = SignA.build(identity.cuid, random10)
            val signB = BootstrapClient(identity, transport).fetchSignB(signA, ::calibrate)
            val secret = SignA.parseDeviceSecret(identity.cuid, signA, signB)

            prefs.edit()
                .putString(KEY_CUID, identity.cuid)
                .putString(KEY_CERTIFICATE_DIGEST, ProtocolProfile.CERTIFICATE_DIGEST)
                .putString(KEY_SIGN_A, signA)
                .putString(KEY_SIGN_B, signB)
                .apply()

            applyDeviceSecret(secret)
        }
    }

    /** 128 字符小写 hex，作为 RC4 密钥。 */
    fun responseKey(): String {
        ensureInitialized()
        return responseKeyValue
    }

    /** md5Lower(deviceSecret)。 */
    fun deviceSecretDigest(): String {
        ensureInitialized()
        return deviceSecretDigestValue
    }

    /** 签名用的 kakorrhaphiophobia（毫秒）。 */
    fun uptimeMillis(): Long {
        ensureInitialized()
        return uptimeAtReady
    }

    /** 签名用的 _t_：优先用服务端 Date 校准，否则用本机时间。 */
    fun nowSeconds(): Long {
        val base = serverDateMillis
        val millis = if (base > 0) {
            base + (SystemClock.elapsedRealtime() - serverDateUptime)
        } else {
            System.currentTimeMillis()
        }
        return millis / 1000
    }

    /** 用服务端 Date 头校准。 */
    fun calibrate(dateMillis: Long) {
        if (dateMillis <= 0) return
        synchronized(lock) {
            serverDateMillis = dateMillis
            serverDateUptime = SystemClock.elapsedRealtime()
        }
    }

    /** 清空缓存的签名材料（退出登录时调用）。 */
    fun reset() {
        synchronized(lock) {
            prefs.edit().clear().apply()
            deviceSecret = null
            deviceSecretDigestValue = ""
            responseKeyValue = ""
            uptimeAtReady = 0L
            serverDateMillis = 0L
            serverDateUptime = 0L
        }
    }

    private fun applyDeviceSecret(secret: String) {
        deviceSecret = secret
        deviceSecretDigestValue = Digests.md5Lower(secret)
        responseKeyValue = ResponseKey.derive(secret)
        uptimeAtReady = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val PREFS_NAME = "portable_antispam"
        const val KEY_CUID = "cuid"
        const val KEY_CERTIFICATE_DIGEST = "certificateDigest"
        const val KEY_SIGN_A = "signA"
        const val KEY_SIGN_B = "signB"
    }
}
