package com.heikeji.phonesearch.ui.result

/**
 * 答案页里点击图片时回调给宿主 Activity，由宿主打开全屏查看器。
 *
 * 实现方式是给 `<img>` 包一层 `<a href="图片地址">`，点击锚点走
 * `WebViewClient.shouldOverrideUrlLoading`——这样答案页仍然可以禁用 JavaScript。
 */
interface AnswerImageHost {
    fun openAnswerImage(url: String)
}
