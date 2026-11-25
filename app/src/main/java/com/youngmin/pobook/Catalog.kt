package com.youngmin.pobook

import android.content.Context
import android.text.Html
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
        if (isNull(index)) null else optString(index).trim().ifEmpty { null }
    }

internal fun JSONObject?.text(key: String): String =
    if (this == null || isNull(key)) "" else optString(key).trim()

internal fun Context.asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

private fun String.plain(): String = Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString().trim()

internal fun String.searchKey(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

internal fun httpsUrl(value: String): String {
    val uri = runCatching { URI(value) }.getOrNull()
    return value.takeIf { uri?.scheme.equals("https", true) && uri?.host != null && uri.userInfo == null }.orEmpty()
}

internal data class Book(
    val id: String,
    val title: String,
    val author: String,
    val isbn: String = "",
    val publisher: String = "",
    val year: String = "",
    val cover: String = "",
    val type: String = "",
    val location: String = "",
    val description: String = "",
) {
    val recordId get() = id.removePrefix("primo:")

    fun toJson() = JSONObject().put("id", id).put("title", title).put("author", author)
        .put("isbn", isbn).put("publisher", publisher).put("publicationYear", year)
        .put("imageURL", cover).put("type", type).put("subLocation", location)
        .put("description", description)

    companion object {
        fun fromJson(value: JSONObject) = Book(
            id = value.text("id"),
            title = value.text("title"),
            author = value.text("author").ifEmpty { "저자 정보 없음" },
            isbn = value.text("isbn"),
            publisher = value.text("publisher"),
            year = value.text("publicationYear"),
            cover = httpsUrl(value.text("imageURL")),
            type = value.text("type"),
            location = value.text("subLocation"),
            description = value.text("description"),
        )
    }
}

internal data class Holding(
    val institution: String,
    val library: String,
    val code: String,
    val name: String,
    val call: String,
    val availability: String,
    val id: String = "",
) {
    val availabilityLabel get() = when (availability) {
        "available" -> "대출 가능"
        "unavailable" -> "대출 중 또는 이용 불가"
        else -> "대출 상태 확인 필요"
    }
}

internal data class BookDescription(
    val text: String = "",
    val source: String = "",
    val cover: String = "",
    val spine: String = "",
)

internal enum class Category(val label: String, val types: List<String>) {
    ALL("전체", listOf("books", "printed_book", "journals")),
    PRINT("단행본", listOf("books", "printed_book")),
    JOURNAL("학술지", listOf("journals"));
}

internal data class Affiliation(val parent: String, val group: String, val name: String, val records: List<String>) {
    val label get() = listOf(group, name).filter(String::isNotEmpty).joinToString(" · ")
}
internal data class Interest(val name: String, val records: List<String>)
internal data class CatalogPage(val books: List<Book>, val hasMore: Boolean, val bundled: Boolean = false)
internal data class HoldingsResult(val items: List<Holding>, val bundled: Boolean = false)

internal class Catalog(context: Context) {
    private val data = JSONObject(context.asset("recommendations.json"))
    private val bundled = data.optJSONArray("books").objects().mapNotNull { record ->
        val book = record.optJSONObject("book")?.let(Book::fromJson) ?: return@mapNotNull null
        val holdings = record.optJSONArray("holdings").objects().map {
            Holding(it.text("institutionCode"), it.text("libraryCode"), it.text("locationCode"),
                it.text("locationName"), it.text("callNumber"), "", it.text("holdingID"))
        }
        book.recordId to (book.copy(type = record.text("resourceType")) to holdings)
    }.toMap()
    val affiliations = data.optJSONArray("affiliations").objects().map {
        Affiliation(it.text("parent"), it.text("group"), it.text("name"), it.optJSONArray("recordIDs").strings())
    }
    val interests = data.optJSONArray("preferences").objects().map {
        Interest(it.text("name"), it.optJSONArray("recordIDs").strings())
    }
    private val relay = CatalogRelay()
    private val holdingsCache = LruCache<String, CachedHoldings>(128)
    private val descriptions = LruCache<String, BookDescription>(128)
    private data class CachedHoldings(val expiresAt: Long, val result: HoldingsResult)

    fun byRecords(records: List<String>): List<Book> = records.distinct().mapNotNull { bundled[it]?.first }

    suspend fun search(query: String, category: Category, offset: Int = 0): CatalogPage {
        require(query.isNotBlank() && query.length <= 200)
        return try {
            val response = relay.primo("any,contains,${query.replace(',', ' ')}", category, offset)
            val docs = response.getJSONArray("docs")
            val documents = docs.objects()
            val books = documents.mapNotNull(::parsePrimo).distinctBy(Book::id)
            documents.forEach { document ->
                val record = document.optJSONObject("pnx")?.optJSONObject("control")?.optJSONArray("recordid")
                    .strings().firstOrNull() ?: return@forEach
                val holdings = parseHoldings(document)
                if (holdings.isNotEmpty()) cacheHoldings(record, holdings)
            }
            if (docs.length() > 0 && books.isEmpty()) throw IOException("Unexpected catalog response")
            val total = response.optJSONObject("info")?.optInt("total", -1) ?: -1
            CatalogPage(books, if (total >= 0) offset + docs.length() < total else docs.length() >= PAGE_SIZE)
        } catch (_: IOException) {
            localSearch(query, category)
        } catch (_: org.json.JSONException) {
            localSearch(query, category)
        }
    }

    private fun localSearch(query: String, category: Category): CatalogPage {
        val words = query.searchKey().split(Regex("\\s+"))
        val books = if (category == Category.JOURNAL) emptyList() else bundled.values.map { it.first }.filter { book ->
            val metadata = "${book.title} ${book.author} ${book.isbn}".searchKey()
            words.all { it in metadata }
        }
        return CatalogPage(books, false, bundled = true)
    }

    private fun cacheHoldings(record: String, holdings: List<Holding>): HoldingsResult {
        val result = HoldingsResult(holdings)
        holdingsCache.put(record, CachedHoldings(android.os.SystemClock.elapsedRealtime() + 180_000, result))
        return result
    }

    fun bundledCover(book: Book): String = bundled[book.recordId]?.first?.cover.orEmpty()

    fun knownHoldings(book: Book): List<Holding> =
        holdingsCache.get(book.recordId)?.result?.items ?: bundled[book.recordId]?.second.orEmpty()

    suspend fun holdings(book: Book): HoldingsResult {
        val record = book.recordId
        holdingsCache.get(record)?.takeIf { it.expiresAt > android.os.SystemClock.elapsedRealtime() }?.let { return it.result }
        if (Regex("^alma[0-9]{10,30}$").matches(record)) {
            try {
                val doc = relay.primo("any,contains,${record.drop(4)}", timeout = 15_000)
                    .getJSONArray("docs").objects().firstOrNull {
                        it.optJSONObject("pnx")?.optJSONObject("control")
                            ?.optJSONArray("recordid").strings().contains(record)
                    }
                if (doc != null) {
                    return cacheHoldings(record, parseHoldings(doc))
                }
            } catch (_: IOException) {
                return HoldingsResult(bundled[record]?.second.orEmpty(), bundled = true)
            } catch (_: org.json.JSONException) {
                return HoldingsResult(bundled[record]?.second.orEmpty(), bundled = true)
            }
        }
        return HoldingsResult(bundled[record]?.second.orEmpty(), bundled = true)
    }

    suspend fun description(book: Book): BookDescription {
        if (book.isbn.isEmpty()) return BookDescription(book.description)
        descriptions.get(book.isbn)?.let { return it }
        val item = try {
            relay.get("yes24/v1/goods/itemDetail", mapOf("searchType" to "ISBN13", "query" to book.isbn), 10_000)
                .optJSONObject("data")?.optJSONArray("items").objects().firstOrNull { it.text("isbn13") == book.isbn }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
        val yes24Source = yes24Url(item.text("link"))
        val yes24Text = item?.optJSONObject("contentDetail").text("bookIntroduction").plain()
        val useYes24 = yes24Source.isNotEmpty() && yes24Text.isNotEmpty()
        val libraryItem = if (useYes24 && yes24Url(item.text("cover")).isNotEmpty()) null else libraryDetail(book.isbn)
        val result = BookDescription(
            if (useYes24) yes24Text else libraryItem.text("description").plain().ifEmpty { book.description },
            if (useYes24) yes24Source else httpsUrl(libraryItem.text("bookDtlUrl")),
            yes24Url(item.text("cover")).ifEmpty { httpsUrl(libraryItem.text("bookImageURL").replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")) },
            yes24Url(item.text("sideCover")),
        )
        if (result.text.isNotEmpty() || result.cover.isNotEmpty()) descriptions.put(book.isbn, result)
        return result
    }

    private suspend fun libraryDetail(isbn: String): JSONObject? = try {
        relay.get("data4library/api/srchDtlList", mapOf("isbn13" to isbn, "format" to "json"), 10_000)
            .optJSONObject("response")?.optJSONArray("detail").objects().firstOrNull()?.optJSONObject("book")
    } catch (_: IOException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }

    suspend fun related(book: Book): List<Book> {
        if (book.isbn.isEmpty()) return emptyList()
        val response = try {
            relay.get("data4library/api/recommandList",
                mapOf("isbn13" to book.isbn, "type" to "mania", "format" to "json"), 10_000)
                .optJSONObject("response")
        } catch (_: IOException) {
            return emptyList()
        } catch (_: org.json.JSONException) {
            return emptyList()
        }
        val matching = response?.optJSONArray("docs").objects().map { it.optJSONObject("book").text("isbn13") }
            .filter { isbn -> Regex("97[89][0-9]{10}").matches(isbn) }.distinct().take(8)
        if (matching.isEmpty()) return emptyList()
        val available = bundled.values.map { it.first }.filter { it.isbn in matching }
        val remote = try {
            relay.primo(matching.joinToString(" OR ") { "isbn,exact,$it" }, Category.ALL, timeout = 10_000)
                .optJSONArray("docs").objects().mapNotNull(::parsePrimo)
        } catch (_: IOException) {
            emptyList()
        } catch (_: org.json.JSONException) {
            emptyList()
        }
        return (remote + available).filter { it.isbn in matching && it.id != book.id }.distinctBy(Book::id)
            .sortedBy { matching.indexOf(it.isbn) }
    }

    private fun yes24Url(value: String): String {
        val address = httpsUrl(value)
        val host = URI(address).host?.lowercase(Locale.ROOT)
        return address.takeIf { host == "yes24.com" || host?.endsWith(".yes24.com") == true }.orEmpty()
    }

    private fun parsePrimo(doc: JSONObject): Book? {
        val pnx = doc.optJSONObject("pnx") ?: return null
        val record = pnx.optJSONObject("control")?.optJSONArray("recordid").strings().firstOrNull() ?: return null
        val display = pnx.optJSONObject("display") ?: return null
        val title = display.optJSONArray("title").strings().firstOrNull()?.plain()?.takeIf(String::isNotEmpty) ?: return null
        val add = pnx.optJSONObject("addata")
        val image = doc.optJSONObject("delivery")?.optJSONArray("link").objects().firstNotNullOfOrNull { item ->
            val kind = item.text("linkType")
            val url = httpsUrl(item.text("linkURL"))
            url.takeIf { (kind == "thumbnail" || kind.endsWith("/thumbnail")) && it.isNotEmpty() && "jscmd=viewapi" !in it }
        }.orEmpty()
        val types = pnx.optJSONObject("facets")?.optJSONArray("rsrctype").strings()
            .ifEmpty { display.optJSONArray("type").strings() }
        return Book(
            id = "primo:$record",
            title = title,
            author = display.optJSONArray("creator").strings().joinToString(", ") { it.substringBefore("\$\$Q").plain() }
                .ifEmpty { "저자 정보 없음" },
            isbn = add?.optJSONArray("isbn").strings().firstNotNullOfOrNull { isbn ->
                Regex("(?<![0-9])97[89][0-9]{10}(?![0-9])").find(isbn.replace("-", ""))?.value
            }.orEmpty(),
            publisher = add?.optJSONArray("pub").strings().ifEmpty { add?.optJSONArray("publisher").strings() }.joinToString(", "),
            year = Regex("(?<![0-9])(?!0000)[0-9]{4}(?![0-9])")
                .find(add?.optJSONArray("date").strings().firstOrNull().orEmpty())?.value.orEmpty(),
            cover = image.ifEmpty { bundled[record]?.first?.cover.orEmpty() },
            type = types.firstOrNull().orEmpty(),
            location = parseHoldings(doc).map(Holding::name).filter(String::isNotEmpty).distinct().joinToString(" · "),
        )
    }

    private fun parseHoldings(doc: JSONObject): List<Holding> {
        val pnx = doc.optJSONObject("pnx")
        val delivery = doc.optJSONObject("delivery") ?: pnx?.optJSONObject("delivery")
        val rows = delivery?.optJSONArray("holding")?.objects()
            ?: delivery?.optJSONObject("bestlocation")?.let(::listOf)
        val holdings = rows?.map {
            Holding(it.text("organization"), it.text("libraryCode"),
                it.text("subLocationCode").ifEmpty { it.text("subLocation") },
                it.text("subLocationCodeName").ifEmpty { it.text("subLocation") }, it.text("callNumber"),
                it.text("availabilityStatus"), it.text("holdId").ifEmpty { it.text("holdingId") })
        } ?: pnx?.optJSONObject("display")?.optJSONArray("availlibrary").strings().mapNotNull { raw ->
            val fields = raw.split("\$\$").drop(1).filter(String::isNotEmpty).associate { it.first() to it.drop(1).trim() }
            if (fields['9'] == "ONLINE" || fields['2'].isNullOrEmpty()) null else
                Holding(fields['I'].orEmpty(), fields['L'].orEmpty(), fields['Z'].orEmpty(), fields['1'].orEmpty(),
                    fields['2'].orEmpty(), fields['S'].orEmpty(), fields['J'].orEmpty())
        }
        return holdings.distinctBy { listOf(it.institution, it.library, it.code, it.name, it.call, it.id) }
    }

    companion object { const val PAGE_SIZE = 20 }
}

private class CatalogRelay {
    suspend fun primo(query: String, category: Category? = null, offset: Int = 0, timeout: Int = 18_000): JSONObject {
        val params = linkedMapOf(
            "vid" to "82POSTECH_INST:82POSTECH", "tab" to "LibraryCatalog", "scope" to "MyInstitution",
            "q" to query, "lang" to "ko", "offset" to offset.toString(), "limit" to Catalog.PAGE_SIZE.toString(),
            "skipDelivery" to "false",
        )
        if (category != null) {
            params["multiFacets"] = category.types.joinToString("|,|") { "facet_rtype,include,$it" }
        }
        return get("primo/v1/search", params, timeout)
    }

    suspend fun get(path: String, params: Map<String, String>, timeout: Int): JSONObject {
        if (BuildConfig.RELAY_TOKEN.isEmpty()) throw IOException("Catalog relay is unavailable")
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        for (attempt in 0..1) {
            try {
                return withContext(Dispatchers.IO) {
                    val connection = URI("${BuildConfig.RELAY_URL}/$path?$query").toURL().openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = timeout
                        connection.readTimeout = timeout
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Accept", "application/json")
                        connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.RELAY_TOKEN}")
                        val status = connection.responseCode
                        if (status !in 200..299) {
                            throw RelayHttpError(status, connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 0)
                        }
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (error: RelayHttpError) {
                if (attempt == 1 || error.retryAfter > 5 || (error.status != 429 && error.status !in 500..599)) throw error
                delay(maxOf(500L, error.retryAfter * 1000))
            } catch (error: SocketTimeoutException) {
                if (attempt == 1) throw error
            }
        }
        throw IOException("Catalog relay is unavailable")
    }
    private class RelayHttpError(val status: Int, val retryAfter: Long) : IOException("Catalog request failed")
}
