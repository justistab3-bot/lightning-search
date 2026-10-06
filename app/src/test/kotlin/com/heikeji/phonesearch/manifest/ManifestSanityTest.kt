package com.heikeji.phonesearch.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 清单静态检查。
 *
 * 这里每条规则都对应一个**编译能过、装上去才发现**的坑。
 *
 * 第一条是真实事故：登录改成可选之后，引导页和匿名模式都做好了，但清单里
 * `LoginActivity` 仍然挂着 LAUNCHER —— 新设备点图标直接进登录页，
 * `HomeActivity` 根本没被启动过，整套匿名流程**一行都没跑到**。
 * 编译、lint、单元测试全绿，只有装到新设备上才看得出来。
 */
class ManifestSanityTest {

    private val manifest: Element by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 AndroidManifest.xml，工作目录=${File("").absolutePath}")
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun activities(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = manifest.getElementsByTagName("activity")
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { out.add(it) }
        return out
    }

    private fun Element.hasLauncherFilter(): Boolean {
        val filters = getElementsByTagName("intent-filter")
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as? Element ?: continue
            val actions = filter.getElementsByTagName("action")
            val categories = filter.getElementsByTagName("category")
            var hasMain = false
            var hasLauncher = false
            for (j in 0 until actions.length) {
                if ((actions.item(j) as? Element)?.getAttribute("android:name") ==
                    "android.intent.action.MAIN"
                ) {
                    hasMain = true
                }
            }
            for (j in 0 until categories.length) {
                if ((categories.item(j) as? Element)?.getAttribute("android:name") ==
                    "android.intent.category.LAUNCHER"
                ) {
                    hasLauncher = true
                }
            }
            if (hasMain && hasLauncher) return true
        }
        return false
    }

    @Test
    fun `launcher is the home screen, not the login screen`() {
        val launchers = activities().filter { it.hasLauncherFilter() }
            .map { it.getAttribute("android:name") }

        assertEquals(
            "有且只能有一个 LAUNCHER 入口，实际：$launchers",
            1,
            launchers.size,
        )
        assertEquals(
            "启动入口必须是首页。若这里变回 LoginActivity，新装设备会直接进登录页，" +
                "首次引导和匿名模式都走不到（真实发生过）。",
            ".ui.home.HomeActivity",
            launchers.first(),
        )
    }

    @Test
    fun `login screen is not exported`() {
        val login = activities().firstOrNull {
            it.getAttribute("android:name").endsWith("LoginActivity")
        } ?: error("清单里找不到 LoginActivity")

        assertFalse(
            "登录页不该对外暴露：它已经不是启动入口，导出只会多一个外部可拉起的界面。",
            login.getAttribute("android:exported") == "true",
        )
    }

    @Test
    fun `onboarding is registered and internal`() {
        val onboard = activities().firstOrNull {
            it.getAttribute("android:name").endsWith("OnboardingActivity")
        } ?: error("清单里没有注册 OnboardingActivity —— 首次引导会直接崩")

        assertFalse(
            "引导页只应由应用内部拉起，不需要导出。",
            onboard.getAttribute("android:exported") == "true",
        )
    }

    @Test
    fun `manifest parsing actually finds activities`() {
        // 防止上面几条因为解析不到而「永远通过」
        val names = activities().map { it.getAttribute("android:name") }
        assertTrue("应当解析出多个 Activity，实际：$names", names.size >= 8)
        assertTrue("应当包含 HomeActivity，实际：$names", names.any { it.endsWith("HomeActivity") })
    }
}
