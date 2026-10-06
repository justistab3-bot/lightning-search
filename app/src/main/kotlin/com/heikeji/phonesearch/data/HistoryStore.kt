package com.heikeji.phonesearch.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.heikeji.phonesearch.protocol.search.model.AnswerItem
import com.heikeji.phonesearch.protocol.search.model.SearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 一条搜题历史。 */
data class HistoryEntry(
    val id: String,
    val subject: String,
    val timestamp: Long,
    val questionFile: File,
    val resultFile: File,
)

/**
 * 本地搜题历史与统计。
 *
 * 存在 `filesDir/history/<id>/`：`question.jpg`（展示用题目图）+ `result.json`（答案快照），
 * 索引写在 `history/index.json`。最多保留 [MAX_ENTRIES] 条，超出后删掉最旧的目录。
 *
 * 只保存题目图与答案 HTML，不保存任何账号、签名或验证材料。
 */
class HistoryStore(context: Context) {

    private val root = File(context.filesDir, "history")
    private val indexFile = File(root, "index.json")
    private val stats = context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)

    fun record(jpeg: ByteArray, result: SearchResult) {
        if (jpeg.isEmpty()) return
        root.mkdirs()

        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, QUESTION_FILE).writeBytes(encodeThumbnail(jpeg))
        File(dir, RESULT_FILE).writeText(serializeResult(result), Charsets.UTF_8)

        val entry = JSONObject()
            .put("id", id)
            .put("subject", result.subject)
            .put("timestamp", System.currentTimeMillis())

        val entries = ArrayList<JSONObject>()
        entries.add(entry)
        entries.addAll(loadIndex())

        // 先按条数截断，再按总容量截断。
        // 单条 result.json 里是整份答案 HTML，遇到带内嵌图的题目可以很大，
        // 只限条数挡不住体积膨胀，所以两个维度都要管。
        val kept = ArrayList<JSONObject>(MAX_ENTRIES)
        var totalBytes = 0L
        for (candidate in entries) {
            if (kept.size >= MAX_ENTRIES) break
            val id = candidate.optString("id")
            if (id.isEmpty()) continue
            val dir = File(root, id)
            val size = dirSize(dir)
            // 至少留一条，否则一道超大题会把历史清空
            if (kept.isNotEmpty() && totalBytes + size > MAX_TOTAL_BYTES) break
            totalBytes += size
            kept.add(candidate)
        }

        entries.filter { it !in kept }.forEach { stale ->
            stale.optString("id").takeIf { it.isNotEmpty() }?.let { oldId ->
                File(root, oldId).deleteRecursively()
            }
        }
        writeIndex(kept)
        bumpStats()
    }

    /** 历史占用的字节数（列表页展示用）。 */
    fun totalBytes(): Long {
        if (!root.exists()) return 0L
        return root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    fun list(limit: Int = MAX_ENTRIES): List<HistoryEntry> =
        loadIndex().take(limit).mapNotNull { json ->
            val id = json.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            val dir = File(root, id)
            val question = File(dir, QUESTION_FILE)
            val result = File(dir, RESULT_FILE)
            if (!question.isFile || !result.isFile) return@mapNotNull null
            HistoryEntry(
                id = id,
                subject = json.optString("subject"),
                timestamp = json.optLong("timestamp"),
                questionFile = question,
                resultFile = result,
            )
        }

    fun loadResult(entry: HistoryEntry): SearchResult? = try {
        deserializeResult(entry.resultFile.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun clear() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ 统计

    fun todayCount(): Int {
        rolloverIfNeeded()
        return stats.getInt(KEY_TODAY, 0)
    }

    fun totalCount(): Int = stats.getInt(KEY_TOTAL, 0)

    private fun bumpStats() {
        rolloverIfNeeded()
        stats.edit()
            .putInt(KEY_TODAY, stats.getInt(KEY_TODAY, 0) + 1)
            .putInt(KEY_TOTAL, stats.getInt(KEY_TOTAL, 0) + 1)
            .apply()
    }

    private fun rolloverIfNeeded() {
        val today = dayStamp()
        if (stats.getString(KEY_DAY, null) != today) {
            stats.edit().putString(KEY_DAY, today).putInt(KEY_TODAY, 0).apply()
        }
    }

    private fun dayStamp(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ------------------------------------------------------------------ 序列化

    private fun serializeResult(result: SearchResult): String {
        val items = JSONArray()
        for (item in result.items) {
            items.put(
                JSONObject()
                    .put("index", item.index)
                    .put("title", item.title)
                    .put("questionHtml", item.questionHtml)
                    .put("questionImages", JSONArray(item.questionImages))
                    .put("answerHtml", item.answerHtml)
                    .put("answerImages", JSONArray(item.answerImages))
                    .put("analysisHtml", item.analysisHtml)
                    .put("subject", item.subject)
                    .put("rawHtml", item.rawHtml ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("sid", result.sid)
            .put("subject", result.subject)
            .put("items", items)
            .toString()
    }

    private fun deserializeResult(text: String): SearchResult {
        val rootJson = JSONObject(text)
        val array = rootJson.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<AnswerItem>(array.length())
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            items.add(
                AnswerItem(
                    index = json.optInt("index", i + 1),
                    title = json.optString("title"),
                    questionHtml = json.optString("questionHtml"),
                    questionImages = json.optJSONArray("questionImages").toStringList(),
                    answerHtml = json.optString("answerHtml"),
                    answerImages = json.optJSONArray("answerImages").toStringList(),
                    analysisHtml = json.optString("analysisHtml"),
                    subject = json.optString("subject"),
                    rawHtml = json.optString("rawHtml").takeIf { it.isNotEmpty() && it != "null" },
                ),
            )
        }
        return SearchResult(
            items = items,
            sid = rootJson.optString("sid"),
            subject = rootJson.optString("subject"),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i, "")
            if (value.isNotEmpty()) list.add(value)
        }
        return list
    }

    // ------------------------------------------------------------------ 索引

    private fun loadIndex(): List<JSONObject> = try {
        if (!indexFile.isFile) {
            emptyList()
        } else {
            val array = JSONArray(indexFile.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeIndex(entries: List<JSONObject>) {
        root.mkdirs()
        val array = JSONArray()
        entries.forEach { array.put(it) }
        indexFile.writeText(array.toString(), Charsets.UTF_8)
    }

    /** 列表缩略图与详情页共用同一份题目图，最长边压到 1080。 */
    private fun encodeThumbnail(jpeg: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1080) sample *= 2

        val bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return jpeg

        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val PREFS_STATS = "search_stats"
        const val KEY_TOTAL = "total"
        const val KEY_TODAY = "today"
        const val KEY_DAY = "day"
        const val QUESTION_FILE = "question.jpg"
        const val RESULT_FILE = "result.json"
        const val MAX_ENTRIES = 20

        /** 历史总容量上限，超出就丢最旧的。 */
        const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}

/** 相对时间文案，用于历史列表。 */
fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 172_800_000 -> "昨天"
        diff < 604_800_000 -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(timestamp))
    }
}

/** 今日日期文案，例如「10月1日 星期三」。 */
fun todayLabel(): String {
    val now = Date()
    val date = SimpleDateFormat("M月d日", Locale.CHINA).format(now)
    val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
    return "$date $week"
}
