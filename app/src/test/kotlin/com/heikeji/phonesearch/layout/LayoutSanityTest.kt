package com.heikeji.phonesearch.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 布局静态检查。
 *
 * 这两条规则各自对应一个**只在真机上才炸**的 bug，普通单测抓不到，
 * 但代价极高（一个是横屏闪退，一个是特定方向 NPE），所以在这里钉死。
 *
 * 1. `ScrollView` 只能有一个直接子 View —— 多一个就在 inflate 时抛
 *    `IllegalStateException: ScrollView can host only one direct child`。
 *    实际发生过：`layout-land/activity_essay.xml` 的答案区塞了两个 TextView，
 *    结果**一横屏打开 AI 作文就闪退**。
 *
 * 2. 同名布局的竖屏版与横屏版，`android:id` 集合必须一致。
 *    不一致时 ViewBinding 会把只在一边存在的字段生成为**可空**，
 *    代码里直接点用就变成「某个方向才 NPE」。实际发生过：首页加了 `chatButton`
 *    只改了竖屏，横屏下这个字段就是 `MaterialButton?`。
 */
class LayoutSanityTest {

    private val resDir = findResDir()

    private fun findResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 res 目录，工作目录=${File("").absolutePath}")
    }

    private fun layouts(sub: String): List<File> =
        File(resDir, sub).listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    /**
     * 递归收集所有元素，**包含根元素**。
     *
     * 不能用 `getElementsByTagName("*")` —— 它只返回后代，不含自身，
     * 而首页的根恰好就是 `NestedScrollView`，漏掉根就漏掉了最该检查的那个。
     */
    private fun allElements(root: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            out.add(element)
            val children = element.childNodes
            for (i in 0 until children.length) {
                (children.item(i) as? Element)?.let { walk(it) }
            }
        }
        walk(root)
        return out
    }

    private fun idsOf(file: File): Set<String> {
        val found = sortedSetOf<String>()
        for (element in allElements(parse(file))) {
            val id = element.getAttribute("android:id")
            if (id.startsWith("@+id/")) found.add(id.removePrefix("@+id/"))
        }
        return found
    }

    // ------------------------------------------------------------------ 规则 1

    @Test
    fun `no scroll view has more than one direct child`() {
        val problems = ArrayList<String>()
        for (sub in listOf("layout", "layout-land")) {
            for (file in layouts(sub)) {
                for (element in allElements(parse(file))) {
                    // 只看标签名，不管包名前缀
                    val tag = element.tagName.substringAfterLast('.')
                    if (tag !in SCROLL_TAGS) continue
                    val kids = (0 until element.childNodes.length)
                        .map { element.childNodes.item(it) }
                        .filterIsInstance<Element>()
                    if (kids.size > 1) {
                        problems.add(
                            "$sub/${file.name}: <${element.tagName}> 有 ${kids.size} 个直接子 View " +
                                "(${kids.joinToString(", ") { it.tagName }})",
                        )
                    }
                }
            }
        }
        assertTrue(
            "ScrollView / NestedScrollView 只能有一个直接子 View，否则 inflate 时崩溃：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 2

    @Test
    fun `every landscape layout matches its portrait ids`() {
        val landscape = layouts("layout-land")
        assertTrue("没有找到任何横屏布局，路径可能不对：${resDir.absolutePath}", landscape.isNotEmpty())

        val problems = ArrayList<String>()
        for (land in landscape) {
            val portrait = File(resDir, "layout/${land.name}")
            if (!portrait.isFile) continue

            val portraitIds = idsOf(portrait)
            val landscapeIds = idsOf(land)
            val onlyPortrait = portraitIds - landscapeIds
            val onlyLandscape = landscapeIds - portraitIds
            if (onlyPortrait.isNotEmpty() || onlyLandscape.isNotEmpty()) {
                problems.add(
                    "${land.name}: 竖屏独有=[${onlyPortrait.joinToString(",")}] " +
                        "横屏独有=[${onlyLandscape.joinToString(",")}]",
                )
            }
        }
        assertTrue(
            "竖屏与横屏的 id 必须一致，否则 ViewBinding 会把字段变成可空、某个方向下 NPE：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `id extraction actually finds ids`() {
        // 防止上面的规则因为解析不到 id 而「永远通过」
        val home = File(resDir, "layout/activity_home.xml")
        assertTrue("activity_home.xml 不存在：${home.absolutePath}", home.isFile)
        val ids = idsOf(home)
        assertTrue(
            "应当解析出若干 id，实际 ${ids.size} 个：${ids.joinToString(",")}",
            ids.size >= 10,
        )
        assertTrue(
            "应当包含 root，实际：${ids.joinToString(",")}",
            "root" in ids,
        )
    }

    private companion object {
        /** 这些容器都只允许一个直接子 View（比较时只看标签名，去掉包名前缀）。 */
        val SCROLL_TAGS = setOf("ScrollView", "HorizontalScrollView", "NestedScrollView")
    }
}
