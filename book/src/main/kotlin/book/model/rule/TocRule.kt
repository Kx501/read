package book.model.rule

data class TocRule(
    var preUpdateJs: String? = null,
    var chapterList: String? = null,
    var chapterName: String? = null,
    var chapterUrl: String? = null,
    var formatJs: String? = null,
    var isVolume: String? = null,
    var isVip: String? = null,
    var isPay: String? = null,
    var updateTime: String? = null,
    var nextTocUrl: String? = null,
    /** 目录结构化（items/groups）；null 或 flat 仅用平面字段 */
    var layout: TocScreenConfig? = null,
)
