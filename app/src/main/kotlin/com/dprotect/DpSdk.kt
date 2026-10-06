package com.dprotect

import android.content.Context

/**
 * 官方手机客户端 `libdpsdk.so` 的 JNI 胶水（Dp-Ticket 设备保护票据）。
 *
 * 类名/包名/方法签名与官方一致，标准 JNI 导出：
 * ```
 * public class DpSdk {
 *     static { System.loadLibrary("dpsdk"); }
 *     public static native String getTicket();
 *     public static native void init(Context context);
 * }
 * ```
 */
class DpSdk private constructor() {

    companion object {
        init {
            try {
                System.loadLibrary("dpsdk")
            } catch (t: Throwable) {
                // 加载失败：上层会跳过 Dp-Ticket 头。
            }
        }

        @JvmStatic
        external fun getTicket(): String

        @JvmStatic
        external fun init(context: Context)
    }
}
