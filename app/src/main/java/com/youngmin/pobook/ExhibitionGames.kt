package com.youngmin.pobook

import android.content.Context
import org.json.JSONObject

internal data class ExhibitionGame(
    val id: Int,
    val title: String,
    val englishTitle: String,
    val team: String,
    val genre: String,
    val platform: String,
    val summary: String,
    val description: String,
    val images: List<String>,
)

internal data class ExhibitionBooth(val areaId: String, val number: Int, val games: List<ExhibitionGame>) {
    val code get() = "G${number.toString().padStart(2, '0')}"
}

internal class ExhibitionGames(context: Context) {
    private val data = JSONObject(context.asset("exhibition-games/catalog.json"))
    private val games = data.getJSONArray("games").objects().associate { row ->
        val id = row.getInt("id")
        id to ExhibitionGame(
            id = id,
            title = row.getString("title"),
            englishTitle = row.text("englishTitle"),
            team = row.text("team"),
            genre = row.text("genre"),
            platform = row.text("platform"),
            summary = row.text("summary"),
            description = row.text("description"),
            images = row.getJSONArray("images").strings(),
        )
    }

    val booths = data.getJSONArray("booths").objects().map { row ->
        val ids = row.getJSONArray("gameIds")
        ExhibitionBooth(row.getString("areaId"), row.getInt("number"),
            List(ids.length()) { games.getValue(ids.getInt(it)) })
    }

    val byAreaId = booths.associateBy(ExhibitionBooth::areaId)
}
