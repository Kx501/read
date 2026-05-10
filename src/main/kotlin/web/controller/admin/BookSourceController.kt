package web.controller.admin

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper
import com.google.gson.Gson
import org.noear.solon.annotation.Body
import org.noear.solon.annotation.Controller
import org.noear.solon.annotation.Get
import org.noear.solon.annotation.Inject
import org.noear.solon.annotation.Mapping
import org.noear.solon.annotation.Param
import org.noear.solon.annotation.Post
import org.noear.solon.core.handle.UploadedFile
import org.noear.solon.core.util.DataThrowable
import web.mapper.BookSourceMapper
import web.model.BookSource
import web.notification.Source
import web.response.*
import web.util.page.PageByAjax
import java.io.EOFException
import java.util.Date
import book.model.BookSource as Booksource


@Controller
@Mapping("/admin")
class BookSourceController {

    @Inject
    lateinit var bookSourceMapper: BookSourceMapper

    @Get
    @Mapping("/seachbookSource")
    fun seachbookSource(where:String?, order:String?, @Param(defaultValue = "1") page:Int, @Param(defaultValue = "20")  limit:Int) = run{
        val queryWrapper: QueryWrapper<BookSource> = QueryWrapper()
        if(!where.isNullOrBlank()){
            queryWrapper.like("book_source_url",where).or().like("book_source_name",where).or().like("book_source_group",where)
        }
        PageByAjax(bookSourceMapper,queryWrapper,page,limit,order)
    }

    @Mapping("/uploadSource")
    fun uploadSource(file: UploadedFile) = run{
        val content = String(file.contentAsBytes)
        var insert = 0
        var update = 0
        try {
            if (content.trim().startsWith("[")){
                //数组
                val bookSourcelist= Booksource.fromJsonArray(content).getOrNull()
                bookSourcelist?.forEach {
                    runCatching {
                        addorupdate(it).let {  (ins,ups)->
                            insert += ins
                            update += ups
                        }
                    }.onFailure {
                        it.printStackTrace()
                    }
                }
            }else{
                //单独一个
                val bookSource =Booksource.fromJson(content).getOrNull()?:Booksource()
                if (bookSource.bookSourceUrl.isBlank()){
                    throw DataThrowable().data(JsonResponse(false, SOURCE_URL_BANK))
                }
                addorupdate(bookSource).let {  (ins,ups)->
                    insert += ins
                    update += ups
                }
            }
        }catch (e: EOFException){
            throw DataThrowable().data(JsonResponse(false, JSON_ERROR))
        }catch (e:Exception){
            e.printStackTrace()
            throw DataThrowable().data(JsonResponse(false, DO_ERROR))
        }
        Source.sendNotification()
        JsonResponse(true,"新增${insert}条书源，更新${update}条书源")
    }

    @Mapping("/delbookSource")
    fun delbookSource(id: String?) = run{
        if (id.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        bookSourceMapper.getBookSource(id) ?: throw DataThrowable().data(JsonResponse(false, NOT_IS))
        bookSourceMapper.deleteById(id)
        Source.sendNotification()
        JsonResponse(true)
    }

    @Mapping("/delbookSources")
    fun delbookSources(@Body ids: List<String>?) = run{
        ids?.forEach {id->
            if (id.isNotBlank()){
                bookSourceMapper.deleteById(id)
            }
        }
        Source.sendNotification()
        JsonResponse(true)
    }

    @Mapping("/stopbookSource")
    fun stopbookSource(id: String? ,st: String?)= run{
        if (id.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        bookSourceMapper.getBookSource(id) ?: throw DataThrowable().data(JsonResponse(false, NOT_IS))
        when(st){
            "0"->{
                bookSourceMapper.changeEnabled(id,false)
            }
            "1"->{
                bookSourceMapper.changeEnabled(id,true)
            }
            else -> throw DataThrowable().data(JsonResponse(false, USE_ERROE))
        }
        Source.sendNotification()
        JsonResponse(true)
    }

    @Mapping("/stopbookSourceExplore")
    fun stopbookSourceExplore( id: String? ,st: String?)= run{
        if (id.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        bookSourceMapper.getBookSource(id) ?: throw DataThrowable().data(JsonResponse(false, NOT_IS))
        when(st){
            "0"->{
                bookSourceMapper.changeenabledExplore(id,false)
            }
            "1"->{
                bookSourceMapper.changeenabledExplore(id,true)
            }
            else -> throw DataThrowable().data(JsonResponse(false, USE_ERROE))
        }
        Source.sendNotification()
        JsonResponse(true)
    }

    @Post
    @Mapping("/patchBookSourceText")
    fun patchBookSourceText(@Body patch: BookSourceTextPatch?) = run {
        val url = patch?.bookSourceUrl?.trim()
        val field = patch?.field?.trim()
        if (url.isNullOrBlank()) {
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        if (field.isNullOrBlank()) {
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        val allowed = setOf("bookSourceName", "bookSourceGroup", "bookSourceComment")
        if (field !in allowed) {
            throw DataThrowable().data(JsonResponse(false, USE_ERROE))
        }
        val row = bookSourceMapper.getBookSource(url)
            ?: throw DataThrowable().data(JsonResponse(false, NOT_IS))
        val rawJson = row.json
        val domain = if (!rawJson.isNullOrBlank()) {
            Booksource.fromJson(rawJson).getOrNull()
                ?: throw DataThrowable().data(JsonResponse(false, SOURCE_JSON_ERROR))
        } else {
            Booksource(bookSourceUrl = row.bookSourceUrl ?: "")
        }
        domain.bookSourceUrl = row.bookSourceUrl ?: domain.bookSourceUrl
        val v = patch.value
        when (field) {
            "bookSourceName" -> {
                row.bookSourceName = v
                domain.bookSourceName = v ?: ""
            }
            "bookSourceGroup" -> {
                row.bookSourceGroup = v
                domain.bookSourceGroup = v
            }
            "bookSourceComment" -> {
                row.bookSourceComment = v
                domain.bookSourceComment = v
            }
        }
        val now = Date().time
        row.lastUpdateTime = now
        domain.lastUpdateTime = now
        row.json = Gson().toJson(domain)
        bookSourceMapper.updateById(row)
        Source.sendNotification()
        JsonResponse(true).Data(row.json ?: "{}")
    }

    @Mapping("/topSource")
    fun topSource( id: String?)= run{
        if (id.isNullOrBlank()){
            throw DataThrowable().data(JsonResponse(false, NOT_BANK))
        }
        val bookSource= bookSourceMapper.getBookSource(id) ?: throw DataThrowable().data(JsonResponse(false, NOT_IS))
        val sources = bookSourceMapper.getallBookSourcelist()
        var order=1
        for( it in sources!!){
            if(it.bookSourceUrl == bookSource.bookSourceUrl){
                bookSourceMapper.changeorder(it.bookSourceUrl?:"", 0)
            }else{
                bookSourceMapper.changeorder(it.bookSourceUrl?:"", order)
                order++
            }
        }
        Source.sendNotification()
        JsonResponse(true)
    }




    private fun addorupdate(bookSource: Booksource) = run{
        var insert = 0
        var update = 0
        val source=BookSource().jsontomodel(bookSource)
        bookSourceMapper.getBookSource(bookSource.bookSourceUrl).let {
            if (it != null){
                source.enabled=it.enabled
                if(it.createtime != null){
                    source.createtime=it.createtime
                }
                source.sourceorder=it.sourceorder
                bookSource.lastUpdateTime= Date().time
                update += bookSourceMapper.updateById(source)
            }else{
                source.enabled=true
                source.sourceorder=9999
                insert += bookSourceMapper.insert(source)
            }
        }
        Pair(insert, update)
    }

}

/** 管理员表格行内编辑：仅允许修改与 json 同步的纯文字列 */
class BookSourceTextPatch {
    var bookSourceUrl: String? = null
    var field: String? = null
    var value: String? = null
}