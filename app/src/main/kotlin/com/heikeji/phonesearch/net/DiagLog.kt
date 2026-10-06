package com.heikeji.phonesearch.net

import android.content.Context

/**
 * 诊断文件日志：原生调用、崩溃、搜题链路统一落盘。
 *
 * 文件在 `Android/data/com.heikeji.phonesearch/files/protocol-log.txt`，
 * 可从「设置 → 协议诊断」直接查看尾部。
 */
object DiagLog {

    @Volatile
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun append(line: String) {
        try {
            val ctx = context ?: return
            val file = java.io.File(ctx.getExternalFilesDir(null), "protocol-log.txt")
            file.appendText("${System.currentTimeMillis()} ${Thread.currentThread().name} $line\n")
        } catch (e: Exception) {
            // 日志失败不影响主流程
        }
    }
}
