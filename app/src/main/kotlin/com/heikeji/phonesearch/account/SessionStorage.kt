package com.heikeji.phonesearch.account

import com.heikeji.phonesearch.protocol.model.AccountSession

/** Persistent account storage; implementations retain the existing on-disk schema. */
interface SessionStorage {
    fun load(): AccountSession?
    fun save(session: AccountSession)
    fun clear()
}
