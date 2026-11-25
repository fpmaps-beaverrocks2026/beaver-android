package com.youngmin.pobook

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

internal enum class Tab(val title: String, val icon: String) { HOME("홈", "home"), RECOMMEND("추천", "book"), SEARCH("검색", "search") }
internal enum class ListKind(val title: String) { FAVORITES("내가 읽고 싶은 책"), CART("책바구니"), FOUND("최근 찾은 책 목록") }
internal enum class SetupMode { FIRST_RUN, AFFILIATION, INTERESTS }
internal enum class SetupStep { WELCOME, AFFILIATION, INTERESTS }
internal class SetupState(val mode: SetupMode, val step: SetupStep, var affiliation: Affiliation?, val interests: MutableSet<String>)

internal sealed interface Screen {
    data class Setup(val state: SetupState) : Screen
    data object Home : Screen
    data object Profile : Screen
    data class Saved(val kind: ListKind) : Screen
    data class Detail(val book: Book) : Screen
    data class ShelfDetail(val shelf: Shelf, val favoriteOnly: Boolean = false) : Screen
    data class GameBooth(val booth: ExhibitionBooth, val selectedGameId: Int? = null) : Screen
    data class Locate(
        val books: List<Book>,
        var origin: FloorPosition? = null,
        var stepFree: Boolean = false,
        var attemptedAutomaticStart: Boolean = false,
    ) : Screen
    data class Origin(val location: Locate, val destination: RouteTarget) : Screen
    data class Preview(val request: PlanRequest, val plan: RoutePlan) : Screen
    data class Guide(val session: GuidanceSession) : Screen
}

class MainActivity : AppCompatActivity() {
    internal val state by lazy { LibraryState(this) }
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var scope = newScreenScope()
        private set
    internal val catalogData by lazy { lifetime.async(Dispatchers.IO) { Catalog(applicationContext) } }
    internal val mapData by lazy { lifetime.async(Dispatchers.IO) { IndoorLibrary(applicationContext) } }
    internal val exhibitionData by lazy { lifetime.async(Dispatchers.IO) { IndoorLibrary(applicationContext, IndoorDataset.SETEC) } }
    internal val exhibitionGamesData by lazy { lifetime.async(Dispatchers.IO) { ExhibitionGames(applicationContext) } }
    internal val covers = BookCovers()
    internal val search = SearchState()
    internal val cartSelection = linkedSetOf<String>()
    private val screens = mutableListOf<Screen>()
    internal val current get() = screens.last()
    internal var tab = Tab.HOME
    internal var homeFloor = IndoorDataset.SETEC.initialFloor
    internal var focusedExhibitionBooth: String? = null
    internal var showAmenities = false
    internal val favoriteSelection = linkedSetOf<String>()
    internal var positionFix: PositionFix? = null
        private set
    private lateinit var positionSource: IndoorPositionSource
    private lateinit var root: LinearLayout
    private var locationPrompted = false
    internal var activeMap: IndoorMapView? = null
    internal var positionListener: ((PositionFix?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        positionSource = IndoorPositionSource(this, ::receivedPosition)
        root = column().apply {
            setBackgroundColor(Pobook.bg)
            isFocusableInTouchMode = true
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, maxOf(bars.top, dp(54)), bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        tab = Tab.entries.getOrElse(savedInstanceState?.getInt("tab") ?: 0) { Tab.HOME }
        homeFloor = savedInstanceState?.getInt("exhibitionFloor", IndoorDataset.SETEC.initialFloor) ?: IndoorDataset.SETEC.initialFloor
        focusedExhibitionBooth = savedInstanceState?.getString("focusedExhibitionBooth")
        locationPrompted = savedInstanceState?.getBoolean("locationPrompted") ?: false
        screens += if (state.profile.completed) Screen.Home else Screen.Setup(
            SetupState(SetupMode.FIRST_RUN, SetupStep.WELCOME, null, linkedSetOf())
        )
        catalogData.start()
        exhibitionData.start()
        exhibitionGamesData.start()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab.ordinal)
        outState.putInt("exhibitionFloor", homeFloor)
        outState.putString("focusedExhibitionBooth", focusedExhibitionBooth)
        outState.putBoolean("locationPrompted", locationPrompted)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        positionSource.start()
    }

    override fun onStop() {
        positionSource.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        lifetime.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST && grantResults.contains(PackageManager.PERMISSION_GRANTED)) positionSource.start()
    }

    internal fun receivedPosition(fix: PositionFix?) {
        positionFix = fix
        val visible = freshPosition()
        activeMap?.showLocation(visible)
        positionListener?.invoke(visible)
    }

    internal fun freshPosition() = positionFix?.takeIf { it.isFresh(SystemClock.elapsedRealtime()) }

    internal fun currentLocation(map: IndoorMapView, library: IndoorLibrary) {
        if (!positionSource.hasPermission()) {
            if (locationPrompted && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                showDialog("위치 권한 설정", "현재 위치를 보려면 앱 설정에서 위치 권한을 허용해 주세요.",
                    "설정 열기", ::openLocationSettings)
            } else requestLocation()
            return
        }
        val fix = freshPosition()
        if (fix == null) {
            toast("현재 위치를 확인 중이에요")
        } else if (!library.inside(fix.point)) {
            toast("현재 위치가 ${library.dataset.venueName} 밖에 있어요")
        } else if (library.floors.size == 1) {
            map.show(fix.point, library.floors.single(), 2.2f)
        } else if (fix.floor != null && fix.floor in library.floors) {
            map.show(fix.point, fix.floor, 2.2f)
        } else {
            choose("현재 위치의 층을 선택해 주세요", library.floors.map { "${it}층" }) { index ->
                val floor = library.floors[index]
                positionSource.confirmFloor(floor)
                map.show(fix.point, floor, 2.2f)
            }
        }
    }

    internal fun confirmCurrentFloor(floor: Int) = positionSource.confirmFloor(floor)

    internal fun requestLocation() {
        if (positionSource.hasPermission()) return
        locationPrompted = true
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
    }

    internal fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    internal fun push(screen: Screen) {
        hideKeyboard()
        screens += screen
        render()
    }

    internal fun replace(screen: Screen) {
        screens[screens.lastIndex] = screen
        render()
    }

    internal fun back() {
        val screen = current
        if (screen is Screen.Guide) {
            when (screen.session.phase) {
                GuidePhase.ARRIVED, GuidePhase.HELP -> {
                    screen.session.phase = GuidePhase.FINDING
                    render()
                }
                GuidePhase.FINDING -> {
                    screen.session.phase = GuidePhase.WALK
                    render()
                }
                GuidePhase.COMPLETE -> endRoute()
                else -> showDialog("길안내를 종료할까요?", "진행 중인 안내가 종료돼요.", "종료", ::endRoute)
            }
        } else if (screens.size > 1) {
            pop()
        } else if (screen == Screen.Home && tab != Tab.HOME) {
            tab = Tab.HOME
            render()
        } else finish()
    }

    internal fun pop() {
        if (screens.size > 1) screens.removeAt(screens.lastIndex)
        render()
    }

    internal fun endRoute() {
        while (screens.size > 1 && (current is Screen.Locate || current is Screen.Origin || current is Screen.Preview || current is Screen.Guide)) {
            screens.removeAt(screens.lastIndex)
        }
        render()
    }

    internal fun openDetail(book: Book) {
        state.view(book)
        push(Screen.Detail(book))
    }

    internal fun beginSetup(mode: SetupMode) {
        val profile = state.profile
        push(Screen.Setup(SetupState(mode, if (mode == SetupMode.INTERESTS) SetupStep.INTERESTS else SetupStep.AFFILIATION,
            profile.affiliation, profile.interests.toMutableSet())))
    }

    internal fun completeSetup(setup: SetupState, skip: Boolean = false) {
        if (!skip) {
            state.profile = Profile(true, setup.affiliation, setup.interests.toList())
        } else if (setup.mode == SetupMode.FIRST_RUN) {
            state.profile = Profile(completed = true)
        }
        if (setup.mode == SetupMode.FIRST_RUN) {
            screens.clear()
            screens += Screen.Home
            render()
            root.post {
                if (!isFinishing && !locationPrompted && !positionSource.hasPermission()) requestLocation()
            }
        } else {
            while (screens.size > 1 && current is Screen.Setup) screens.removeAt(screens.lastIndex)
            render()
        }
    }

    internal fun render() {
        scope.cancel()
        scope = newScreenScope()
        activeMap = null
        positionListener = null
        root.removeAllViews()
        val screen = current
        root.setBackgroundColor(if (screen == Screen.Home && tab == Tab.HOME) 0xFF293340.toInt() else Pobook.bg)
        if (screen == Screen.Home) {
            val frame = FrameLayout(this)
            frame.setBackgroundColor(if (tab == Tab.SEARCH) 0xFF111111.toInt() else Pobook.black)
            if (tab == Tab.HOME) {
                frame.addView(mapHome(), FrameLayout.LayoutParams(MATCH, MATCH))
                frame.addView(topBar(tab.title, tabs = true), FrameLayout.LayoutParams(MATCH, dp(62), Gravity.TOP))
            } else {
                frame.addView(column().apply {
                    val heading = if (tab == Tab.RECOMMEND) "전시 게임" else tab.title
                    addView(topBar(heading, tabs = true), LinearLayout.LayoutParams(MATCH, dp(62)))
                    fill(if (tab == Tab.RECOMMEND) exhibitionGamesContent() else searchHome())
                }, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = dp(74) })
            }
            frame.addView(bottomTabs(), FrameLayout.LayoutParams(MATCH, dp(62), Gravity.BOTTOM))
            root.fill(frame)
        } else {
            val content = when (screen) {
                is Screen.Setup -> setupScreen(screen.state)
                Screen.Profile -> profileScreen()
                is Screen.Saved -> savedScreen(screen.kind)
                is Screen.Detail -> detailScreen(screen.book)
                is Screen.ShelfDetail -> shelfScreen(screen)
                is Screen.GameBooth -> gameBoothScreen(screen)
                is Screen.Locate -> routePreparation(screen)
                is Screen.Origin -> routeOrigin(screen)
                is Screen.Preview -> routePreview(screen)
                is Screen.Guide -> routeGuidance(screen.session)
                Screen.Home -> error("Home is rendered above")
            }
            root.fill(content)
        }
        if (tab != Tab.SEARCH || screen != Screen.Home) root.requestFocus()
    }

    internal fun revealExhibitionBooth(areaId: String) {
        focusedExhibitionBooth = areaId
        hideKeyboard()
        tab = Tab.HOME
        screens.clear()
        screens.add(Screen.Home)
        render()
    }

    internal fun topBar(title: String, tabs: Boolean = false, close: Boolean = false, extra: View? = null): View {
        if (tabs) return row().apply {
            pad(16, 0)
            fill(label(title, 34, android.graphics.Color.WHITE, bold = true).apply {
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                letterSpacing = 0f
                isAccessibilityHeading = true
            })
            val paired = row().apply {
                background = glass(23)
                for ((name, screen) in listOf("bookmark" to ListKind.FAVORITES, "basket" to ListKind.CART)) {
                    addView(LineIcon(this@MainActivity, name, android.graphics.Color.WHITE, 25).apply {
                        contentDescription = screen.title
                        touch(radius = 22) { push(Screen.Saved(screen)) }
                    }, LinearLayout.LayoutParams(dp(53), dp(44)))
                }
            }
            addView(paired)
            gap(11)
            addView(glassSymbol("person_filled", "마이페이지") { push(Screen.Profile) })
        }
        return FrameLayout(this).apply {
            minimumHeight = dp(55)
            val heading = label(title, 17, android.graphics.Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                isAccessibilityHeading = title.isNotEmpty()
                pad(68, 0)
            }
            addView(heading, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(glassSymbol(if (close) "close" else "back", if (close) "닫기" else "뒤로") { back() },
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(16) })
            if (extra != null) addView(extra, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(16) })
        }
    }

    private fun bottomTabs(): View = FrameLayout(this).apply {
        val pair = row().apply {
            background = glass(32)
            pad(4, 4)
            for (item in listOf(Tab.HOME, Tab.RECOMMEND)) {
                val color = if (tab == item) Pobook.tabAccent else android.graphics.Color.WHITE
                val cell = column().apply {
                    gravity = Gravity.CENTER
                    isSelected = tab == item
                    contentDescription = item.title
                    addView(LineIcon(this@MainActivity, if (item == Tab.HOME) "home_filled" else "book_filled", color, 29),
                        LinearLayout.LayoutParams(dp(31), dp(29)))
                    gap(2)
                    addView(label(item.title, 10, color, bold = true), LinearLayout.LayoutParams(WRAP, WRAP))
                    touch(if (tab == item) 0xA0242424.toInt() else android.graphics.Color.TRANSPARENT, 27) { selectTab(item) }
                }
                addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
        }
        addView(pair, FrameLayout.LayoutParams(dp(188), dp(62), Gravity.START or Gravity.BOTTOM).apply { marginStart = dp(21) })
        addView(LineIcon(this@MainActivity, "search", if (tab == Tab.SEARCH) Pobook.tabAccent else android.graphics.Color.WHITE, 35).apply {
            contentDescription = "검색"
            isSelected = tab == Tab.SEARCH
            glassTouch(32, if (tab == Tab.SEARCH) 0xAE535353.toInt() else Pobook.glassFill) { selectTab(Tab.SEARCH) }
        }, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.END or Gravity.BOTTOM).apply { marginEnd = dp(21) })
    }

    private fun selectTab(value: Tab) {
        if (value == tab) return
        hideKeyboard()
        tab = value
        render()
    }

    internal fun mapWithControls(
        library: IndoorLibrary,
        floor: Int,
        recenter: ((IndoorMapView) -> Unit)? = null,
        home: Boolean = false,
        floorPicker: Boolean = true,
        configure: (IndoorMapView) -> Unit = {},
    ): FrameLayout {
        val map = IndoorMapView(this, library, floor, home)
        activeMap = map
        map.showLocation(freshPosition())
        val frame = FrameLayout(this)
        frame.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        val controls = column()
        val floors = column().apply {
            background = if (home) glass(12) else shape(0xDD252525.toInt(), 12, 0x33FFFFFF)
            clipToOutline = true
        }
        fun update(selected: Int) {
            floors.removeAllViews()
            library.floors.asReversed().forEachIndexed { index, value ->
                if (index > 0) floors.addView(divider())
                floors.addView(label(if (home) "${value}F" else "${value}층", 14,
                    if (value == selected) Pobook.bg else Pobook.text, value == selected).apply {
                    gravity = Gravity.CENTER
                    isSelected = value == selected
                    contentDescription = "${value}층 지도 보기"
                    touch(if (value != selected) android.graphics.Color.TRANSPARENT else if (home) Pobook.accent else Pobook.text, 0) {
                        map.setFloor(value)
                    }
                }, LinearLayout.LayoutParams(dp(if (home) 61 else 56), dp(33)))
            }
        }
        update(floor)
        map.onFloorChanged = ::update
        val controlBottom = if (home) 82 else 16
        if (home) {
            fun syncAmenities(button: TextView) {
                map.showAmenities = showAmenities
                button.setTextColor(if (showAmenities) Pobook.accent else Pobook.text)
                button.contentDescription = if (showAmenities) "편의시설 · 전체 시설 표시 중" else "편의시설"
            }
            val amenities = label("편의시설", 12).apply {
                gravity = Gravity.CENTER
                glassTouch(12) { showAmenities = !showAmenities; syncAmenities(this) }
                syncAmenities(this)
            }
            controls.addView(amenities, LinearLayout.LayoutParams(dp(61), dp(34)))
            controls.gap(12)
        }
        if (floorPicker) controls.addView(floors)
        frame.addView(controls, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(16); bottomMargin = dp(controlBottom)
        })
        val recenterButton = glassSymbol("location", "현재 위치로 지도 이동", 48) {
            if (recenter == null) currentLocation(map, library) else recenter(map)
        }
        frame.addView(recenterButton, FrameLayout.LayoutParams(dp(48), dp(48), (if (home) Gravity.BOTTOM else Gravity.TOP) or Gravity.END).apply {
            marginEnd = dp(if (home) 25 else 16); bottomMargin = dp(controlBottom); topMargin = dp(16)
        })
        configure(map)
        scope.launch {
            while (true) {
                delay(5000)
                if (freshPosition() == null && positionFix != null) {
                    map.showLocation(null)
                    positionListener?.invoke(null)
                }
            }
        }
        return frame
    }

    internal fun loading(message: String): View = column().apply {
        gravity = Gravity.CENTER
        pad(24)
        addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(36), dp(36)))
        gap(14)
        addView(label(message, 15, Pobook.muted).apply { gravity = Gravity.CENTER })
    }

    internal fun empty(title: String, body: String, symbol: String = "book"): View = column().apply {
        gravity = Gravity.CENTER
        pad(28, 44)
        addView(LineIcon(this@MainActivity, symbol, Pobook.muted), LinearLayout.LayoutParams(dp(47), dp(47)))
        gap(15)
        addView(label(title, 19, bold = true).apply { gravity = Gravity.CENTER })
        gap(9)
        addView(label(body, 15, Pobook.muted).apply { gravity = Gravity.CENTER })
    }

    internal fun bookCover(book: Book, fallback: Int = 0): ImageView = ImageView(this).apply {
        background = shape(Pobook.surface, 9)
        clipToOutline = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setImageResource(if (fallback == 0) R.drawable.book_cover_placeholder else fallback)
        covers.load(this, book.cover, scope)
    }

    internal fun bookRow(book: Book, subtitle: String = book.location, pick: (() -> Unit)? = null,
                         extra: View? = null): View = row().apply {
        pad(10, 9)
        addView(bookCover(book), LinearLayout.LayoutParams(dp(49), dp(70)))
        gap(12)
        fill(column().apply {
            addView(label(book.title, 16, bold = true).apply { maxLines = 2 })
            gap(4)
            addView(label(book.author, 13, Pobook.muted).apply { maxLines = 1 })
            if (subtitle.isNotEmpty()) {
                gap(4)
                addView(label(subtitle, 12, Pobook.muted))
            }
            touch(action = pick ?: { openDetail(book) })
        })
        if (extra != null) {
            gap(4)
            addView(extra)
        } else touch(action = pick ?: { openDetail(book) })
    }

    internal fun carousel(books: List<Book>): HorizontalScrollView {
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val line = row().apply { gravity = Gravity.BOTTOM; pad(16, 6, 5) }
        for (book in books) {
            val cell = column().apply {
                addView(bookCover(book), LinearLayout.LayoutParams(MATCH, dp(131)))
                gap(7)
                addView(label(book.title, 14, bold = true).apply { maxLines = 2; minLines = 2 })
                addView(label(book.author, 12, Pobook.muted).apply { maxLines = 1 })
                touch { openDetail(book) }
            }
            line.addView(cell, LinearLayout.LayoutParams(dp(95), WRAP))
            line.gap(12)
        }
        scroller.addView(line)
        return scroller
    }

    internal fun captionLink(title: String, value: String = "", action: () -> Unit) = row().apply {
        pad(12, 11)
        minimumHeight = dp(50)
        fill(label(title, 15, bold = true))
        if (value.isNotEmpty()) {
            gap(8)
            addView(label(value, 13, Pobook.muted).apply { maxWidth = dp(155) })
        }
        addView(LineIcon(this@MainActivity, "next", Pobook.muted), LinearLayout.LayoutParams(dp(24), dp(24)))
        touch(action = action)
    }

    internal fun choose(title: String, values: List<String>, onSelected: (Int) -> Unit) {
        if (values.isEmpty()) return
        AlertDialog.Builder(this).setTitle(title).setItems(values.toTypedArray()) { _, index -> onSelected(index) }
            .setNegativeButton("취소", null).show()
    }

    internal fun showDialog(title: String, message: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(positive) { _, _ -> action() }.setNegativeButton("취소", null).show()
    }

    internal fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    internal fun openWeb(url: String) {
        val safe = httpsUrl(url)
        if (safe.isEmpty()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) }
            .onFailure { toast("링크를 열 수 없어요") }
    }

    internal fun report(book: Book) {
        val address = "hellomaps@fpmaps.com"
        val body = "도서명: ${book.title}\n자료 번호: ${book.recordId}\n\n어떤 문제가 있었나요?\n"
        val mail = Uri.parse("mailto:$address?subject=${Uri.encode("Po:book 도서 위치 문의")}&body=${Uri.encode(body)}")
        runCatching { startActivity(Intent(Intent.ACTION_SENDTO, mail)) }.onFailure {
            showDialog("메일 앱을 열 수 없어요", "$address\n\n$body", "복사") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Po:book", "$address\n\n$body"))
                toast("복사했어요")
            }
        }
    }

    internal fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        root.requestFocus()
    }

    internal fun body() = column().apply { pad(16, 16, 24) }
    private fun newScreenScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val LOCATION_REQUEST = 4
        internal const val PRIVACY_POLICY = "https://link.fpmaps.com/privacy-policy-pobook"
    }
}
