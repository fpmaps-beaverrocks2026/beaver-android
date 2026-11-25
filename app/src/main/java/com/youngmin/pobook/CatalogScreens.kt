package com.youngmin.pobook

import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Color
import android.widget.LinearLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class SearchState {
    var draft = ""
    var query = ""
    var category = Category.ALL
    var result: CatalogPage? = null
    var offset = 0
}

internal fun MainActivity.mapHome(): View = FrameLayout(this).apply {
    val frame = FrameLayout(this@mapHome)
    frame.addView(loading("SETEC 전시장 지도를 불러오고 있어요"), FrameLayout.LayoutParams(MATCH, MATCH))
    addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
    addView(View(this@mapHome).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xA5262E37.toInt(), 0x80212222.toInt(), 0x00121212),
        )
    }, FrameLayout.LayoutParams(MATCH, dp(286), Gravity.TOP))
    addView(label("SETEC\n전시장 안내", 24, bold = true).apply {
        isAccessibilityHeading = true
        pad(16, 0)
    }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(83) })
    scope.launch {
        val exhibition = exhibitionData.await()
        val games = exhibitionGamesData.await()
        val controls = mapWithControls(exhibition,
            homeFloor.takeIf(exhibition.floors::contains) ?: exhibition.dataset.initialFloor, home = true) { map ->
            val updateFloor = map.onFloorChanged
            map.onFloorChanged = { floor ->
                homeFloor = floor
                updateFloor?.invoke(floor)
            }
            map.exhibitionBooths = games.byAreaId
            val focused = exhibition.areas.values.asSequence().flatten().firstOrNull { it.id == focusedExhibitionBooth }
            if (focused != null && focused.center != null) {
                map.highlightedBooth = focused.id
                map.show(focused.center, focused.floor, 4.2f)
            }
            map.onAreaTapped = { area ->
                val booth = games.byAreaId[area.id]
                if (booth != null) {
                    focusedExhibitionBooth = area.id
                    push(Screen.GameBooth(booth))
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(this@mapHome)
                        .setTitle(area.name)
                        .setMessage("SETEC · ${area.floor}층")
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
        }
        frame.removeAllViews()
        frame.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

internal fun MainActivity.setupScreen(setup: SetupState): View = column().apply {
    val stage = setup.step
    if (stage != SetupStep.WELCOME) addView(topBar(""))
    val content = column().apply { pad(if (stage == SetupStep.WELCOME) 20 else 16, if (stage == SetupStep.WELCOME) 100 else
        if (stage == SetupStep.AFFILIATION) 46 else 30, 10) }
    val footer = column().apply { pad(36, 9, if (stage == SetupStep.WELCOME) 24 else 10) }
    val continueButton: () -> Unit = {
        when (stage) {
            SetupStep.WELCOME -> push(Screen.Setup(SetupState(setup.mode, SetupStep.AFFILIATION, setup.affiliation, setup.interests)))
            SetupStep.AFFILIATION -> {
                if (setup.mode == SetupMode.AFFILIATION) completeSetup(setup)
                else push(Screen.Setup(SetupState(setup.mode, SetupStep.INTERESTS, setup.affiliation, setup.interests)))
            }
            SetupStep.INTERESTS -> completeSetup(setup)
        }
    }
    fun updateFooter() {
        footer.removeAllViews()
        if (stage != SetupStep.WELCOME && setup.mode == SetupMode.FIRST_RUN) {
            footer.addView(row().apply {
                gravity = Gravity.CENTER
                repeat(2) { index ->
                    val selected = index == if (stage == SetupStep.AFFILIATION) 0 else 1
                    if (index > 0) gap(9)
                    addView(View(this@setupScreen).apply {
                        background = shape(if (selected) Pobook.text else 0x4DF9F9F9, 4)
                    }, LinearLayout.LayoutParams(dp(7), dp(7)))
                }
            }, LinearLayout.LayoutParams(MATCH, dp(14)))
            footer.gap(25)
        }
        val enabled = when (stage) {
            SetupStep.WELCOME -> true
            SetupStep.AFFILIATION -> setup.affiliation != null
            SetupStep.INTERESTS -> setup.interests.isNotEmpty()
        }
        val title = when {
            stage == SetupStep.WELCOME -> "시작하기"
            stage == SetupStep.AFFILIATION && setup.mode != SetupMode.AFFILIATION -> "다음"
            setup.mode == SetupMode.FIRST_RUN -> if (enabled) "시작하기" else "선택을 완료해주세요"
            else -> "저장하기"
        }
        footer.addView(button(title, enabled = enabled, action = continueButton))
        if (stage == SetupStep.AFFILIATION && setup.mode == SetupMode.FIRST_RUN) {
            footer.addView(link("다음에 할래요") {
                showDialog("맞춤 정보 설정을 건너뛸까요?", "마이페이지에서 언제든지 다시 설정할 수 있어요.", "건너뛰기") {
                    completeSetup(setup, skip = true)
                }
            }.apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG })
        }
    }
    when (stage) {
        SetupStep.WELCOME -> {
            content.addView(label("읽고 싶은 책까지,\nPo:Book이 이어줄게요 📚📍", 24, bold = true))
            content.gap(12)
            content.addView(label("내 취향에 맞는 책을 발견하고\n도서관에서 쉽게 찾아보세요.", 18, Pobook.muted))
            content.addView(View(this@setupScreen), LinearLayout.LayoutParams(1, dp(30), 1f))
            content.addView(ImageView(this@setupScreen).apply {
                setImageResource(R.drawable.pobook_logo)
                contentDescription = "Po:book"
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(MATCH, dp(158)))
            content.addView(View(this@setupScreen), LinearLayout.LayoutParams(1, dp(30), 1f))
        }
        SetupStep.AFFILIATION -> {
            content.addView(label("입력한 정보를 바탕으로\n내게 더 잘 맞는 책을 보여드릴게요.", 24, bold = true))
            content.gap(10)
            content.addView(label("소속과 학과를 알려주세요", 17, Pobook.muted))
            content.gap(29)
            val fields = column()
            content.addView(fields)
            scope.launch {
                val entries = catalogData.await().affiliations
                val parents = entries.map(Affiliation::parent).distinct()
                var parent = setup.affiliation?.parent
                fun field(label: String, value: String, enabled: Boolean, open: () -> Unit) {
                    fields.addView(label(label, 16, if (enabled) Pobook.text else 0xFF777777.toInt()))
                    fields.gap(8)
                    val color = if (enabled) Pobook.text else 0xFF999999.toInt()
                    fields.addView(row().apply {
                        setPadding(dp(20), 0, dp(23), 0)
                        fill(label(value, 16, color))
                        addView(LineIcon(this@setupScreen, "next", color, 15), LinearLayout.LayoutParams(dp(22), dp(22)))
                        if (enabled) touch(Pobook.panel, 20, action = open)
                        else background = shape(0xFF161616.toInt(), 20)
                        isEnabled = enabled
                    }, LinearLayout.LayoutParams(MATCH, dp(52)))
                    fields.gap(20)
                }
                fun rebuild() {
                    fields.removeAllViews()
                    field("소속", parent ?: "소속을 선택해주세요", true) {
                        choose("소속", parents) { index ->
                            parent = parents[index]
                            val options = entries.filter { it.parent == parent }
                            setup.affiliation = options.singleOrNull()
                            rebuild()
                        }
                    }
                    field("학과", setup.affiliation?.name?.ifEmpty { "별도 선택 없음" } ?: "학과를 선택해주세요", parent != null) {
                        val options = entries.filter { it.parent == parent }
                        choose("학과", options.map { it.label.ifEmpty { it.parent } }) {
                            setup.affiliation = options[it]
                            rebuild()
                        }
                    }
                    fields.addView(label("*소속과 학과는 나중에 변경 가능해요.", 13, Pobook.muted))
                    updateFooter()
                }
                rebuild()
            }
        }
        SetupStep.INTERESTS -> {
            content.addView(label("어떤 책에 끌리나요?", 24, bold = true))
            content.gap(10)
            content.addView(label("관심 있는 분야를 골라주세요.", 20, Pobook.muted))
            content.gap(32)
            val pills = column()
            content.addView(pills)
            scope.launch {
                val interests = catalogData.await().interests
                fun refresh() {
                    if (pills.width <= 0) return
                    pills.removeAllViews()
                    var line = row()
                    var used = 0
                    pills.addView(line)
                    for (option in interests) {
                        val selected = option.name in setup.interests
                        val tag = label(option.name, 16, if (selected) Pobook.bg else Pobook.text).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(25), 0, dp(25), 0)
                            isSelected = selected
                            touch(if (selected) Pobook.accent else Pobook.panel, 26) {
                                if (!setup.interests.remove(option.name)) {
                                    if (setup.interests.size == 3) toast("관심 분야는 최대 3개까지 선택할 수 있어요")
                                    else setup.interests.add(option.name)
                                }
                                refresh()
                                updateFooter()
                            }
                        }
                        tag.measure(View.MeasureSpec.makeMeasureSpec(pills.width, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(dp(52), View.MeasureSpec.EXACTLY))
                        val width = tag.measuredWidth
                        if (used > 0 && used + dp(8) + width > pills.width) {
                            pills.gap(12)
                            line = row()
                            pills.addView(line)
                            used = 0
                        }
                        if (used > 0) { line.gap(8); used += dp(8) }
                        line.addView(tag, LinearLayout.LayoutParams(width, dp(52)))
                        used += width
                    }
                }
                pills.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (right - left != oldRight - oldLeft) refresh()
                }
                refresh()
            }
        }
    }
    fill(scroll(content))
    addView(footer)
    updateFooter()
}

private fun MainActivity.shelfPlate(): View = FrameLayout(this).apply {
    addView(View(this@shelfPlate).apply { background = shape(0x80C5E300.toInt(), 10) }, FrameLayout.LayoutParams(MATCH, MATCH))
    addView(object : View(this@shelfPlate) {
        val art = requireNotNull(androidx.core.content.res.ResourcesCompat.getDrawable(resources,
            R.drawable.recommendation_glass, context.theme))
        val center = android.graphics.Path()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            art.setBounds(0, 0, w, h)
            center.rewind()
            center.addRoundRect(dp(3).toFloat(), dp(3).toFloat(), (w - dp(3)).toFloat(),
                (h - dp(3)).toFloat(), dp(8).toFloat(), dp(8).toFloat(), android.graphics.Path.Direction.CW)
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val layer = canvas.save()
            canvas.clipOutPath(center)
            art.draw(canvas)
            canvas.restoreToCount(layer)
        }
    }, FrameLayout.LayoutParams(MATCH, MATCH))
    addView(ImageView(this@shelfPlate).apply { setImageResource(R.drawable.recommendation_bolt) },
        FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(15) })
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
}

private fun MainActivity.catalogBookRow(book: Book, holding: Holding?, selected: Boolean? = null,
                                         action: () -> Unit, details: (() -> Unit)? = null): View = row().apply {
    pad(16, 16)
    if (selected != null) {
        val mark = LineIcon(this@catalogBookRow, if (selected) "check" else "", Pobook.bg, 15).apply {
            background = shape(if (selected) Pobook.accent else Color.TRANSPARENT, 12,
                if (selected) null else Pobook.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(mark, LinearLayout.LayoutParams(dp(24), dp(24)))
        gap(16)
    }
    addView(bookCover(book).apply {
        scaleType = ImageView.ScaleType.FIT_START
        if (details != null) {
            contentDescription = "${book.title} 상세"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { details() }
        }
    }, LinearLayout.LayoutParams(dp(78), dp(111)))
    gap(20)
    val info = FrameLayout(this@catalogBookRow)
    val bibliography = column().apply {
        addView(label(book.title, 18, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(label(holding?.name?.ifEmpty { null } ?: book.location.ifEmpty { "위치 정보 없음" }, 16, Color.WHITE).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(label(book.author, 12, 0xA6FFFFFF.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }
    info.addView(bibliography, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
    val availability = holding?.availability
    info.addView(label(when (availability) {
        "available" -> "대출 가능"
        "unavailable" -> "이용 불가"
        else -> "대출 상태 확인 필요"
    }, 12, when (availability) {
        "available" -> Pobook.success
        "unavailable" -> 0xFFFF383C.toInt()
        else -> 0xA6FFFFFF.toInt()
    }), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    val rowHeight = (111 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
    addView(info, LinearLayout.LayoutParams(0, dp(rowHeight), 1f))
    isSelected = selected == true
    touch(radius = 0, action = action)
}

private fun MainActivity.catalogCard(books: List<Book>, onSelect: (Book) -> Unit): View = column().apply {
    background = shape(Pobook.card, 20)
    clipToOutline = true
    scope.launch {
        val catalog = catalogData.await()
        books.forEachIndexed { index, book ->
            if (index > 0) addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                marginStart = dp(16); marginEnd = dp(16)
            })
            addView(catalogBookRow(book, catalog.knownHoldings(book).firstOrNull(), action = { onSelect(book) }))
        }
    }
}

internal fun MainActivity.searchHome(): View = column().apply {
    val content = column().apply { pad(0, 9, 18) }
    val scroller = scroll(content)
    val input = EditText(this@searchHome).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        filters = arrayOf(InputFilter.LengthFilter(200))
        textSize = 16f
        typeface = Pobook.font(context, false)
        setTextColor(Pobook.text)
        setHintTextColor(Pobook.muted)
        background = null
        hint = "책 제목이나 저자를 검색하세요"
        setText(search.draft)
        setSelection(length())
    }
    val filters = row().apply { pad(16, 9, 3) }
    var request: Job? = null

    fun paintContent() {
        content.removeAllViews()
        val result = search.result
        fun message(title: String, body: String) {
            val region = column().apply {
                gravity = Gravity.CENTER
                pad(24, 0, 78)
                addView(label(title, 22, Color.WHITE, true).apply { gravity = Gravity.CENTER })
                gap(8)
                addView(label(body, 16, Pobook.muted).apply { gravity = Gravity.CENTER })
            }
            content.fill(region)
        }
        if (search.query.isEmpty()) {
            val viewed = state.recentlyViewed.map(SavedBook::book)
            if (viewed.isEmpty()) {
                message("최근 항목이 없어요", "검색 결과에서 선택한 책이 여기에 표시돼요.")
            } else {
                content.addView(label("최근 항목", 20, Color.WHITE, bold = true).apply { pad(16, 8, 8) })
                content.addView(catalogCard(viewed, ::openDetail), LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    marginStart = dp(16); marginEnd = dp(16)
                })
            }
        } else if (result == null) {
            content.fill(loading("책을 찾고 있어요"))
        } else {
            if (result.bundled) {
                content.addView(label("추천 도서 자료에서 검색했어요. 실시간 검색은 연결을 확인해 주세요.", 13, Pobook.muted)
                    .apply { pad(16, 9, 8) })
            }
            if (result.books.isEmpty()) {
                message("검색 결과 없음", if (search.category == Category.ALL)
                    "결과가 없어요. 새로운 검색을 시도해보세요."
                    else "불러온 결과에 해당 자료 유형이 없어요.\n다른 유형을 선택하거나 새로 검색해보세요.")
            } else {
                content.addView(label("검색 결과 ${result.books.size}건", 24, Color.WHITE, true).apply {
                    isAccessibilityHeading = true
                    pad(16, 6, 12)
                })
                content.addView(catalogCard(result.books, ::openDetail), LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    marginStart = dp(16); marginEnd = dp(16)
                })
            }
        }
    }

    lateinit var submit: (Boolean) -> Unit
    filters.background = shape(0xFF272727.toInt(), 9)
    filters.pad(2, 2)
    fun paintFilters() {
        filters.removeAllViews()
        for ((index, option) in Category.entries.withIndex()) {
            val selected = search.category == option
            if (index > 0 && !selected && search.category != Category.entries[index - 1]) {
                filters.addView(View(this@searchHome).apply { setBackgroundColor(0x35FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(15)))
            }
            filters.addView(label(option.label, 13, Color.WHITE, selected).apply {
                gravity = Gravity.CENTER
                isSelected = selected
                touch(if (selected) 0xFF565656.toInt() else Color.TRANSPARENT, 7) {
                    if (search.category != option) {
                        search.category = option
                        paintFilters()
                        if (search.query.isNotEmpty()) submit(false)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(28), 1f))
        }
    }

    fun loadNext(offset: Int) {
        val query = search.query
        val category = search.category
        request?.cancel()
        request = scope.launch {
            val page = catalogData.await().search(query, category, offset)
            if (query != search.query || category != search.category) return@launch
            val previous = if (offset == 0) emptyList() else search.result?.books.orEmpty()
            if (offset > 0 && page.bundled) {
                search.result = CatalogPage(previous, false)
                toast("다음 검색 결과를 불러오지 못했어요")
            } else {
                search.result = page.copy(books = (previous + page.books).distinctBy(Book::id))
            }
            search.offset = offset
            paintContent()
            if (offset == 0) scroller.scrollTo(0, 0)
        }
    }

    submit = { updateQuery ->
        if (updateQuery) search.query = input.text.toString().trim()
        hideKeyboard()
        if (search.query.isEmpty()) {
            request?.cancel()
            search.result = null
            paintContent()
        } else {
            state.rememberQuery(search.query)
            search.result = null
            paintContent()
            loadNext(0)
        }
    }
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { search.draft = s.toString() }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    })
    input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { submit(true); true } else false }
    addView(row().apply {
        pad(16, 7, 2)
        addView(row().apply {
            pad(6, 0)
            background = shape(Pobook.panel, 26, Pobook.border)
            addView(icon("search", "검색 실행", Pobook.muted, android.graphics.Color.TRANSPARENT) { submit(true) })
            fill(input)
            addView(icon("close", "검색어 지우기", Pobook.muted, android.graphics.Color.TRANSPARENT) {
                input.setText("")
                submit(true)
            })
        }, LinearLayout.LayoutParams(MATCH, dp(52)))
    })
    addView(filters, LinearLayout.LayoutParams(MATCH, dp(32)).apply {
        marginStart = dp(16); marginEnd = dp(16); topMargin = dp(9); bottomMargin = dp(16)
    })
    fill(scroller)
    paintFilters()
    paintContent()
    scroller.setOnScrollChangeListener { _, _, y, _, oldY ->
        if (y > oldY && request?.isActive != true && search.result?.hasMore == true &&
            scroller.height + y >= content.height - dp(190)) loadNext(search.offset + Catalog.PAGE_SIZE)
    }
    if (search.query.isNotEmpty() && search.result == null) loadNext(0)
}

internal fun MainActivity.profileScreen(): View = column().apply {
    addView(topBar("마이페이지"))
    val content = column().apply { pad(0, 28, 80) }
    val found = state.recentlyFound
    content.addView(row().apply {
        pad(16, 0)
        fill(label("최근 찾은 책 목록", 20, bold = true))
        addView(icon("plus", "최근 찾은 책 목록 전체 보기", background = Color.TRANSPARENT) {
            push(Screen.Saved(ListKind.FOUND))
        })
    })
    content.gap(28)
    val shelf = FrameLayout(this@profileScreen)
    val books = row().apply {
        gravity = Gravity.BOTTOM
        pad(53, 0)
        val count = if (found.isEmpty()) 5 else found.size.coerceAtMost(7)
        repeat(count) { index ->
            val saved = found.getOrNull(index)
            val height = if (index % 3 == 0) 171 else 212
            addView(ImageView(this@profileScreen).apply {
                setImageResource(R.drawable.map_shelf)
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (saved != null) covers.load(this, saved.spine, scope)
                if (saved == null) alpha = 0.65f
            }, LinearLayout.LayoutParams(dp(31), dp(height)))
        }
    }
    shelf.addView(books, FrameLayout.LayoutParams(MATCH, MATCH))
    shelf.addView(shelfPlate(), FrameLayout.LayoutParams(MATCH, dp(54), Gravity.BOTTOM).apply { marginStart = dp(16) })
    shelf.contentDescription = "최근 찾은 책 목록 전체 보기"
    shelf.setOnClickListener { push(Screen.Saved(ListKind.FOUND)) }
    content.addView(shelf, LinearLayout.LayoutParams(MATCH, dp(214)))
    content.gap(20)
    content.addView(column().apply {
        pad(16, 0)
        addView(label("설정 및 안내", 20, bold = true))
        gap(20)
        val settings = listOf<Pair<String, () -> Unit>>(
            "소속 및 학과 정보 변경" to { beginSetup(SetupMode.AFFILIATION) },
            "관심 분야 변경하기" to { beginSetup(SetupMode.INTERESTS) },
            "위치 권한 설정" to ::openLocationSettings,
            "개인정보 처리방침" to { openWeb(MainActivity.PRIVACY_POLICY) },
        )
        settings.forEachIndexed { index, (title, action) ->
            if (index > 0) addView(divider(0x35AAAAAA))
            addView(row().apply {
                minimumHeight = dp(52)
                fill(label(title, 18))
                addView(LineIcon(this@profileScreen, "next", Pobook.text, 15),
                    LinearLayout.LayoutParams(dp(23), dp(23)))
                touch(action = action)
            })
        }
    })
    fill(scroll(content))
}

internal fun MainActivity.savedScreen(kind: ListKind): View = column().apply {
    val entries = when (kind) {
        ListKind.CART -> state.cart
        ListKind.FAVORITES -> state.favorites
        ListKind.FOUND -> state.recentlyFound
    }
    val selected = if (kind == ListKind.CART) cartSelection else favoriteSelection
    selected.retainAll(entries.map { it.book.id }.toSet())
    val content = body().apply { pad(16, 24, 24) }
    val sticky = row().apply { pad(28, 12) }
    addView(topBar(kind.title))
    val shell = FrameLayout(this@savedScreen)
    shell.addView(scroll(content), FrameLayout.LayoutParams(MATCH, MATCH))
    if (entries.isEmpty() && kind != ListKind.FAVORITES) {
        shell.addView(empty(
            if (kind == ListKind.CART) "책바구니가 비어 있어요" else "아직 찾은 책이 없어요",
            if (kind == ListKind.CART) "찾고 싶은 책을 책바구니에 저장해두면\n빠르게 책의 위치를 찾을 수 있어요."
            else "길안내를 따라 찾은 책이 여기에 모여요.",
            if (kind == ListKind.CART) "basket" else "book",
        ), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    }
    fill(shell)
    if (kind != ListKind.FOUND) addView(sticky)
    val catalogJob = catalogData
    scope.launch {
        val catalog = catalogJob.await()
        fun paint() {
            sticky.removeAllViews()
            content.removeAllViews()
            content.addView(label("총 ${entries.size}권", 16, Pobook.secondary).apply { gravity = Gravity.END })
            content.gap(4)
            val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            for ((day, group) in entries.groupBy { date.format(java.util.Date(it.timestamp)) }) {
                content.addView(label(day, 20, Pobook.secondary, true))
                content.gap(12)
                content.addView(column().apply {
                    background = shape(Pobook.card, 20)
                    clipToOutline = true
                    group.forEachIndexed { index, saved ->
                        if (index > 0) addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                            marginStart = dp(16); marginEnd = dp(16)
                        })
                        val book = saved.book
                        val holding = catalog.knownHoldings(book).firstOrNull()
                        addView(catalogBookRow(book, holding, if (kind == ListKind.FOUND) null else book.id in selected,
                            action = {
                                if (kind == ListKind.FOUND) openDetail(book)
                                else if (holding?.availability == "unavailable" && kind == ListKind.CART && book.id !in selected) {
                                    showDialog("대출 중인 책은 안내할 수 없어요", "대출 가능한 책을 선택해 주세요.", "확인") {}
                                } else {
                                    if (!selected.remove(book.id)) {
                                        if (kind == ListKind.CART && selected.size == LibraryState.MAX_SELECTION) {
                                            showDialog("아이고 무거워! 책이 너무 많아서 안내를 시작할 수 없어요.",
                                                "다른 책을 선택하려면 추가한 책을 해제하고 다시 시도해보세요.", "확인했어요") {}
                                        } else selected.add(book.id)
                                    }
                                    paint()
                                }
                            },
                            details = if (kind == ListKind.FOUND) null else ({ openDetail(book) })))
                    }
                })
                content.gap(if (kind == ListKind.CART) 28 else 24)
            }
            if (kind == ListKind.FOUND) return
            val chosen = entries.filter { it.book.id in selected }
            sticky.addView(glassSymbol("trash", "선택한 책 삭제", 48) {
                if (chosen.isNotEmpty()) showDialog("선택한 책 ${chosen.size}권을 삭제할까요?",
                    if (kind == ListKind.CART) "이 책은 책바구니에서만 삭제돼요." else "선택한 책을 삭제해요.", "삭제") {
                    chosen.forEach {
                        if (kind == ListKind.CART) state.removeFromCart(it.book)
                        else state.toggleFavorite(it.book)
                    }
                    selected.clear()
                    render()
                }
            }.apply { isEnabled = chosen.isNotEmpty(); alpha = if (isEnabled) 1f else 0.48f })
            sticky.addView(View(this@savedScreen), LinearLayout.LayoutParams(0, 1, 1f))
            val title = if (kind == ListKind.FAVORITES) "책바구니에 담기"
                else if (chosen.isEmpty()) "안내 시작하기" else "안내 시작하기 (${chosen.size}권)"
            sticky.addView(label(title, 16, if (chosen.isEmpty()) 0x90DBDBDB.toInt() else Color.BLACK).apply {
                gravity = Gravity.CENTER
                pad(14, 0)
                minimumHeight = dp(48)
                glassTouch(24, if (chosen.isEmpty()) 0x76657123 else 0xE2DDF53C.toInt()) {
                    if (kind == ListKind.CART) push(Screen.Locate(chosen.map(SavedBook::book)))
                    else {
                        val added = chosen.count { state.addToCart(it.book) }
                        chosen.take(added).forEach { selected.remove(it.book.id) }
                        if (added == 0) toast("책바구니에는 최대 30권까지 담을 수 있어요")
                        else showDialog("선택한 책을 책바구니에 담았어요.", "책바구니로 이동해 길 안내를 시작할까요?",
                            "책바구니 가기") { push(Screen.Saved(ListKind.CART)) }
                        paint()
                    }
                }
                isEnabled = chosen.isNotEmpty()
            }, LinearLayout.LayoutParams(WRAP, dp(48)))
        }
        paint()
    }
}

internal fun MainActivity.detailScreen(book: Book): View = FrameLayout(this).apply {
    val frame = this
    setBackgroundColor(Pobook.bg)
    val backgroundLayer = FrameLayout(this@detailScreen)
    val blurredCover = bookCover(book).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.56f
        if (android.os.Build.VERSION.SDK_INT >= 31) setRenderEffect(
            android.graphics.RenderEffect.createBlurEffect(dp(26).toFloat(), dp(26).toFloat(), android.graphics.Shader.TileMode.CLAMP))
    }
    backgroundLayer.addView(blurredCover, FrameLayout.LayoutParams(MATCH, MATCH))
    backgroundLayer.addView(View(this@detailScreen).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x44121212, 0x80121212.toInt(), 0xFF121212.toInt()),
        )
    }, FrameLayout.LayoutParams(MATCH, MATCH))
    addView(backgroundLayer, FrameLayout.LayoutParams(MATCH, MATCH))

    val toolbar = row().apply {
        pad(16, 8, 0)
        addView(glassSymbol("back", "뒤로", action = ::back))
        addView(View(this@detailScreen), LinearLayout.LayoutParams(0, 1, 1f))
    }
    fun paintFavorite() {
        if (toolbar.childCount > 2) toolbar.removeViewAt(2)
        val liked = state.favorites.any { it.book.id == book.id }
        toolbar.addView(glassSymbol(if (liked) "bookmark" else "bookmark_line", if (liked) "찜 해제" else "찜하기") {
            state.toggleFavorite(book)
            paintFavorite()
        })
    }
    paintFavorite()
    addView(toolbar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

    val compactHeight = 283
    var expanded = false
    var items = emptyList<Holding>()
    var shelves = emptyList<ShelfMatch>()
    var extra: BookDescription? = null
    var relatedBooks = emptyList<Book>()
    var loaded = false
    var bundled = false
    val floorBadge = label("", 14, Pobook.text, true).apply {
        gravity = Gravity.CENTER
        background = glass(12)
        visibility = View.GONE
    }
    val sheet = column().apply {
        background = glass(29, 0xED242424.toInt())
        clipToOutline = true
        pad(0, 0, 10)
    }
    val panelLayout = FrameLayout.LayoutParams(MATCH, dp(compactHeight), Gravity.BOTTOM)
    val handleArea = FrameLayout(this@detailScreen).apply {
        addView(View(this@detailScreen).apply { background = shape(0x90DBDBDB.toInt(), 3) },
            FrameLayout.LayoutParams(dp(36), dp(5), Gravity.CENTER))
    }
    val info = column()
    val coverImage = bookCover(book).apply { scaleType = ImageView.ScaleType.FIT_START }
    val header = row().apply {
        gravity = Gravity.TOP
        pad(16, 4, 0)
        addView(coverImage, LinearLayout.LayoutParams(dp(140), dp(186)))
        gap(14)
        addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { topMargin = dp(16) })
    }
    val expandedText = column().apply { pad(16, 24) }
    val scroller = scroll(column().apply {
        addView(header)
        addView(expandedText)
    }).apply { isFillViewport = false }
    val footer = row().apply { pad(16, 12, 0) }
    val badgeLayout = FrameLayout.LayoutParams(dp(61), dp(34), Gravity.BOTTOM or Gravity.END).apply {
        marginEnd = dp(16)
        bottomMargin = dp(compactHeight + 13)
    }
    addView(floorBadge, badgeLayout)
    addView(sheet, panelLayout)
    sheet.addView(handleArea, LinearLayout.LayoutParams(MATCH, dp(17)))
    sheet.fill(scroller)
    sheet.addView(footer)

    fun paint() {
        val holding = shelves.firstOrNull()?.holding ?: items.firstOrNull()
        val shelf = shelves.firstOrNull()?.shelf
        val canGuide = shelves.isNotEmpty() && holding?.availability != "unavailable"
        val cannotBorrow = loaded && holding?.availability == "unavailable"
        info.removeAllViews()
        info.addView(label(if (!loaded) "소장정보 확인 중" else holding?.availabilityLabel ?: "소장정보 없음", 12,
            if (holding?.availability == "available") Pobook.success else Pobook.muted))
        info.gap(8)
        info.addView(label(book.title, 18, Pobook.text, true).apply {
            maxLines = if (expanded) 3 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isAccessibilityHeading = true
        })
        if (loaded) {
            info.gap(8)
            info.addView(label(shelf?.let { "${it.floor}층 ${it.title}" } ?: holding?.name?.ifEmpty { null } ?: "위치 정보 없음",
                16, Pobook.secondary).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.gap(2)
            info.addView(label(holding?.call?.ifEmpty { null } ?: "청구기호 정보 없음", 16, Pobook.secondary).apply {
                setTextIsSelectable(true)
                if (!expanded) maxLines = 2
            })
        }

        expandedText.removeAllViews()
        expandedText.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) {
            expandedText.addView(label("${book.author} 저자", 16, Pobook.secondary))
            if (book.publisher.isNotEmpty()) {
                expandedText.gap(4)
                expandedText.addView(label("${book.publisher} 출판", 14, Pobook.secondary))
            }
            expandedText.gap(8)
            expandedText.addView(label(if (extra == null) "책 정보를 불러오고 있어요"
                else extra?.text?.ifEmpty { null } ?: "이 책의 정보가 아직 제공되지 않아요.", 12, Pobook.muted).apply {
                setTextIsSelectable(true)
            })
            extra?.source?.takeIf(String::isNotEmpty)?.let { source ->
                expandedText.addView(captionLink("책 소개 출처 · 원문 보기") { openWeb(source) })
            }
            if (bundled) expandedText.addView(label("실시간 소장 상태를 확인하지 못했어요.", 12, Pobook.muted))
            for (candidate in shelves.drop(1)) {
                expandedText.addView(captionLink("${candidate.shelf.floor}층 ${candidate.shelf.title}", "서가 보기") {
                    push(Screen.ShelfDetail(candidate.shelf))
                })
            }
            if (relatedBooks.isNotEmpty()) {
                expandedText.gap(14)
                expandedText.addView(section("함께 읽기 좋은 책"))
                expandedText.addView(carousel(relatedBooks))
            }
            expandedText.addView(captionLink("도서관에서 상세 정보 보기") {
                openWeb("https://postech.primo.exlibrisgroup.com/discovery/fulldisplay?docid=${android.net.Uri.encode(book.recordId)}&context=L&vid=82POSTECH_INST%3A82POSTECH&lang=ko")
            })
            expandedText.addView(captionLink("도서 위치 문제 신고") { report(book) })
        }
        footer.removeAllViews()
        footer.addView(label(if (cannotBorrow) "대출 중인 책" else "바로 안내 시작", 18,
            if (canGuide) Pobook.text else 0xFF888888.toInt(), true).apply {
            gravity = Gravity.CENTER
            glassTouch(26, if (canGuide) 0x80535353.toInt() else 0x63515151) { push(Screen.Locate(listOf(book))) }
            isEnabled = canGuide
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        footer.gap(12)
        footer.addView(glassSymbol("basket", if (state.cart.any { it.book.id == book.id }) "책바구니 보기" else "책바구니에 담기", 50) {
            if (state.cart.any { it.book.id == book.id }) push(Screen.Saved(ListKind.CART))
            else if (state.addToCart(book)) {
                toast("책바구니에 담았어요")
                paint()
            } else toast("책바구니에는 최대 30권까지 담을 수 있어요")
        })
        floorBadge.visibility = if (shelf != null && !expanded) View.VISIBLE else View.GONE
        if (shelf != null) floorBadge.text = "${shelf.floor}F"
    }
    fun switchDetent(value: Boolean) {
        if (expanded == value) return
        expanded = value
        val height = if (value) (frame.height - dp(56)).coerceAtLeast(dp(compactHeight)) else dp(compactHeight)
        panelLayout.height = height
        sheet.layoutParams = panelLayout
        handleArea.contentDescription = if (value) "책 정보 접기" else "책 정보 더 보기"
        paint()
        if (!value) scroller.scrollTo(0, 0)
    }
    handleArea.contentDescription = "책 정보 더 보기"
    handleArea.isFocusable = true
    handleArea.setOnClickListener { switchDetent(!expanded) }
    var dragFrom = 0f
    handleArea.setOnTouchListener { target, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { dragFrom = event.rawY; true }
            android.view.MotionEvent.ACTION_UP -> {
                val travel = event.rawY - dragFrom
                if (kotlin.math.abs(travel) < dp(14)) target.performClick() else switchDetent(travel < 0f)
                true
            }
            else -> true
        }
    }
    paint()
    scope.launch {
        val catalog = catalogData.await()
        val holdings = catalog.holdings(book)
        val library = mapData.await()
        items = holdings.items
        bundled = holdings.bundled
        shelves = library.candidates(items)
        loaded = true
        val destination = shelves.firstOrNull()?.shelf
        if (destination != null) {
            val map = IndoorMapView(this@detailScreen, library, destination.floor)
            activeMap = map
            map.showLocation(freshPosition())
            map.selectedShelves = shelves.map { it.shelf }
            val focus = destination.fixture.center
            if (focus != null) map.show(focus.copy(y = focus.y + 6.5), destination.floor, 3.8f)
            backgroundLayer.removeAllViews()
            backgroundLayer.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        paint()
    }
    scope.launch {
        val catalog = catalogData.await()
        extra = catalog.description(book)
        val cover = extra?.cover?.ifEmpty { null } ?: catalog.bundledCover(book)
        if (cover.isNotEmpty() && cover != book.cover) {
            covers.load(coverImage, cover, scope)
        }
        if (book.cover.isEmpty() && cover.isNotEmpty()) covers.load(blurredCover, cover, scope)
        paint()
    }
    scope.launch {
        relatedBooks = catalogData.await().related(book)
        if (expanded) paint()
    }
}

internal fun MainActivity.shelfScreen(screen: Screen.ShelfDetail): View = column().apply {
    val shelf = screen.shelf
    addView(topBar(if (screen.favoriteOnly) "이 서가에 찜한 책" else "서가 위치"))
    val holder = FrameLayout(this@shelfScreen)
    holder.addView(loading("서가 지도를 준비하고 있어요"), FrameLayout.LayoutParams(MATCH, MATCH))
    fill(holder)
    val details = body()
    details.addView(section("${shelf.floor}층 · ${shelf.title}",
        listOf(shelf.from, shelf.to).filter(String::isNotEmpty).joinToString(" ~ ").ifEmpty { null }))
    addView(scroll(details), LinearLayout.LayoutParams(MATCH, if (screen.favoriteOnly) dp(220) else WRAP))
    scope.launch {
        val library = mapData.await()
        val map = mapWithControls(library, shelf.floor) {
            it.selectedShelves = listOf(shelf)
            shelf.fixture.center?.let { center -> it.show(center, shelf.floor, 3.1f) }
        }
        holder.removeAllViews()
        holder.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        if (screen.favoriteOnly) {
            val catalog = catalogData.await()
            val matching = state.favorites.map { it.book }.filter { book ->
                library.candidates(catalog.knownHoldings(book)).any { it.shelf.fixture.id == shelf.fixture.id }
            }
            matching.forEach { details.addView(bookRow(it)) }
        }
    }
}
