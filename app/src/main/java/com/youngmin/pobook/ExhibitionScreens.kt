package com.youngmin.pobook

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private data class GameListing(val booth: ExhibitionBooth, val game: ExhibitionGame) {
    val searchText = "${game.title} ${game.englishTitle} ${game.team} ${booth.code}".searchKey()
}

internal fun MainActivity.exhibitionGamesContent(): View = column().apply {
    val field = EditText(this@exhibitionGamesContent).apply {
        hint = "게임, 팀 또는 부스 번호 검색"
        setTextColor(Pobook.text)
        setHintTextColor(Pobook.muted)
        textSize = 16f
        typeface = Pobook.font(context, false)
        isSingleLine = true
        pad(16, 12)
        background = shape(Pobook.panel, 23)
    }
    addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply {
        marginStart = dp(16)
        marginEnd = dp(16)
        topMargin = dp(10)
        bottomMargin = dp(10)
    })
    val resultCount = label("", 14, Pobook.muted).apply { pad(16, 6) }
    addView(resultCount)
    val list = ListView(this@exhibitionGamesContent).apply {
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = dp(16)
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        pad(16, 4, 16)
    }
    fill(list)

    val adapter = object : BaseAdapter() {
        var results = emptyList<GameListing>()
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = results[position].game.id.toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val cell = recycled as? LinearLayout ?: row().apply {
                pad(12, 13)
                minimumHeight = dp(64)
                fill(column().apply {
                    addView(label("", 16, Pobook.text, true).apply { maxLines = 2 })
                    gap(4)
                    addView(label("", 13, Pobook.muted).apply { maxLines = 1 })
                })
                gap(10)
                addView(label("", 13, Pobook.accent, true))
                background = shape(Pobook.panel, 10)
                layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
            }
            val title = cell.getChildAt(0) as LinearLayout
            (title.getChildAt(0) as TextView).text = item.game.title
            (title.getChildAt(2) as TextView).text = item.game.team
            (cell.getChildAt(2) as TextView).text = item.booth.code
            return cell
        }
    }
    list.adapter = adapter
    list.setOnItemClickListener { _, _, index, _ ->
        val item = adapter.getItem(index)
        push(Screen.GameBooth(item.booth, item.game.id))
    }

    scope.launch {
        val entries = exhibitionGamesData.await().booths
            .flatMap { booth -> booth.games.map { game -> GameListing(booth, game) } }
            .sortedBy { it.game.title.searchKey() }
        fun filter(text: CharSequence?) {
            val query = text.toString().searchKey()
            adapter.results = entries.filter { query in it.searchText }
            resultCount.text = if (adapter.results.isEmpty()) "일치하는 게임이 없어요" else "게임 ${adapter.results.size}개"
            adapter.notifyDataSetChanged()
        }
        filter(field.text)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter(s)
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
}

internal fun MainActivity.gameBoothScreen(screen: Screen.GameBooth): View = column().apply {
    val booth = screen.booth
    var active = booth.games.firstOrNull { it.id == screen.selectedGameId } ?: booth.games.first()
    addView(topBar("${booth.code} 게임 부스"))
    val details = body()
    val scroller = scroll(details)
    fill(scroller)
    val footer = column().apply {
        pad(16, 8, 12)
        addView(button("지도에서 부스 보기") { revealExhibitionBooth(booth.areaId) })
    }
    addView(footer)
    fun paint() {
        details.removeAllViews()
        if (booth.games.size > 1) {
            val tabs = row().apply {
                booth.games.forEachIndexed { index, game ->
                    if (index > 0) gap(8)
                    fill(chip(game.title, active.id == game.id) {
                        active = game
                        paint()
                    }.apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            }
            details.addView(tabs)
            details.gap(20)
        }
        details.addView(label(active.title, 26, Pobook.text, true).apply { isAccessibilityHeading = true })
        if (active.englishTitle.isNotEmpty() && active.englishTitle != active.title) {
            details.gap(4)
            details.addView(label(active.englishTitle, 16, Pobook.muted))
        }
        val metadata = listOf(active.team, active.genre, active.platform).filter(String::isNotBlank).joinToString(" · ")
        if (metadata.isNotEmpty()) {
            details.gap(7)
            details.addView(label(metadata, 14, Pobook.secondary))
        }
        details.gap(18)
        val hero = ImageView(this@gameBoothScreen).apply {
            background = shape(Pobook.panel, 18)
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        fun displayImage(index: Int) {
            hero.contentDescription = "${active.title} 이미지 ${index + 1}/${active.images.size}"
            loadGameImage(hero, active.images[index], 1200)
        }
        details.addView(hero, LinearLayout.LayoutParams(MATCH, dp(228)))
        displayImage(0)
        if (active.images.size > 1) {
            details.gap(10)
            val game = active
            val gallery = HorizontalScrollView(this@gameBoothScreen).apply {
                isHorizontalScrollBarEnabled = false
                addView(row().apply {
                    game.images.forEachIndexed { index, path ->
                        if (index > 0) gap(8)
                        addView(ImageView(this@gameBoothScreen).apply {
                            contentDescription = "${game.title} 이미지 ${index + 1} 보기"
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = shape(Pobook.panel, 8)
                            clipToOutline = true
                            isFocusable = true
                            setOnClickListener { displayImage(index) }
                            loadGameImage(this, path, 240)
                        }, LinearLayout.LayoutParams(dp(92), dp(60)))
                    }
                })
            }
            details.addView(gallery)
        }
        if (active.summary.isNotEmpty()) {
            details.gap(22)
            details.addView(label(active.summary, 18, Pobook.text, true))
        }
        if (active.description.isNotEmpty() && active.description != active.summary) {
            details.gap(10)
            details.addView(label(active.description, 16, Pobook.secondary))
        }
    }
    paint()
}

private fun MainActivity.loadGameImage(image: ImageView, path: String, maximum: Int) {
    image.tag = path
    scope.launch {
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val fullPath = "exhibition-games/$path"
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open(fullPath).use { BitmapFactory.decodeStream(it, null, options) }
                val longest = maxOf(options.outWidth, options.outHeight)
                var sample = 1
                while (longest / (sample * 2) >= maximum) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                assets.open(fullPath).use { BitmapFactory.decodeStream(it, null, options) }
            } catch (_: IOException) {
                null
            }
        }
        if (image.tag == path && bitmap != null) image.setImageBitmap(bitmap)
    }
}
