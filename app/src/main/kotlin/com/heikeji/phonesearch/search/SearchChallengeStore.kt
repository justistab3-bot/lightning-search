package com.heikeji.phonesearch.search

import com.heikeji.phonesearch.protocol.model.SearchChallenge

/**
 * 内存中的验证挑战（同一时刻最多一个）。
 *
 * 绝不落盘、绝不进日志；验证成功后必须确认 localToken / 图片字节 / KDUSS 仍然一致。
 */
class SearchChallengeStore {

    @Volatile
    private var challenge: SearchChallenge? = null

    fun put(value: SearchChallenge) {
        challenge = value
    }

    fun peek(): SearchChallenge? = challenge

    fun clear() {
        challenge = null
    }
}
