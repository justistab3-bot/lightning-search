package com.heikeji.phonesearch.layout

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 布局静态检查。
 *
 * 下面每条规则各自对应一个**只在真机上才炸**的 bug，普通单测抓不到，
 * 但代价极高，所以在这里钉死。
 *
 * 1. `ScrollView` 只能有一个直接子 View —— 多一个就在 inflate 时抛
 *    `IllegalStateException: ScrollView can host only one direct child`。
 *    实际发生过：`layout-land/activity_essay.xml` 的答案区塞了两个 TextView，
 *    结果**一横屏打开 AI 作文就闪退**。
 *
 * 2. 同名布局的各个变体，`android:id` 集合必须与竖屏版一致。
 *    不一致时 ViewBinding 会把只在一边存在的字段生成为**可空**，
 *    代码里直接点用就变成「某个方向才 NPE」。实际发生过：首页加了 `chatButton`
 *    只改了竖屏，横屏下这个字段就是 `MaterialButton?`。
 *
 * 3. 同一个 id 在所有变体里必须是**同一种 View**。ViewBinding 只按其中一份
 *    生成字段类型，另一份不同就是运行时 `ClassCastException`。
 *    实际发生过：`root` 竖屏是 `NestedScrollView`、横屏是 `LinearLayout`，
 *    结果「竖屏转横屏闪退」。
 *
 * **覆盖范围自动包含所有 `layout-*` 目录**，不只是 `layout-land`。
 * 词典笔那类超窄屏用的是 `layout-h280dp-land`，如果这里漏掉它，
 * 上面三个坑会在那个方向上原样重演。
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

    /**
     * 除 `layout/` 之外的所有布局变体目录（`layout-land`、`layout-h280dp-land`…）。
     *
     * 动态枚举而不是写死清单：新增一个尺寸变体时检查会自动覆盖，
     * 不需要记得回来改测试。
     */
    private fun variantDirs(): List<String> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("layout-") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** 所有布局目录，含竖屏基准目录。 */
    private fun allLayoutDirs(): List<String> = listOf("layout") + variantDirs()

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    /**
     * 递归收集所有元素，**包含根元素**。
     *
     * 不能用 `getElementsByTagName("*")` —— 它只返回后代，不含自身，
     * 而首页的根恰好就是 `ScrollView` 一类容器，漏掉根就漏掉了最该检查的那个。
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

    private fun idsOf(file: File): Set<String> = idToTag(file).keys

    /** id -> 视图标签名。重复 id 只保留最后一个（另有专门用例检查重复）。 */
    private fun idToTag(file: File): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        for (element in allElements(parse(file))) {
            val id = element.getAttribute("android:id")
            if (id.startsWith("@+id/")) {
                found[id.removePrefix("@+id/")] = element.tagName
            }
        }
        return found
    }

    // ------------------------------------------------------------------ 规则 1

    @Test
    fun `no scroll view has more than one direct child`() {
        val problems = ArrayList<String>()
        for (sub in allLayoutDirs()) {
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
    fun `every layout variant matches its portrait ids`() {
        val variants = variantDirs()
        assertTrue("没有找到任何布局变体目录，路径可能不对：${resDir.absolutePath}", variants.isNotEmpty())

        val problems = ArrayList<String>()
        for (sub in variants) {
            for (variant in layouts(sub)) {
                val portrait = File(resDir, "layout/${variant.name}")
                if (!portrait.isFile) continue

                val portraitIds = idsOf(portrait)
                val variantIds = idsOf(variant)
                val onlyPortrait = portraitIds - variantIds
                val onlyVariant = variantIds - portraitIds
                if (onlyPortrait.isNotEmpty() || onlyVariant.isNotEmpty()) {
                    problems.add(
                        "$sub/${variant.name}: 竖屏独有=[${onlyPortrait.joinToString(",")}] " +
                            "该变体独有=[${onlyVariant.joinToString(",")}]",
                    )
                }
            }
        }
        assertTrue(
            "各方向/尺寸变体的 id 必须与竖屏一致，否则 ViewBinding 会把字段变成可空、某个方向下 NPE：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 3

    @Test
    fun `same id keeps the same view type across all variants`() {
        val problems = ArrayList<String>()
        for (sub in variantDirs()) {
            for (variant in layouts(sub)) {
                val portrait = File(resDir, "layout/${variant.name}")
                if (!portrait.isFile) continue
                val portraitTags = idToTag(portrait)
                val variantTags = idToTag(variant)
                for ((id, variantTag) in variantTags) {
                    val portraitTag = portraitTags[id] ?: continue
                    if (variantTag != portraitTag) {
                        problems.add(
                            "$sub/${variant.name}: id=$id 竖屏是 <$portraitTag>，该变体是 <$variantTag>",
                        )
                    }
                }
            }
        }
        assertTrue(
            "同一个 id 在所有变体里必须是同一种 View，否则 ViewBinding 强转崩溃：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 4

    /** 一份布局里 id 不能重复：ViewBinding 会抛，且 findViewById 行为不确定。 */
    @Test
    fun `no layout declares the same id twice`() {
        val problems = ArrayList<String>()
        for (sub in allLayoutDirs()) {
            for (file in layouts(sub)) {
                val seen = HashMap<String, Int>()
                for (element in allElements(parse(file))) {
                    val id = element.getAttribute("android:id")
                    if (!id.startsWith("@+id/")) continue
                    val name = id.removePrefix("@+id/")
                    seen[name] = (seen[name] ?: 0) + 1
                }
                seen.filterValues { it > 1 }.forEach { (name, count) ->
                    problems.add("$sub/${file.name}: id=$name 出现 $count 次")
                }
            }
        }
        assertTrue(
            "同一份布局里 id 不能重复：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 元检查

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

    @Test
    fun `all landscape variants are actually covered`() {
        // 这条不是防布局出错，是防「测试悄悄漏掉目录」——
        // 一旦某个变体没被扫到，上面三条规则对它就是形同虚设。
        val variants = variantDirs()

        // layout-land 是横屏默认（矮屏也走它），layout-h340dp-land 是给高屏的增强版。
        // 少了任何一个，对应那类设备就会掉回竖屏布局。
        assertTrue(
            "应当同时存在 layout-land 与 layout-h340dp-land，实际：${variants.joinToString(",")}",
            variants.containsAll(listOf("layout-land", "layout-h340dp-land")),
        )

        val land = layouts("layout-land").map { it.name }.toSet()
        assertTrue(
            "layout-land 应当覆盖首页与整页结果页，实际：${land.joinToString(",")}",
            land.containsAll(setOf("activity_home.xml", "activity_page_result.xml")),
        )

        // 整页结果页原先**没有**横屏版，是这次补的；掉了就会退回竖屏那份
        // （56dp 标题栏 + 180dp 原图 > 254dp 屏高，答案区直接消失）。
        val tall = layouts("layout-h340dp-land").map { it.name }.toSet()
        assertTrue(
            "layout-h340dp-land 应当有首页宽敞版，实际：${tall.joinToString(",")}",
            "activity_home.xml" in tall,
        )
    }

    private companion object {
        /** 这些容器都只允许一个直接子 View（比较时只看标签名，去掉包名前缀）。 */
        val SCROLL_TAGS = setOf("ScrollView", "HorizontalScrollView", "NestedScrollView")
    }
}
