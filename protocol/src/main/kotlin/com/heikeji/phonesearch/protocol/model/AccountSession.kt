package com.heikeji.phonesearch.protocol.model

/**
 * 登录会话，对应原 P0.l 并补充 identityIdV2 / occupationType。
 *
 * 原实现把 identityIdV2 / occupationType 放在 ApiClient 实例状态里（重启后退化为 "0"），
 * 这里随会话一起持久化，并在恢复会话时重新拉取用户资料回填。
 */
data class AccountSession(
    val kduss: String,
    val userName: String,
    val uid: String,
    val grade: Int,
    val identityIdV2: Int = 0,
    val occupationType: Int = 0,
) {
    val isValid: Boolean get() = kduss.isNotEmpty()
}
