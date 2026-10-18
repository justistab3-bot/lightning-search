package com.heikeji.phonesearch.account

import com.heikeji.phonesearch.protocol.model.AccountSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionRepositoryTest {
    private val oldAccount = AccountSession("fixture-old-session", "旧账号", "11", 6, 1, 0)
    private val newAccount = AccountSession("fixture-new-session", "新账号", "22", 9, 1, 0)

    private class MemoryStorage(var saved: AccountSession?) : SessionStorage {
        var saveCount = 0
        var clearCount = 0
        var failSave = false
        override fun load() = saved
        override fun save(session: AccountSession) {
            if (failSave) throw IllegalStateException("storage unavailable")
            saved = session
            saveCount++
        }
        override fun clear() { saved = null; clearCount++ }
    }

    @Test fun `restoring an older account does not rewrite or clear it`() {
        val store = MemoryStorage(oldAccount)
        val sessions = SessionRepository(store)
        sessions.restore()
        assertEquals(oldAccount, sessions.current())
        assertEquals(0, store.saveCount)
        assertEquals(0, store.clearCount)
    }

    @Test fun `failed login persistence preserves the existing account`() {
        val store = MemoryStorage(oldAccount)
        val sessions = SessionRepository(store).apply { restore() }
        store.failSave = true
        assertThrows(IllegalStateException::class.java) { sessions.update(newAccount) }
        assertEquals(oldAccount, sessions.current())
        assertEquals(oldAccount, store.saved)
    }

    @Test fun `expired old requests cannot clear a newly logged in account`() {
        val store = MemoryStorage(oldAccount)
        val sessions = SessionRepository(store).apply { restore(); update(newAccount) }
        sessions.clearIfCurrent(oldAccount.kduss)
        assertEquals(newAccount, sessions.current())
        assertEquals(newAccount, store.saved)
        assertEquals(0, store.clearCount)
    }

    @Test fun `late user profile responses cannot switch back to the old account`() {
        val store = MemoryStorage(oldAccount)
        val sessions = SessionRepository(store).apply { restore(); update(newAccount) }
        sessions.updateInMemory(oldAccount.copy(userName = "过期资料"))
        assertEquals(newAccount, sessions.current())
        assertEquals(newAccount, store.saved)
    }

    @Test fun `explicit expiry still clears the matching current account`() {
        val store = MemoryStorage(oldAccount)
        val sessions = SessionRepository(store).apply { restore() }
        sessions.clearIfCurrent(oldAccount.kduss)
        assertNull(sessions.current())
        assertNull(store.saved)
    }
}
