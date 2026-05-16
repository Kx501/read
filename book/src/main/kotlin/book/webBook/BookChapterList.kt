package book.webBook

import book.model.Book
import book.model.BookChapter
import book.model.BookSource
import book.model.rule.TocRule
import book.model.rule.TocScreenJsonParser
import book.util.TextUtils
import book.util.isTrue
import book.webBook.analyzeRule.AnalyzeRule
import book.webBook.analyzeRule.AnalyzeUrl
import book.webBook.exception.TocEmptyException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element

object BookChapterList {

    suspend fun analyzeChapterList(
        book: Book,
        body: String?,
        bookSource: BookSource,
        baseUrl: String,
        redirectUrl: String,
        debugLog: DebugLog? = null
    ): List<BookChapter> {
        body ?: throw Exception(
//            App.INSTANCE.getString(R.string.error_get_web_content, baseUrl)
            //todo getString
            "error_get_web_content"
        )
        val chapterList = arrayListOf<BookChapter>()
        debugLog?.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        // debugLog?.log(bookSource.bookSourceUrl, body)
        val tocRule = bookSource.getTocRule()
        val nextUrlList = arrayListOf(redirectUrl)
        var reverse = false
        var listRule = tocRule.chapterList ?: ""
        if (listRule.startsWith("-")) {
            reverse = true
            listRule = listRule.substring(1)
        }
        if (listRule.startsWith("+")) {
            listRule = listRule.substring(1)
        }
        var chapterData =
            analyzeChapterList(
                book, baseUrl, redirectUrl, body,
                tocRule, listRule, bookSource, true, true, debugLog
            )
        chapterList.addAll(chapterData.first)
        when (chapterData.second.size) {
            0 -> Unit
            1 -> {
                var nextUrl = chapterData.second[0]
                while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                    nextUrlList.add(nextUrl)
                    AnalyzeUrl(
                        mUrl = nextUrl,
                        source = bookSource,
                        ruleData = book,
                        headerMapF = bookSource.getHeaderMap(), debugLog = debugLog
                    ).getStrResponseAwait().body?.let { nextBody ->
                        chapterData = analyzeChapterList(
                            book, nextUrl, nextUrl,
                            nextBody, tocRule, listRule, bookSource, true, false, debugLog
                        )
                        nextUrl = chapterData.second.firstOrNull() ?: ""
                        chapterList.addAll(chapterData.first)
                    }
                }
                debugLog?.log(bookSource.bookSourceUrl, "◇目录总页数:${nextUrlList.size}")
            }
            else -> {
                debugLog?.log(bookSource.bookSourceUrl, "◇并发解析目录,总页数:${chapterData.second.size}")
                withContext(IO) {
                    val asyncArray = Array(chapterData.second.size) {
                        async(IO) {
                            val urlStr = chapterData.second[it]
                            val analyzeUrl = AnalyzeUrl(
                                mUrl = urlStr,
                                source = bookSource,
                                ruleData = book,
                                headerMapF = bookSource.getHeaderMap(), debugLog = debugLog
                            )
                            val res = analyzeUrl.getStrResponseAwait()
                            analyzeChapterList(
                                book, urlStr, res.url,
                                res.body!!, tocRule, listRule, bookSource, false, false, debugLog
                            ).first
                        }
                    }
                    asyncArray.forEach { coroutine ->
                        chapterList.addAll(coroutine.await())
                    }
                }
            }
        }
        if (chapterList.isEmpty()) {
            throw TocEmptyException("目录为空")
        }
        //去重
        if (!reverse) {
            chapterList.reverse()
        }
        val lh = LinkedHashSet(chapterList)
        val list = ArrayList(lh)
        // if (!book.getReverseToc()) {
        list.reverse()
        // }
        debugLog?.log(book.origin, "◇目录总数:${list.size}")
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
        }
        if (list.isNotEmpty()) {
            book.latestChapterTitle = list.last().title
        }
//        book.durChapterTitle =
//            list.getOrNull(book.durChapterIndex)?.title ?: book.latestChapterTitle
        if (book.totalChapterNum < list.size) {
            book.lastCheckCount = list.size - book.totalChapterNum
            // book.latestChapterTime = System.currentTimeMillis()
            // book.lastCheckTime = System.currentTimeMillis()
        }
        book.totalChapterNum = list.size
        return list
    }

    private fun analyzeChapterList(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        tocRule: TocRule,
        listRule: String,
        bookSource: BookSource,
        getNextUrl: Boolean = true,
        log: Boolean = false,
        debugLog: DebugLog? = null
    ): Pair<List<BookChapter>, List<String>> {
        val analyzeRule = AnalyzeRule(book, debugLog, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        //获取目录列表
        val chapterList = arrayListOf<BookChapter>()
        val userid = bookSource.userid ?: ""
        if (log) debugLog?.log(bookSource.bookSourceUrl, "┌获取目录列表")
        //获取下一页链接
        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (getNextUrl && !nextTocRule.isNullOrEmpty()) {
            if (log) debugLog?.log(bookSource.bookSourceUrl, "┌获取目录下一页列表")
            analyzeRule.getStringList(nextTocRule, isUrl = true)?.let {
                for (item in it) {
                    if (item != redirectUrl) {
                        nextUrlList.add(item)
                    }
                }
            }
            if (log) debugLog?.log(bookSource.bookSourceUrl, "└" + TextUtils.join("，\n", nextUrlList))
        }
        val tocLayout = tocRule.layout
        when (tocLayout?.effectiveMode()) {
            "items" -> {
                val js = tocLayout.itemsJs?.trim().orEmpty()
                if (js.isNotEmpty()) {
                    val raw = kotlin.runCatching { analyzeRule.evalJS(js, body) }.getOrNull()
                    val tree = TocScreenJsonParser.parseAnyToJsonElement(raw)
                    if (tree != null) {
                        val fromJson = TocScreenJsonParser.parseToChapters(
                            tree, book.bookUrl, baseUrl, redirectUrl, userid
                        )
                        if (fromJson.isNotEmpty()) {
                            chapterList.addAll(fromJson)
                            if (log) {
                                debugLog?.log(
                                    bookSource.bookSourceUrl,
                                    "◇toc.layout items 解析:${fromJson.size}"
                                )
                            }
                            logFirstChapter(bookSource, chapterList, log, debugLog)
                            return Pair(chapterList, nextUrlList)
                        }
                    }
                }
            }
            "groups" -> {
                val g = tocLayout.groups
                val gl = g?.groupList?.trim().orEmpty()
                val cl = g?.chapterList?.trim().orEmpty()
                if (g != null && gl.isNotEmpty() && cl.isNotEmpty()) {
                    val groupsEls = analyzeRule.getElements(gl)
                    if (groupsEls.isNotEmpty()) {
                        for (groupEl in groupsEls) {
                            val vtRule = g.volumeTitle?.trim().orEmpty()
                            if (vtRule.isNotEmpty()) {
                                val volCtx = when (g.volumeAnchor?.trim()?.lowercase()) {
                                    "prev", "previous" ->
                                        (groupEl as? Element)?.previousElementSibling()
                                    else -> groupEl
                                }
                                analyzeRule.setContent(volCtx ?: groupEl)
                                val volTitle = analyzeRule.getString(
                                    analyzeRule.splitSourceRule(vtRule)
                                )
                                if (volTitle.isNotEmpty()) {
                                    val vol = BookChapter(
                                        bookUrl = book.bookUrl,
                                        baseUrl = redirectUrl,
                                        userid = userid
                                    )
                                    vol.title = volTitle
                                    vol.isVolume = true
                                    vol.url = volTitle + chapterList.size
                                    chapterList.add(vol)
                                }
                            }
                            analyzeRule.setContent(groupEl)
                            val eps = analyzeRule.getElements(cl)
                            parseElementsIntoChapterList(
                                eps, chapterList, analyzeRule, tocRule, book,
                                redirectUrl, baseUrl, bookSource, chapterList.size,
                                log, debugLog,
                                g.chapterName, g.chapterUrl
                            )
                        }
                        if (log) {
                            debugLog?.log(
                                bookSource.bookSourceUrl,
                                "◇toc.layout groups 解析:${chapterList.size}"
                            )
                        }
                        logFirstChapter(bookSource, chapterList, log, debugLog)
                        return Pair(chapterList, nextUrlList)
                    }
                }
            }
        }
        val elements = analyzeRule.getElements(listRule)
        if (log) debugLog?.log(bookSource.bookSourceUrl, "└列表大小:${elements.size}")
        if (elements.isNotEmpty()) {
            if (log) debugLog?.log(bookSource.bookSourceUrl, "┌解析目录列表")
            parseElementsIntoChapterList(
                elements, chapterList, analyzeRule, tocRule, book,
                redirectUrl, baseUrl, bookSource, 0, log, debugLog
            )
            if (log) debugLog?.log(bookSource.bookSourceUrl, "└目录列表解析完成")
            if (chapterList.isEmpty()) {
                if (log) debugLog?.log(bookSource.bookSourceUrl, "◇章节列表为空")
            } else {
                logFirstChapter(bookSource, chapterList, log, debugLog)
            }
        }
        return Pair(chapterList, nextUrlList)
    }

    private fun logFirstChapter(
        bookSource: BookSource,
        chapterList: List<BookChapter>,
        log: Boolean,
        debugLog: DebugLog?,
    ) {
        if (!log || chapterList.isEmpty()) return
        debugLog?.log(bookSource.bookSourceUrl, "≡首章信息")
        debugLog?.log(bookSource.bookSourceUrl, "◇章节名称:${chapterList[0].title}")
        debugLog?.log(bookSource.bookSourceUrl, "◇章节链接:${chapterList[0].url}")
        debugLog?.log(bookSource.bookSourceUrl, "◇章节信息:${chapterList[0].tag}")
        debugLog?.log(bookSource.bookSourceUrl, "◇是否卷名:${chapterList[0].isVolume}")
    }

    private fun parseElementsIntoChapterList(
        elements: List<Any>,
        chapterList: ArrayList<BookChapter>,
        analyzeRule: AnalyzeRule,
        tocRule: TocRule,
        book: Book,
        redirectUrl: String,
        baseUrl: String,
        bookSource: BookSource,
        startIndex: Int,
        log: Boolean,
        debugLog: DebugLog?,
        chapterNameOverride: String? = null,
        chapterUrlOverride: String? = null,
    ) {
        val userid = bookSource.userid ?: ""
        val nameRule = analyzeRule.splitSourceRule(
            chapterNameOverride?.trim()?.takeIf { it.isNotEmpty() } ?: tocRule.chapterName
        )
        val urlRule = analyzeRule.splitSourceRule(
            chapterUrlOverride?.trim()?.takeIf { it.isNotEmpty() } ?: tocRule.chapterUrl
        )
        val vipRule = analyzeRule.splitSourceRule(tocRule.isVip)
        val payRule = analyzeRule.splitSourceRule(tocRule.isPay)
        val upTimeRule = analyzeRule.splitSourceRule(tocRule.updateTime)
        val isVolumeRule = analyzeRule.splitSourceRule(tocRule.isVolume)
        elements.forEachIndexed { i, raw ->
            val index = startIndex + i
            analyzeRule.setContent(raw)
            val bookChapter = BookChapter(bookUrl = book.bookUrl, baseUrl = redirectUrl, userid = userid)
            analyzeRule.chapter = bookChapter
            bookChapter.title = analyzeRule.getString(nameRule)
            bookChapter.url = analyzeRule.getString(urlRule)
            bookChapter.tag = analyzeRule.getString(upTimeRule)
            val isVolume = analyzeRule.getString(isVolumeRule)
            bookChapter.isVolume = false
            if (isVolume.isTrue()) {
                bookChapter.isVolume = true
            }
            if (bookChapter.url.isEmpty()) {
                if (bookChapter.isVolume) {
                    bookChapter.url = bookChapter.title + index
                    if (log) {
                        debugLog?.log(
                            bookSource.bookSourceUrl,
                            "⇒一级目录${index}未获取到url,使用标题替代"
                        )
                    }
                } else {
                    bookChapter.url = baseUrl
                    if (log) {
                        debugLog?.log(
                            bookSource.bookSourceUrl,
                            "⇒目录${index}未获取到url,使用baseUrl替代"
                        )
                    }
                }
            }
            if (bookChapter.title.isNotEmpty()) {
                val isVip = analyzeRule.getString(vipRule)
                bookChapter.isVip = isVip.isTrue()
                if (isVip.isTrue()) {
                    bookChapter.title = "\uD83D\uDD12" + bookChapter.title
                }
                val isPay = analyzeRule.getString(payRule)
                bookChapter.isPay = isPay.isTrue()
                chapterList.add(bookChapter)
            }
        }
    }
}
