package book.model.rule

import book.model.BookChapter
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * 可选的目录扩展，存于 [TocRule.layout]。`mode=flat` 或未配置时完全走 [TocRule] 平面字段。
 *
 * - **items**：`itemsJs` 对当前目录页 `result` 求值，返回 JSON 数组（或字符串化的 JSON）。
 * - **groups**：按 `groups.groupList` 取多块根节点，每块先取 `volumeTitle` 为卷行，再按组内规则解析集。
 */
data class TocScreenConfig(
    val mode: String = "flat",
    val itemsJs: String? = null,
    val groups: TocScreenGroups? = null,
) {

    fun effectiveMode(): String = mode.trim().lowercase().ifEmpty { "flat" }
}

data class TocScreenGroups(
    val groupList: String? = null,
    /** 卷标题规则相对节点：`prev` 为组根上一元素兄弟 */
    val volumeAnchor: String? = null,
    val volumeTitle: String? = null,
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null,
)

object TocScreenJsonParser {

    fun parseToChapters(
        root: JsonElement,
        bookUrl: String,
        baseUrl: String,
        redirectUrl: String,
        userid: String = "",
    ): List<BookChapter> {
        val out = ArrayList<BookChapter>()
        val syn = java.util.concurrent.atomic.AtomicInteger(0)
        when {
            root.isJsonArray -> root.asJsonArray.forEach {
                appendNode(it, bookUrl, baseUrl, redirectUrl, userid, out, syn)
            }
            root.isJsonObject -> appendNode(root, bookUrl, baseUrl, redirectUrl, userid, out, syn)
        }
        return out
    }

    fun parseAnyToJsonElement(raw: Any?): JsonElement? {
        if (raw == null) return null
        val str = when (raw) {
            is String -> raw.trim()
            else -> raw.toString().trim()
        }
        if (str.isEmpty()) return null
        return kotlin.runCatching { JsonParser.parseString(str) }.getOrNull()
    }

    private fun appendNode(
        el: JsonElement,
        bookUrl: String,
        baseUrl: String,
        redirectUrl: String,
        userid: String,
        out: MutableList<BookChapter>,
        syn: java.util.concurrent.atomic.AtomicInteger,
    ) {
        if (!el.isJsonObject) return
        val o = el.asJsonObject
        val title = o.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (title.isEmpty()) return
        val isVolume = o.get("isVolume")?.takeIf { !it.isJsonNull }?.asBoolean == true
        val url = o.get("url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val ch = BookChapter(bookUrl = bookUrl, baseUrl = redirectUrl, userid = userid)
        ch.title = title
        ch.isVolume = isVolume
        ch.url = when {
            url.isNotEmpty() -> url
            isVolume -> title + syn.getAndIncrement()
            else -> baseUrl
        }
        out.add(ch)
        val children = o.get("children")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        children.forEach { appendNode(it, bookUrl, baseUrl, redirectUrl, userid, out, syn) }
    }
}
