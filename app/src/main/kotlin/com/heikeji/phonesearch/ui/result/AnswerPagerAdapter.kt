package com.heikeji.phonesearch.ui.result

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 答案分页：一页一条答案，左右滑动切换。
 *
 * 传入的是已经渲染好的 HTML（String），避免把模型对象塞进 Fragment arguments。
 */
class AnswerPagerAdapter(
    activity: FragmentActivity,
    private val pages: List<String>,
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment =
        AnswerPageFragment.newInstance(pages[position])
}
