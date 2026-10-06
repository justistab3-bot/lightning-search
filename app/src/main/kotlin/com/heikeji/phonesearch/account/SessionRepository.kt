package com.heikeji.phonesearch.account

import com.heikeji.phonesearch.protocol.model.AccountSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前登录会话。
 *
 * 与原实现的一处改进：`identityIdV2` / `occupationType` 随会话一起持久化，
 * 恢复会话后立刻用 `userinfov3` 回填。原实现把它们放在 API 客户端的实例状态里，
 * 重启后会退化成 "0" 直到再次登录。
 */
class SessionRepository(private val store: SecureSessionStore) {

    private val _session = MutableStateFlow<AccountSession?>(null)
    val session: StateFlow<AccountSession?> = _session.asStateFlow()

    /** 从安全存储恢复（应在 Application 启动时调用一次）。 */
    fun restore() {
        _session.value = store.load()
    }

    fun current(): AccountSession? = _session.value

    fun kduss(): String = _session.value?.kduss.orEmpty()

    fun update(session: AccountSession) {
        _session.value = session
        store.save(session)
    }

    /** 仅更新内存（例如回填 identityIdV2 时顺带持久化）。 */
    fun updateInMemory(session: AccountSession) {
        _session.value = session
        runCatching { store.save(session) }
    }

    fun clear() {
        _session.value = null
        store.clear()
    }

    /**
     * compare-and-clear：只有当前存储的 KDUSS 仍等于**发起该请求时**用的那个，才清除会话。
     *
     * 否则一个失败请求的 HTTP 401 或业务错误码 3 会误删用户刚刚登录的新会话。
     * 交接文档 §9 / §12.3。
     */
    fun clearIfCurrent(kduss: String) {
        if (kduss.isEmpty()) return
        if (_session.value?.kduss != kduss) return
        clear()
    }
}
