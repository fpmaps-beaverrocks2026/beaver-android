package com.youngmin.pobook

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class GuidePhase { WALK, ARRIVED, FINDING, HELP, COMPLETE }

internal class GuidanceSession(plan: RoutePlan, val accessible: Boolean) {
    var visits = plan.visits
        private set
    var index = 0
        private set
    var phase = GuidePhase.WALK
    var stage = 0
    var expanded = false
    var foundCount = 0
        private set
    private var arrivedSince: Long? = null
    val visit get() = visits[index]
    val stages get() = visit.path.filter(RouteSegment::changesFloor)
    val floor get() = stages.getOrNull(stage - 1)?.to?.floor ?: visit.start.floor
    val nextTransition get() = stages.getOrNull(stage)
    val reached get() = phase == GuidePhase.ARRIVED || phase == GuidePhase.FINDING || phase == GuidePhase.HELP

    fun advanceFloor() {
        if (stage < stages.size) stage++
        arrivedSince = null
    }

    fun location(fix: PositionFix?): Boolean {
        if (fix?.floor == null || phase != GuidePhase.WALK || fix.accuracy > 17f) {
            arrivedSince = null
            return false
        }
        val next = nextTransition
        if (next != null) {
            if (fix.floor == next.to.floor && fix.point.distanceTo(next.to.point) <= 14 &&
                (next.to.floor != floor || fix.point.distanceTo(next.to.point) < fix.point.distanceTo(next.from.point))) {
                advanceFloor()
                return true
            }
            return false
        }
        if (fix.floor != visit.destination.floor || fix.point.distanceTo(visit.destination.point) > 6) {
            arrivedSince = null
            return false
        }
        val first = arrivedSince ?: fix.timestamp.also { arrivedSince = it }
        if (fix.timestamp - first < 1500) return false
        phase = GuidePhase.FINDING
        return true
    }

    fun previousFloor() {
        if (stage > 0) stage--
        arrivedSince = null
    }

    fun next(found: Boolean, rest: List<Visit>? = null) {
        if (found) foundCount++
        if (index + 1 >= visits.size) {
            phase = GuidePhase.COMPLETE
            return
        }
        if (rest != null) visits = visits.take(index + 1) + rest
        index++
        stage = 0
        arrivedSince = null
        phase = GuidePhase.WALK
    }
}

internal fun MainActivity.routePreparation(screen: Screen.Locate): View = column().apply {
    addView(topBar(if (screen.books.size == 1) "책 찾아가기" else "길안내 준비", close = true))
    val content = body()
    val footer = column().apply { pad(16, 10, 16) }
    fill(scroll(content))
    addView(footer)
    content.addView(loading("책이 있는 서가를 확인하고 있어요"), LinearLayout.LayoutParams(MATCH, dp(280)))
    scope.launch {
        val catalog = catalogData.await()
        val library = mapData.await()
        val targets = screen.books.map { book -> async { book to catalog.holdings(book) } }.awaitAll()
        val candidates = targets.map { (book, holding) -> RouteTarget(book, library.candidates(holding.items)) }
        val available = candidates.filter { it.shelves.isNotEmpty() }
        val excluded = candidates.filter { it.shelves.isEmpty() }
        val fallback = targets.any { it.second.bundled }
        val allOrigins = library.entrances
        val fix = freshPosition()
        val canUseFix = fix?.floor != null && library.inside(fix.point)
        if (screen.origin == null && canUseFix) {
            screen.origin = FloorPosition(fix.point, fix.floor, "현재 위치 · ${fix.floor}층")
        }
        fun failureMessage(title: String, body: String) {
            content.addView(empty(title, body))
        }
        var planning = false
        lateinit var startPlan: () -> Unit

        fun paint() {
            content.removeAllViews()
            footer.removeAllViews()
            if (available.isEmpty()) {
                failureMessage("안내할 수 있는 서가가 없어요", "소장처와 청구기호 또는 대출 상태를 확인해 주세요.")
                targets.forEach { (book, holdings) ->
                    content.addView(bookRow(book, holdings.items.firstOrNull()?.availabilityLabel ?: "소장 위치 확인 필요"))
                }
                footer.addView(button("도서 정보로 돌아가기", action = ::pop))
                return
            }
            content.addView(section("어디에서 출발할까요?", "출발 위치를 선택하면 책이 있는 서가까지 길을 안내해 드려요."))
            content.gap(16)
            val origin = screen.origin
            content.addView(captionLink("출발 위치", origin?.label ?: "출발 위치 선택") {
                val currentFix = freshPosition()?.takeIf { library.inside(it.point) }
                val here = currentFix?.floor?.let { FloorPosition(currentFix.point, it, "현재 위치 · ${it}층") }
                val options = listOfNotNull(here) + allOrigins
                val canConfirm = here == null && currentFix != null
                val names = options.map(FloorPosition::label) + listOf("지도에서 출발 위치 직접 설정") +
                    if (canConfirm) listOf("현재 위치의 층을 직접 선택") else emptyList()
                choose("출발 위치", names) { selected ->
                    if (selected == options.size) {
                        push(Screen.Origin(screen, available.first()))
                    } else if (selected < options.size) {
                        screen.origin = options[selected]
                        paint()
                    } else if (currentFix != null) {
                        choose("현재 위치의 층을 선택해 주세요", library.floors.map { "${it}층" }) { floorIndex ->
                            val floor = library.floors[floorIndex]
                            confirmCurrentFloor(floor)
                            screen.origin = FloorPosition(currentFix.point, floor, "현재 위치 · ${floor}층")
                            paint()
                        }
                    }
                }
            }.apply { background = shape(Pobook.panel, 14) })
            if (!canUseFix) {
                val positionNote = when {
                    fix == null -> "현재 위치를 확인할 수 없어요. 도서관 출입구를 선택할 수 있어요."
                    !library.inside(fix.point) -> "현재 위치가 도서관 밖에 있어요. 도서관 출입구를 선택해 주세요."
                    else -> "현재 층을 확인해 주세요. 도서관 출입구를 선택할 수도 있어요."
                }
                content.addView(label(positionNote, 13, Pobook.muted).apply { pad(3, 9) })
            }
            content.gap(10)
            content.addView(CheckBox(this@routePreparation).apply {
                text = "엘리베이터 이용하기"
                textSize = 15f
                setTextColor(Pobook.text)
                buttonTintList = ColorStateList.valueOf(Pobook.accent)
                isChecked = screen.stepFree
                setOnCheckedChangeListener { _, checked -> screen.stepFree = checked }
            })
            content.gap(20)
            content.addView(section("찾을 책 ${available.size}권"))
            content.gap(7)
            for (target in available) {
                content.addView(bookRow(target.book, target.shelves.map { "${it.shelf.floor}층 · ${it.shelf.title}" }.distinct().joinToString(" / ")))
            }
            if (excluded.isNotEmpty()) {
                content.gap(16)
                content.addView(section("안내할 수 없는 책 ${excluded.size}권",
                    "서가 또는 이용 가능 여부를 확인할 수 없는 책은 경로에서 제외돼요."))
                excluded.forEach { content.addView(bookRow(it.book, "서가 확인 필요")) }
            }
            if (fallback) {
                content.gap(10)
                content.addView(label("추천 도서에 저장된 서가 정보가 포함돼 있어요. 대출 상태는 도서관에서 확인해 주세요.", 13, Pobook.muted))
            }
            footer.addView(button(
                when {
                    planning -> "경로를 찾고 있어요"
                    available.size == 1 -> "길안내 시작하기"
                    else -> "경로 미리보기"
                }, enabled = origin != null && !planning,
                action = { startPlan() },
            ))
        }
        startPlan = plan@{
            val origin = screen.origin ?: return@plan
            if (planning || available.isEmpty()) return@plan
            val planRequest = PlanRequest(available, origin, screen.stepFree)
            planning = true
            paint()
            scope.launch {
                val plan = withContext(Dispatchers.Default) { library.plan(planRequest) }
                planning = false
                if (plan == null) {
                    paint()
                    showDialog("경로를 만들지 못했어요", "다른 출발 위치를 선택한 뒤 다시 시도해 주세요.", "확인") {}
                } else if (plan.visits.size == 1) {
                    push(Screen.Guide(GuidanceSession(plan, planRequest.accessible)))
                } else {
                    push(Screen.Preview(planRequest, plan))
                }
            }
        }
        if (screen.books.size == 1 && canUseFix && !screen.attemptedAutomaticStart) {
            screen.attemptedAutomaticStart = true
            startPlan()
        } else {
            paint()
        }
    }
}

internal fun MainActivity.routeOrigin(screen: Screen.Origin): View = FrameLayout(this).apply {
    val sink = this
    scope.launch {
        val library = mapData.await()
        val target = screen.destination.shelves.first().shelf
        val map = IndoorMapView(this@routeOrigin, library, screen.location.origin?.floor ?: target.floor)
        map.selectedShelves = listOf(target)
        target.fixture.center?.let { map.show(it, target.floor, 2.7f) }
        sink.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        activeMap = map
        val header = row().apply {
            gravity = Gravity.TOP
            pad(20, 20)
            addView(column().apply {
                addView(label(if (screen.location.books.size == 1) screen.destination.book.title else "출발지 직접 설정하기", 24, bold = true))
                gap(6)
                addView(label("${target.floor}층 ${target.title}", 24, Pobook.secondary, true))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            gap(16)
            addView(glassSymbol("close", "출발 위치 직접 설정 그만두기") { pop() })
        }
        sink.addView(header, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        sink.addView(ImageView(this@routeOrigin).apply {
            setImageResource(R.drawable.map_start)
            scaleType = ImageView.ScaleType.FIT_CENTER
            translationY = -dp(27).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(37), dp(45), Gravity.CENTER))
        val floors = column().apply { background = glass(12); clipToOutline = true }
        val footer = column().apply { pad(20, 0, 14) }
        var moved = false
        fun paintFooter() {
            footer.removeAllViews()
            val point = map.visibleCenter()
            val inside = library.areas[map.floor].orEmpty().firstOrNull { it.type == "level" }?.contains(point) == true
            if (moved && inside) {
                footer.addView(button("위치 확정하고 안내 받기") {
                    screen.location.origin = FloorPosition(map.visibleCenter(), map.floor, "${map.floor}층 · 지정 위치")
                    pop()
                })
            } else {
                footer.addView(label("층을 선택한 후,\n지도를 움직여 출발 위치를 직접 설정해주세요", 16).apply {
                    gravity = Gravity.CENTER
                    background = glass(12)
                    pad(16, 10)
                })
            }
            footer.addView(link("출입구 선택", Pobook.secondary) {
                choose("출발 위치", library.entrances.map(FloorPosition::label)) { index ->
                    screen.location.origin = library.entrances[index]
                    pop()
                }
            })
        }
        fun paintFloors() {
            floors.removeAllViews()
            for ((index, floor) in library.floors.asReversed().withIndex()) {
                if (index > 0) floors.addView(divider())
                val current = floor == map.floor
                floors.addView(label(if (current) "현재 ${floor}F" else "${floor}F", 13,
                    if (current) Pobook.bg else Pobook.text, current).apply {
                    gravity = Gravity.CENTER
                    touch(if (current) Pobook.accent else android.graphics.Color.TRANSPARENT, 0) {
                        map.setFloor(floor)
                        moved = true
                        paintFloors()
                        paintFooter()
                    }
                }, LinearLayout.LayoutParams(dp(61), dp(33)))
            }
        }
        map.onCameraInteraction = {
            moved = true
            paintFooter()
        }
        paintFloors()
        sink.addView(floors, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(17); bottomMargin = dp(121)
        })
        sink.addView(footer, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        sink.post(::paintFooter)
    }
}

internal fun MainActivity.routePreview(screen: Screen.Preview): View = FrameLayout(this).apply {
    val route = screen.plan.visits
    var selection = 0
    val frame = FrameLayout(this@routePreview)
    frame.addView(loading("안내 지도를 준비하고 있어요"), FrameLayout.LayoutParams(MATCH, MATCH))
    addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
    val toolbar = row().apply { pad(16, 8, 0) }
    val panel = column().apply {
        background = glass(28, 0xE1242424.toInt())
        pad(16, 11, 12)
    }
    val panelHeight = 274
    val badge = label("", 14, Pobook.text, true).apply { gravity = Gravity.CENTER; background = glass(12) }
    fun paint() {
        val visit = route[selection]
        toolbar.removeAllViews()
        toolbar.addView(glassSymbol("back", "뒤로") { pop() })
        toolbar.gap(12)
        val options = row().apply { pad(0, 4) }
        for ((index, item) in route.withIndex()) {
            if (index > 0) options.gap(12)
            options.addView(label(item.book.title, 14, if (selection == index) Pobook.bg else Pobook.text).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isSelected = selection == index
                pad(16, 0)
                glassTouch(17, if (selection == index) 0xF0DDF53C.toInt() else Pobook.glassFill) {
                    selection = index
                    paint()
                }
            }, LinearLayout.LayoutParams(dp(114), dp(32)))
        }
        toolbar.fill(HorizontalScrollView(this@routePreview).apply {
            isHorizontalScrollBarEnabled = false
            addView(options)
            post { scrollTo(dp((selection * 126 - 70).coerceAtLeast(0)), 0) }
        })
        badge.text = "${visit.destination.floor}F"
        panel.removeAllViews()
        panel.addView(row().apply {
            gravity = Gravity.TOP
            addView(bookCover(visit.book), LinearLayout.LayoutParams(dp(140), dp(186)))
            gap(14)
            addView(column().apply {
                pad(0, 13)
                addView(label(visit.target.holding.availabilityLabel, 12,
                    if (visit.target.holding.availability == "available") Pobook.success else Pobook.muted))
                gap(8)
                addView(label(visit.book.title, 18, Pobook.text, true).apply { maxLines = 2 })
                gap(8)
                addView(label("${visit.destination.floor}층 ${visit.target.shelf.title}", 16, Pobook.secondary))
                addView(label(visit.target.holding.call, 16, Pobook.secondary))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        panel.addView(row().apply {
            addView(label("바로 안내 시작", 18, Pobook.text, true).apply {
                gravity = Gravity.CENTER
                glassTouch(25) { replace(Screen.Guide(GuidanceSession(screen.plan, screen.request.accessible))) }
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
            gap(12)
            addView(glassSymbol("location", "출발 위치 변경", 50) { pop() })
        })
        activeMap?.apply {
            visits = route
            selectedShelves = listOf(visit.target.shelf)
            showVisit(visit, visit.destination.floor)
        }
    }
    addView(toolbar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
    addView(badge, FrameLayout.LayoutParams(dp(61), dp(34), Gravity.BOTTOM or Gravity.END).apply {
        marginEnd = dp(16); bottomMargin = dp(panelHeight + 13)
    })
    addView(panel, FrameLayout.LayoutParams(MATCH, dp(panelHeight), Gravity.BOTTOM))
    paint()
    scope.launch {
        val library = mapData.await()
        val map = IndoorMapView(this@routePreview, library, route.first().destination.floor)
        activeMap = map
        frame.removeAllViews()
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        paint()
    }
}

internal fun MainActivity.routeGuidance(session: GuidanceSession): View = when (session.phase) {
    GuidePhase.WALK -> walkingScreen(session)
    GuidePhase.ARRIVED -> arrivalScreen(session)
    GuidePhase.FINDING -> findingScreen(session)
    GuidePhase.HELP -> helpScreen(session)
    GuidePhase.COMPLETE -> completedScreen(session)
}

private fun MainActivity.walkingScreen(session: GuidanceSession): View = FrameLayout(this).apply {
    val visit = session.visit
    val next = session.nextTransition
    val frame = FrameLayout(this@walkingScreen)
    frame.addView(loading("안내 지도를 준비하고 있어요"), FrameLayout.LayoutParams(MATCH, MATCH))
    addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
    addView(View(this@walkingScreen).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xC6262B31.toInt(), 0x79121212, 0x00121212),
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(MATCH, dp(310), Gravity.TOP))
    val heading = column().apply {
        pad(20, 20, 0)
        addView(row().apply {
            gravity = Gravity.TOP
            addView(column().apply {
                addView(label(visit.book.title, 24, bold = true).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    isAccessibilityHeading = true
                })
                gap(6)
                addView(label("${visit.destination.floor}층 ${visit.target.shelf.title}", 24, Pobook.secondary, true))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            gap(16)
            addView(glassSymbol("close", "안내 종료") { back() })
        })
        gap(38)
        addView(label("현재 ${session.floor}F", 14, Pobook.text, true).apply {
            gravity = Gravity.CENTER
            glassTouch(12) {
                scope.launch {
                    val library = mapData.await()
                    choose("현재 층 선택", library.floors.map { "${it}층" }) { index ->
                        val floor = library.floors[index]
                        confirmCurrentFloor(floor)
                        activeMap?.showVisit(visit, floor)
                    }
                }
            }
        }, LinearLayout.LayoutParams(dp(79), dp(34)))
    }
    addView(heading, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

    val panelHeight = if (session.expanded) 308 else 102
    val buttons = column().apply {
        pad(16, 0)
        addView(row().apply {
            addView(View(this@walkingScreen), LinearLayout.LayoutParams(0, 1, 1f))
            addView(glassSymbol("location", "현재 위치로 지도 이동", 48) {
                val map = activeMap ?: return@glassSymbol
                val fix = freshPosition()
                if (fix?.floor != null) map.show(fix.point, fix.floor, 2.4f) else map.showVisit(visit, session.floor)
            })
        })
        gap(16)
        addView(row().apply {
            gravity = Gravity.CENTER
            if (session.stage > 0 && next == null) {
                addView(routeGlassButton("‹ 이전 경로") { session.previousFloor(); render() })
                gap(8)
            }
            val action = if (next != null) "${next.to.floor}층에 도착했어요" else "${visit.target.shelf.title} 도착 시 눌러주세요"
            addView(routeGlassButton(action) {
                if (next != null) session.advanceFloor() else session.phase = GuidePhase.FINDING
                render()
            })
        })
    }
    addView(buttons, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(panelHeight + 16) })
    val sheet = column().apply {
        background = glass(28, 0xCE272727.toInt())
        pad(16, 0, 14)
        val handle = FrameLayout(this@walkingScreen).apply {
            addView(View(this@walkingScreen).apply { background = shape(0x55FFFFFF, 3) },
                FrameLayout.LayoutParams(dp(57), dp(4), Gravity.CENTER))
            contentDescription = if (session.expanded) "길안내 시트 접기" else "길안내 시트 펼치기"
            setOnClickListener { session.expanded = !session.expanded; render() }
            isFocusable = true
        }
        addView(handle, LinearLayout.LayoutParams(MATCH, dp(24)))
        val instruction = if (next == null) "${visit.target.shelf.title}로 이동하세요."
            else "${next.to.floor}층으로 ${if (next.to.floor > next.from.floor) "올라가세요." else "내려가세요."}"
        addView(row().apply {
            val pictogram = if (next?.kind == "elevator") R.drawable.map_elevator else if (next != null) R.drawable.map_stairs else R.drawable.map_end
            addView(ImageView(this@walkingScreen).apply {
                setImageResource(pictogram)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(23), dp(23)))
            gap(6)
            fill(label(instruction, 16).apply { maxLines = 2 })
        }, LinearLayout.LayoutParams(MATCH, dp(28)))
        gap(12)
        addView(routeTimeline(session))
        if (session.expanded) {
            gap(16)
            addView(divider())
            gap(13)
            addView(bookRow(visit.book, "${visit.destination.floor}층 · ${visit.target.shelf.title}"))
            addView(label(visit.target.holding.call, 14, Pobook.secondary).apply { pad(6, 4) })
            if (session.visits.size > 1) addView(link("다음 책으로 건너뛰기") {
                showDialog("이 책을 건너뛸까요?", visit.book.title, "건너뛰기") { finishVisit(session, false, null) }
            })
        }
    }
    addView(sheet, FrameLayout.LayoutParams(MATCH, dp(panelHeight), Gravity.BOTTOM))
    scope.launch {
        val library = mapData.await()
        val map = IndoorMapView(this@walkingScreen, library, session.floor).apply {
            visits = listOf(visit)
            selectedShelves = listOf(visit.target.shelf)
            showVisit(visit, session.floor)
            showLocation(freshPosition())
        }
        activeMap = map
        frame.removeAllViews()
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        positionListener = { fix -> if (session.location(fix)) render() }
    }
}

private fun MainActivity.routeGlassButton(text: String, action: () -> Unit) = label(text, 16, Pobook.text).apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(44)
    pad(16, 8)
    glassTouch(14, action = action)
}

private fun MainActivity.routeTimeline(session: GuidanceSession): View {
    val line = row()
    val descriptions = mutableListOf<String>()
    fun marker(text: String) {
        line.addView(label(text, 11, Pobook.bg, bold = true).apply {
            gravity = Gravity.CENTER
            pad(5, 0)
            minimumWidth = dp(24)
            background = shape(Pobook.accent, 12)
        }, LinearLayout.LayoutParams(WRAP, dp(24)))
    }
    fun track(text: String, color: Int, fill: Int, icon: Int? = null) {
        descriptions += text
        line.addView(row().apply {
            background = shape(fill, if (icon == null) 0 else 12)
            if (icon != null) addView(ImageView(this@routeTimeline).apply {
                setImageResource(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(label(text, 8, color, true).apply {
                gravity = Gravity.CENTER
                minimumWidth = dp(46)
                pad(6, 0)
            }, LinearLayout.LayoutParams(WRAP, dp(20)))
        })
    }
    marker(if (session.index == 0) "출발" else "${session.index}")
    var walkingMeters = 0.0
    fun walk(stage: Int) {
        if (walkingMeters < 0.5) return
        val steps = (walkingMeters / 0.7).roundToInt()
        track("약 ${steps}걸음", if (stage < session.stage) Pobook.bg else Pobook.muted,
            if (stage < session.stage) Pobook.accent else 0xFF777777.toInt())
        walkingMeters = 0.0
    }
    var stage = 0
    for (part in session.visit.path) {
        if (!part.changesFloor) {
            walkingMeters += part.length
            continue
        }
        walk(stage)
        track("${part.from.floor}F → ${part.to.floor}F", 0xFF005207.toInt(),
            if (stage < session.stage) Pobook.accent else 0xFFAEEA89.toInt(),
            if (part.kind == "elevator") R.drawable.map_elevator else R.drawable.map_stairs)
        stage++
    }
    walk(stage)
    marker("${session.index + 1}")
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(line, FrameLayout.LayoutParams(WRAP, WRAP))
        line.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentDescription = "경로 진행, ${session.index + 1}번째 책 안내 중. ${descriptions.joinToString(", ")}"
        isFocusable = true
    }
}

private fun MainActivity.arrivalScreen(session: GuidanceSession): View = column().apply {
    val visit = session.visit
    addView(topBar("서가 도착", close = true))
    val content = body()
    content.addView(section("서가에 도착했어요!", "${session.index + 1}/${session.visits.size}"))
    content.gap(13)
    content.addView(bookRow(visit.book, "${visit.target.shelf.floor}층 · ${visit.target.shelf.title}"))
    val mapFrame = FrameLayout(this@arrivalScreen)
    content.addView(mapFrame, LinearLayout.LayoutParams(MATCH, dp(220)))
    content.gap(15)
    content.addView(callSticker(visit.target.holding.call))
    content.gap(8)
    content.addView(label("책등에 적힌 청구기호를 확인해 주세요.", 15, Pobook.muted))
    fill(scroll(content))
    addView(column().apply {
        pad(16, 10, 16)
        addView(button("책 찾는 방법 보기") { session.phase = GuidePhase.FINDING; render() })
        gap(8)
        addView(row().apply {
            fill(button("못 찾았어요", primary = false) { session.phase = GuidePhase.HELP; render() })
            gap(8)
            fill(button("찾았어요", primary = false) { finishVisit(session, true, null) })
        })
    })
    scope.launch {
        val library = mapData.await()
        mapFrame.addView(mapWithControls(library, visit.destination.floor) {
            it.visits = listOf(visit)
            it.selectedShelves = listOf(visit.target.shelf)
            it.show(visit.destination.point, visit.destination.floor, 3.2f)
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

private fun MainActivity.callSticker(call: String): View = column().apply {
    background = shape(0xFFF3F3F3.toInt(), 12)
    pad(16, 12)
    addView(label("청구기호", 13, 0xFF3A3A3A.toInt(), true))
    gap(6)
    addView(label(call.ifEmpty { "청구기호 확인 필요" }, 23, Pobook.bg, true).apply { setTextIsSelectable(true) })
}

private fun MainActivity.findingScreen(session: GuidanceSession): View = column().apply {
    val visit = session.visit
    var spine = ""
    addView(topBar(""))
    val content = column().apply { pad(0, 15, 8) }
    val display = column().apply {
        pad(20, 0)
        addView(label("${visit.destination.floor}층 ${visit.target.shelf.title}", 20, Pobook.text))
        addView(label(visit.book.title, 24, Pobook.text, true).apply { isAccessibilityHeading = true })
        gap(16)
        val mainNumber = CallNumber.parse(visit.target.holding.call)?.number?.toPlainString()
        addView(label(if (mainNumber == null) "책등에서 청구기호를 확인해 주세요."
            else "책등에서 ${mainNumber}을 먼저 찾은 후\n뒤의 숫자들을 이어서 찾으세요.", 18, Pobook.muted))
        gap(7)
        addView(link("청구기호 보기", Pobook.text) { session.phase = GuidePhase.ARRIVED; render() }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })
    }
    content.addView(display)
    content.addView(View(this@findingScreen), LinearLayout.LayoutParams(MATCH, dp(12), 1f))
    val bookSpine = ImageView(this@findingScreen).apply {
        setImageResource(R.drawable.map_spine)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "${visit.book.title} 책등"
    }
    val shelf = FrameLayout(this@findingScreen)
    shelf.addView(ImageView(this@findingScreen).apply {
        setImageResource(R.drawable.extra_books_left)
        scaleType = ImageView.ScaleType.FIT_END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(dp(145), dp(332), Gravity.BOTTOM or Gravity.START))
    shelf.addView(ImageView(this@findingScreen).apply {
        setImageResource(R.drawable.extra_books_right)
        scaleType = ImageView.ScaleType.FIT_START
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(dp(145), dp(332), Gravity.BOTTOM or Gravity.END))
    shelf.addView(bookSpine, FrameLayout.LayoutParams(dp(96), MATCH, Gravity.CENTER_HORIZONTAL))
    shelf.addView(label(visit.target.holding.call, 12, Pobook.bg, true).apply {
        pad(4, 4)
        gravity = Gravity.CENTER
        background = shape(0xFFDBDBDB.toInt(), 2)
    }, FrameLayout.LayoutParams(dp(74), WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
    content.addView(shelf, LinearLayout.LayoutParams(MATCH, dp(411)))
    fill(scroll(content))
    addView(column().apply {
        pad(36, 16, 10)
        addView(button("찾았어요") { finishVisit(session, true, spine) })
        gap(7)
        addView(link("못 찾았어요.", Pobook.secondary) { session.phase = GuidePhase.HELP; render() }.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        })
    })
    scope.launch {
        spine = catalogData.await().description(visit.book).spine
        covers.load(bookSpine, spine, scope)
    }
}

private fun MainActivity.helpScreen(session: GuidanceSession): View = column().apply {
    val visit = session.visit
    addView(topBar("책 찾기 도움", close = true))
    val content = body()
    content.addView(section("책을 찾기 어려우신가요?", "서가의 다른 면도 살펴보고, 그래도 보이지 않으면 도서관 직원에게 청구기호를 보여주세요."))
    content.gap(18)
    content.addView(callSticker(visit.target.holding.call))
    content.gap(12)
    content.addView(bookRow(visit.book, "${visit.target.shelf.floor}층 · ${visit.target.shelf.title}"))
    content.addView(captionLink("도서 위치 문제 신고") { report(visit.book) })
    fill(scroll(content))
    addView(column().apply {
        pad(16, 8, 15)
        addView(button(if (session.index + 1 < session.visits.size) "다음 책 찾아가기" else "안내 마치기") {
            finishVisit(session, false, null)
        })
        gap(8)
        addView(button("다시 찾아볼게요", primary = false) { session.phase = GuidePhase.FINDING; render() })
    })
}

private fun MainActivity.completedScreen(session: GuidanceSession): View = column().apply {
    addView(topBar("길안내 완료", close = true))
    val content = body()
    content.addView(empty("책 찾기 안내가 끝났어요", "${session.visits.size}권 중 ${session.foundCount}권을 찾았어요."))
    if (session.foundCount > 0) {
        content.addView(captionLink("최근 찾은 책 목록") {
            endRoute()
            push(Screen.Saved(ListKind.FOUND))
        })
    }
    fill(scroll(content))
    addView(column().apply {
        pad(16, 8, 15)
        addView(button("완료", action = ::endRoute))
    })
}

private fun MainActivity.finishVisit(session: GuidanceSession, found: Boolean, spine: String?) {
    val visit = session.visit
    val remaining = session.visits.drop(session.index + 1)
    if (found) state.found(visit.book, spine.orEmpty())
    if (remaining.isEmpty() || session.reached) {
        session.next(found)
        render()
        return
    }
    scope.launch {
        val library = mapData.await()
        val live = freshPosition()?.takeIf { it.floor != null && library.inside(it.point) }
        val lastConfirmed = session.stages.getOrNull(session.stage - 1)?.to ?: visit.start
        val origin = live?.floor?.let { FloorPosition(live.point, it, "현재 위치") } ?: lastConfirmed
        val adjusted = withContext(Dispatchers.Default) { library.reroute(remaining, origin, session.accessible) }
        if (adjusted == null) {
            showDialog("다음 경로를 찾지 못했어요", "출발 위치를 다시 확인해 주세요.", "확인") {}
            return@launch
        }
        session.next(found, adjusted)
        render()
    }
}

private fun minutes(seconds: Double) = maxOf(1, ceil(seconds / 60).toInt())
