package com.heikeji.phonesearch.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新源解析与挑选。
 *
 * 关键场景：**用户从很旧的版本直接跳到最新**。
 * 之前 `/releases/latest` 拿不到 apk 附件时直接返回 null，用户会永远卡在「已是最新」——
 * 明明新版本就在仓库里。这里钉住兜底行为。
 */
class UpdateClientTest {

    private fun release(
        tag: String,
        apk: String? = "lightning-search-$tag.apk",
    ): String {
        val assets = if (apk == null) {
            """[{"name":"$tag.zip","browser_download_url":"https://gitee.com/x/$tag.zip"}]"""
        } else {
            """[{"name":"$apk","browser_download_url":"https://gitee.com/dl/$apk"}]"""
        }
        return """{"tag_name":"$tag","name":"$tag","body":"说明","assets":$assets}"""
    }

    // ------------------------------------------------------------------ 单个 release

    @Test
    fun `parses tag changelog and apk url`() {
        val info = UpdateClient.parseRelease(release("v1.20.0"))!!
        assertEquals("1.20.0", info.versionName)
        assertEquals("v1.20.0", info.tagName)
        assertEquals("说明", info.changelog)
        assertTrue(info.hasApk)
        assertTrue(info.apkUrl.endsWith("lightning-search-v1.20.0.apk"))
    }

    @Test
    fun `release without apk is parsed but marked unusable`() {
        val info = UpdateClient.parseRelease(release("v1.21.0", apk = null))!!
        assertEquals("1.21.0", info.versionName)
        assertFalse(info.hasApk)
    }

    @Test
    fun `release without tag is rejected`() {
        assertNull(UpdateClient.parseRelease("""{"name":"随便","assets":[]}"""))
        assertNull(UpdateClient.parseRelease("不是 JSON"))
    }

    @Test
    fun `non https apk url is ignored`() {
        val body = """
            {"tag_name":"v1.20.0","assets":[{"name":"a.apk","browser_download_url":"http://insecure/a.apk"}]}
        """.trimIndent()
        assertFalse(UpdateClient.parseRelease(body)!!.hasApk)
    }

    // ------------------------------------------------------------------ 挑选

    @Test
    fun `picks the highest version with an apk`() {
        val best = UpdateClient.pickBest(
            listOf(
                UpdateInfo("1.12.0", "v1.12.0", "", "", "https://a", "a.apk"),
                UpdateInfo("1.20.0", "v1.20.0", "", "", "https://b", "b.apk"),
                UpdateInfo("1.9.0", "v1.9.0", "", "", "https://c", "c.apk"),
            ),
        )!!
        // 1.20.0 必须赢过 1.9.0（按字符串比会反过来）
        assertEquals("1.20.0", best.versionName)
    }

    @Test
    fun `skips entries without an apk`() {
        val best = UpdateClient.pickBest(
            listOf(
                UpdateInfo("1.21.0", "v1.21.0", "", "", "", ""),
                UpdateInfo("1.20.0", "v1.20.0", "", "", "https://b", "b.apk"),
            ),
        )!!
        assertEquals("1.20.0", best.versionName)
    }

    @Test
    fun `returns null when nothing has an apk`() {
        assertNull(
            UpdateClient.pickBest(
                listOf(UpdateInfo("1.20.0", "v1.20.0", "", "", "", "")),
            ),
        )
        assertNull(UpdateClient.pickBest(emptyList()))
    }

    @Test
    fun `list parsing tolerates broken entries`() {
        val body = "[${release("v1.15.0")}, 不是对象, ${release("v1.20.0")}, ${release("v1.21.0", apk = null)}]"
        assertEquals("1.20.0", UpdateClient.parseReleaseList(body)!!.versionName)
    }

    @Test
    fun `list parsing of garbage returns null`() {
        assertNull(UpdateClient.parseReleaseList("不是 JSON"))
    }

    // ------------------------------------------------------------------ 跨版本跳升

    @Test
    fun `an old install sees the newest release in one step`() {
        // 用户停在任何旧版本，都应一步看到最新
        for (installed in listOf("1.12.0", "1.13.0", "1.15.0", "1.19.0")) {
            assertTrue(
                "从 $installed 应当能直接看到 1.20.0",
                Version.isNewer("1.20.0", installed),
            )
        }
    }

    @Test
    fun `rolled back versions never win the pick`() {
        // 1.16~1.18 已从 Gitee 删除；就算有人重新传上来，
        // 也不该盖过 1.20.0
        val best = UpdateClient.pickBest(
            listOf(
                UpdateInfo("1.18.0", "v1.18.0", "", "", "https://a", "a.apk"),
                UpdateInfo("1.20.0", "v1.20.0", "", "", "https://b", "b.apk"),
            ),
        )!!
        assertEquals("1.20.0", best.versionName)
    }
}
