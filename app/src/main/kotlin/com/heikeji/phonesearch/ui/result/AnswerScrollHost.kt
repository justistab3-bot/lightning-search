package com.heikeji.phonesearch.ui.result

/**
 * 答案页把竖向滚动量回报给宿主 Activity，用于「向上滚自动收起原图、向下滚自动展开」。
 */
interface AnswerScrollHost {
    /** @param deltaY 本次滚动增量：正数为向下浏览内容（手指上滑）。 */
    fun onAnswerScrolled(deltaY: Int)
}
