package com.heikeji.phonesearch.ui.notice

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heikeji.phonesearch.R

/**
 * 首次使用说明（交接文档 §10）。
 *
 * 原 APK 用 `SharedPreferences: usage-notice` / `key: accepted-version` / `required value: 1`
 * 记录接受状态。这里沿用同样的键名与值。
 *
 * 文案是本项目自己的内容，不照搬原 APK 的措辞。
 */
object UsageNotice {

    private const val PREFS = "usage-notice"
    private const val KEY_ACCEPTED_VERSION = "accepted-version"
    private const val REQUIRED_VERSION = 1

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED_VERSION, 0) >= REQUIRED_VERSION

    private fun markAccepted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION, REQUIRED_VERSION)
            .apply()
    }

    /**
     * 未接受过就先弹说明；拒绝则调用 [onDeclined]（由调用方结束页面）。
     */
    fun ensureAccepted(
        activity: AppCompatActivity,
        onAccepted: () -> Unit,
        onDeclined: () -> Unit,
    ) {
        if (isAccepted(activity)) {
            onAccepted()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.notice_title)
            .setMessage(R.string.notice_body)
            .setCancelable(false)
            .setPositiveButton(R.string.notice_accept) { _, _ ->
                markAccepted(activity)
                onAccepted()
            }
            .setNegativeButton(R.string.notice_decline) { _, _ -> onDeclined() }
            .show()
    }
}
