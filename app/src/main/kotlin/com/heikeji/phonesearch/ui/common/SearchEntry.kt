package com.heikeji.phonesearch.ui.common

import android.content.Context
import android.content.Intent
import com.heikeji.phonesearch.protocol.model.SearchMode
import com.heikeji.phonesearch.ui.crop.CropActivity
import com.heikeji.phonesearch.ui.page.PageResultActivity

/**
 * 拍照之后去哪儿。
 *
 * 应用内相机与系统相机两条入口共用这一份路由，避免两处判断走偏：
 * - 单题：先裁剪/旋转，再搜题
 * - 整页：跳过裁剪，保留整页分辨率给服务端定位题框
 */
object SearchEntry {

    fun intentFor(
        context: Context,
        capturePath: String,
        mode: SearchMode,
        extraRotation: Int = 0,
    ): Intent = if (mode == SearchMode.PAGE) {
        PageResultActivity.newIntent(context, capturePath, extraRotation)
    } else {
        CropActivity.newIntent(context, capturePath, extraRotation)
    }
}
