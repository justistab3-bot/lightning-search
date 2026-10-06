package com.heikeji.phonesearch.search

import com.heikeji.phonesearch.protocol.search.model.SearchChallenge

/**
 * 内存中的验证挑战（同一时刻最多一个）。
 *
 * 1.1.1 起挑战要携带**完整 [SearchTask]**：验证成功后必须按原模式恢复，
 * 整页与框选请求不能被降级成普通单题。
 *
 * 绝不落盘、绝不进日志。
 */
class SearchChallengeStore {

    /** 挑战 + 触发它的任务。 */
    data class Pending(val challenge: SearchChallenge, val task: SearchTask)

    @Volatile
    private var pending: Pending? = null

    fun put(challenge: SearchChallenge, task: SearchTask) {
        pending = Pending(challenge, task)
    }

    fun peek(): Pending? = pending

    fun clear() {
        pending = null
    }
}
