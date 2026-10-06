package com.zuoyebang.baseutil

import android.content.Context

/**
 * 官方手机客户端 `libbaseutil.so` 的 JNI 胶水。
 *
 * 类名/包名/方法签名必须与官方完全一致：原生库在 `JNI_OnLoad` 里按
 * `com/zuoyebang/baseutil/NativeHelper` 动态注册。
 *
 * 官方声明（classes6.dex，原样保留签名）：
 * ```
 * static native String nativeGetKey(String str);
 * static native String nativeGetRandom();
 * static native String nativeGetSign(String str);
 * static native String nativeInitBaseUtil(Context context, String str);
 * static native boolean nativeSetToken(Context context, String str, String str2, String str3);
 * ```
 *
 * 注意所有方法在官方都是 **static**，这里用 @JvmStatic 原样复刻。
 */
class NativeHelper private constructor() {

    companion object {
        init {
            try {
                System.loadLibrary("baseutil")
            } catch (t: Throwable) {
                // 设备缺 ABI 对应库或加载失败：上层自动回退旧的 Java 密钥链。
            }
        }

        @JvmStatic
        external fun nativeGetKey(str: String): String

        @JvmStatic
        external fun nativeGetRandom(): String

        @JvmStatic
        external fun nativeGetSign(str: String): String

        @JvmStatic
        external fun nativeInitBaseUtil(context: Context, str: String): String

        @JvmStatic
        external fun nativeSetToken(
            context: Context,
            str: String,
            str2: String,
            str3: String,
        ): Boolean
    }
}
