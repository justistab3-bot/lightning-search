package com.heikeji.phonesearch.account

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.heikeji.phonesearch.protocol.model.AccountSession
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 登录会话的安全存储（原 P0.m）。
 *
 * Android Keystore alias `watch_search_session_v1`，AES/GCM/NoPadding，256 位密钥；
 * iv 与密文以 Base64(NO_WRAP) 存进 `secure_account` SharedPreferences。
 *
 * **API 21/22 的降级**：`KeyGenParameterSpec` 是 API 23 才有的，Android 5.0/5.1
 * 上拿不到硬件密钥库。这时改用「设备标识派生密钥」的软件 AES —— 强度明显弱于
 * 硬件密钥库（能读到设备标识的人就能解密），但比明文落盘好：
 * 光把 SharedPreferences 文件拷走是解不开的。见 [softwareKey]。
 */
class SecureSessionStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AccountSession? {
        val iv = prefs.getString(KEY_IV, null)
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)
        if (iv.isNullOrEmpty() || ciphertext.isNullOrEmpty()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            val json = JSONObject(String(plain, Charsets.UTF_8))
            val kduss = json.optString("kduss", "")
            if (kduss.isEmpty()) {
                clear()
                null
            } else {
                AccountSession(
                    kduss = kduss,
                    userName = json.optString("name", ""),
                    uid = json.optString("uid", ""),
                    grade = json.optInt("grade", 0),
                    identityIdV2 = json.optInt("identityIdV2", 0),
                    occupationType = json.optInt("occupationType", 0),
                )
            }
        } catch (e: Exception) {
            // 密钥被系统清除或密文损坏：清掉不可用的会话，让用户重新登录。
            Log.w(TAG, "会话解密失败，已清除本地会话")
            clear()
            null
        }
    }

    fun save(session: AccountSession) {
        require(session.kduss.isNotEmpty()) { "登录会话为空" }
        val json = JSONObject()
            .put("kduss", session.kduss)
            .put("name", session.userName)
            .put("uid", session.uid)
            .put("grade", session.grade)
            .put("identityIdV2", session.identityIdV2)
            .put("occupationType", session.occupationType)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        val ok = prefs.edit()
            .putString(KEY_IV, iv)
            .putString(KEY_CIPHERTEXT, ciphertext)
            .commit()
        if (!ok) throw IllegalStateException("无法保存登录会话")
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun secretKey(): SecretKey {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return softwareKey()

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * API 21/22 的软件密钥。
     *
     * 用「ANDROID_ID + 包名 + 固定盐」派生，**不落盘** —— 这样即使有人把
     * SharedPreferences 文件拷到别的设备上也解不开。但在同一台设备上，
     * 其他应用（Android 8 以前 ANDROID_ID 对所有应用可见）理论上能推出来，
     * 所以强度不如 Keystore。这是老系统上能做的最好的折中。
     */
    private fun softwareKey(): SecretKey {
        @Suppress("DEPRECATION")
        val androidId = android.provider.Settings.Secure.getString(
            appContext.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ).orEmpty()
        val material = "$androidId|${appContext.packageName}|$ALIAS"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return javax.crypto.spec.SecretKeySpec(digest, "AES")
    }

    private companion object {
        const val TAG = "SecureSessionStore"
        const val PREFS_NAME = "secure_account"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val ALIAS = "watch_search_session_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
