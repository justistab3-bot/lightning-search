package com.heikeji.phonesearch.ui.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePromptPolicyTest {
    @Test
    fun `back to original page does not reopen the offline prompt`() {
        val policy = OfflinePromptPolicy()
        assertTrue(policy.tryShow(online = false))

        // 提示页关闭后原页面恢复，随后切页、旋转或从设置返回。
        repeat(5) {
            assertFalse(policy.tryShow(online = false))
        }
    }

    @Test
    fun `network and lifecycle callbacks cannot launch two prompts`() {
        val policy = OfflinePromptPolicy()
        assertTrue(policy.tryShow(online = false))
        policy.onNetworkChanged(online = false)
        assertFalse(policy.tryShow(online = false))
    }

    @Test
    fun `reconnection allows one prompt for the next outage`() {
        val policy = OfflinePromptPolicy()
        assertTrue(policy.tryShow(online = false))
        policy.onNetworkChanged(online = true)
        assertTrue(policy.tryShow(online = false))
        assertFalse(policy.tryShow(online = false))
    }

    @Test
    fun `online resume rearms the prompt even without a network callback`() {
        val policy = OfflinePromptPolicy()
        assertFalse(policy.tryShow(online = true))
        assertTrue(policy.tryShow(online = false))
        assertFalse(policy.tryShow(online = true))
        assertTrue(policy.tryShow(online = false))
    }

    @Test
    fun `failed launch leaves the next offline prompt available`() {
        val policy = OfflinePromptPolicy()
        assertTrue(policy.tryShow(online = false))
        policy.onLaunchFailed()
        assertTrue(policy.tryShow(online = false))
        assertFalse(policy.tryShow(online = false))
    }
}
