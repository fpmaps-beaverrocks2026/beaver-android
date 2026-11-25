package com.youngmin.pobook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class Profile(
    val completed: Boolean = false,
    val affiliation: Affiliation? = null,
    val interests: List<String> = emptyList(),
)

internal data class SavedBook(val book: Book, val timestamp: Long, val spine: String = "")

internal class LibraryState(context: Context) {
    private val preferences = context.getSharedPreferences("pobook.library", Context.MODE_PRIVATE)

    var profile: Profile
        get() {
            val parent = preferences.getString("parent", "").orEmpty()
            val affiliation = if (parent.isEmpty()) null else Affiliation(parent,
                preferences.getString("group", "").orEmpty(), preferences.getString("department", "").orEmpty(), emptyList())
            return Profile(preferences.getBoolean("onboarding", false), affiliation, strings("interests"))
        }
        set(value) {
            require(value.interests.distinct().size == value.interests.size && value.interests.size <= 3)
            preferences.edit().putBoolean("onboarding", value.completed)
                .putString("parent", value.affiliation?.parent).putString("group", value.affiliation?.group)
                .putString("department", value.affiliation?.name).putString("interests", JSONArray(value.interests).toString()).apply()
        }

    val favorites get() = saved("favorites")
    val cart get() = saved("cart")
    val recentlyViewed get() = saved("viewed")
    val recentlyFound get() = saved("found")
    val searches get() = strings("queries")

    fun rememberQuery(query: String) {
        val updated = (listOf(query) + searches.filterNot { it.equals(query, ignoreCase = true) }).take(20)
        preferences.edit().putString("queries", JSONArray(updated).toString()).apply()
    }

    fun removeQuery(query: String) {
        preferences.edit().putString("queries", JSONArray(searches.filterNot { it == query }).toString()).apply()
    }

    fun toggleFavorite(book: Book) {
        if (favorites.any { it.book.id == book.id }) remove("favorites", book) else remember("favorites", book, 500)
    }

    fun addToCart(book: Book): Boolean {
        val current = cart
        if (current.any { it.book.id == book.id }) return true
        if (current.size == MAX_CART) return false
        write("cart", listOf(SavedBook(book, System.currentTimeMillis())) + current)
        return true
    }

    fun removeFromCart(book: Book) = remove("cart", book)
    fun view(book: Book) = remember("viewed", book, 20)
    fun found(book: Book, spine: String) = remember("found", book, 100, spine)
    fun removeFound(book: Book) = remove("found", book)

    private fun remember(key: String, book: Book, limit: Int, spine: String = "") {
        val current = saved(key).filterNot { it.book.id == book.id }
        write(key, (listOf(SavedBook(book, System.currentTimeMillis(), spine)) + current).take(limit))
    }

    private fun remove(key: String, book: Book) = write(key, saved(key).filterNot { it.book.id == book.id })
    private fun strings(key: String): List<String> = array(key).strings()
    private fun array(key: String): JSONArray = runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrDefault(JSONArray())

    private fun saved(key: String): List<SavedBook> = array(key).objects().mapNotNull {
        val book = it.optJSONObject("book")?.let(Book::fromJson) ?: return@mapNotNull null
        SavedBook(book, it.optLong("timestamp"), it.text("spine"))
    }

    private fun write(key: String, values: List<SavedBook>) {
        val array = JSONArray(values.map { JSONObject().put("book", it.book.toJson()).put("timestamp", it.timestamp).put("spine", it.spine) })
        preferences.edit().putString(key, array.toString()).apply()
    }

    companion object {
        const val MAX_CART = 30
        const val MAX_SELECTION = 3
    }
}
