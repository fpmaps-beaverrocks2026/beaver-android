package com.youngmin.pobook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2

internal data class MapPin(val point: MapPoint, val floor: Int, val label: String, val icon: Int, val click: (() -> Unit)? = null)

internal class IndoorMapView(context: Context, private val library: IndoorLibrary, initialFloor: Int,
                             private val home: Boolean = false) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val graphics = HashMap<Int, Bitmap>()
    private val exhibition = library.dataset == IndoorDataset.SETEC
    private val libraryHome = home && !exhibition
    private val geometry = library.areas.mapValues { (_, areas) ->
        val layerOrder = if (exhibition) {
            areas.sortedWith(compareBy<MapArea> { area ->
                when (area.type) {
                    "level" -> 0
                    "unit" -> 1
                    "section" -> 2
                    "fixture" -> 3
                    else -> 4
                }
            }.thenByDescending { it.polygonArea() })
        } else areas
        layerOrder.map { it to it.asPath() }
    }
    private val exhibitionLabels = if (exhibition) {
        library.areas.mapValues { (_, areas) ->
            areas.filter { area -> area.name.isNotBlank() && !area.category.startsWith("restroom") &&
                (area.type in setOf("unit", "section") || area.category == "stage")
            }.sortedByDescending { it.polygonArea() }
        }
    } else emptyMap()
    private val floorFrame = library.areas.mapValues { (_, areas) ->
        val points = areas.first { it.type == "level" }.polygons.flatten().flatten()
        val minX = points.minOf(MapPoint::x)
        val maxX = points.maxOf(MapPoint::x)
        val minY = points.minOf(MapPoint::y)
        val maxY = points.maxOf(MapPoint::y)
        Frame(MapPoint((minX + maxX) / 2, (minY + maxY) / 2), maxX - minX, maxY - minY)
    }
    private data class Frame(val center: MapPoint, val width: Double, val height: Double)
    private var center = floorFrame.getValue(initialFloor).center
    private var zoom = 1f
    private var viewportScale = 1f
    private var openAtShelfScale = libraryHome
    var showAmenities = false
        set(value) { field = value; invalidate() }
    var exhibitionBooths: Map<String, ExhibitionBooth> = emptyMap()
        set(value) { field = value; invalidate() }
    var highlightedBooth: String? = null
        set(value) { field = value; updateDescription(); invalidate() }
    private data class ShelfMark(val point: MapPoint, val number: String, val angle: Float)
    private val shelfMarks by lazy {
        library.shelves.groupBy { it.floor to it.number }.mapNotNull { (key, shelves) ->
            val points = shelves.flatMap { it.fixture.polygons.flatten().flatten() }
            if (points.isEmpty()) return@mapNotNull null
            val midpoint = MapPoint(
                (points.minOf(MapPoint::x) + points.maxOf(MapPoint::x)) / 2,
                (points.minOf(MapPoint::y) + points.maxOf(MapPoint::y)) / 2,
            )
            val xx = points.sumOf { (it.x - midpoint.x) * (it.x - midpoint.x) }
            val yy = points.sumOf { (it.y - midpoint.y) * (it.y - midpoint.y) }
            val xy = points.sumOf { (it.x - midpoint.x) * (it.y - midpoint.y) }
            key.first to ShelfMark(midpoint, key.second, Math.toDegrees(atan2(2 * xy, xx - yy) / 2).toFloat())
        }.groupBy({ it.first }, { it.second })
    }
    private var user: PositionFix? = null
    var customPins: List<MapPin> = emptyList()
        set(value) { field = value; invalidate() }
    var visits: List<Visit> = emptyList()
        set(value) { field = value; invalidate() }
    var selectedShelves: List<Shelf> = emptyList()
        set(value) { field = value; invalidate() }
    var onFloorChanged: ((Int) -> Unit)? = null
    var onCameraInteraction: (() -> Unit)? = null
    fun visibleCenter(): MapPoint = unproject(width / 2f, height / 2f)
    var onShelfTapped: ((Shelf) -> Unit)? = null
    var onAreaTapped: ((MapArea) -> Unit)? = null
    var floor = initialFloor
        private set

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val point = unproject(detector.focusX, detector.focusY)
            zoom = (zoom * detector.scaleFactor).coerceIn(0.8f, 12f)
            val moved = unproject(detector.focusX, detector.focusY)
            center = MapPoint(center.x + point.x - moved.x, center.y + point.y - moved.y)
            constrainCenter()
            invalidate()
            onCameraInteraction?.invoke()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scaleDetector.isInProgress) {
                center = MapPoint(center.x + distanceX / scale(), center.y + distanceY / scale())
                constrainCenter()
                invalidate()
                onCameraInteraction?.invoke()
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            center = unproject(e.x, e.y)
            zoom = (zoom * 1.7f).coerceAtMost(12f)
            constrainCenter()
            invalidate()
            onCameraInteraction?.invoke()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val small = 20 * density
            val points = customPins.filter { it.floor == floor && it.click != null }
            val pin = points.firstOrNull { abs(screenX(it.point) - e.x) < small && abs(screenY(it.point) - e.y) < small }
            if (pin != null) {
                performClick()
                pin.click?.invoke()
                return true
            }
            if ((zoom >= 1.6f || density / scale() < 0.11f) && onShelfTapped != null) {
                val point = unproject(e.x, e.y)
                library.shelves.firstOrNull { it.floor == floor && it.fixture.contains(point) }?.let {
                    performClick()
                    onShelfTapped?.invoke(it)
                    return true
                }
            }
            val areaAction = onAreaTapped
            if (areaAction != null) {
                val point = unproject(e.x, e.y)
                val feature = geometry[floor].orEmpty().asReversed().firstOrNull { (area, _) ->
                    area.name.isNotBlank() && area.type in setOf("unit", "section", "fixture") && area.contains(point)
                }?.first
                if (feature != null) {
                    performClick()
                    areaAction(feature)
                    return true
                }
            }
            return super.onSingleTapConfirmed(e)
        }
    })

    init {
        isFocusable = true
        updateDescription()
    }

    fun setFloor(value: Int, restoreBounds: Boolean = true) {
        if (value !in library.floors) return
        val changed = floor != value
        floor = value
        if (changed) openAtShelfScale = false
        if (restoreBounds) {
            center = floorFrame.getValue(value).center
            zoom = 1f
        }
        fitViewport()
        if (changed) onFloorChanged?.invoke(value)
        updateDescription()
        invalidate()
    }

    fun show(point: MapPoint, level: Int, magnification: Float) {
        setFloor(level, restoreBounds = false)
        center = point
        zoom = magnification
        constrainCenter()
        invalidate()
    }

    fun showVisit(visit: Visit, level: Int = visit.start.floor) {
        setFloor(level)
        val points = visit.path.filter { !it.changesFloor && it.from.floor == level }.flatMap(RouteSegment::points)
        if (points.isEmpty()) return
        val x0 = points.minOf(MapPoint::x)
        val x1 = points.maxOf(MapPoint::x)
        val y0 = points.minOf(MapPoint::y)
        val y1 = points.maxOf(MapPoint::y)
        val frame = floorFrame.getValue(level)
        val magnification = minOf(frame.width / (x1 - x0 + 16), frame.height / (y1 - y0 + 10)).toFloat().coerceIn(1f, 3.5f)
        show(MapPoint((x0 + x1) / 2, (y0 + y1) / 2), level, magnification)
    }

    fun showLocation(fix: PositionFix?) {
        user = if (exhibition) {
            fix?.takeIf { library.inside(it.point) }?.copy(floor = library.floors.singleOrNull())
        } else fix
        updateDescription()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fitViewport()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun scale() = viewportScale * zoom
    private fun originX() = width / 2f + if (libraryHome) 38.5f * density else 0f
    private fun originY() = height / 2f + if (libraryHome) 96f * density else 0f
    private fun screenX(point: MapPoint) = originX() + ((point.x - center.x) * scale()).toFloat()
    private fun screenY(point: MapPoint) = originY() + ((point.y - center.y) * scale()).toFloat()
    private fun unproject(x: Float, y: Float) = MapPoint(center.x + (x - originX()) / scale(), center.y + (y - originY()) / scale())
    private fun constrainCenter() {
        val frame = floorFrame.getValue(floor)
        center = MapPoint(center.x.coerceIn(frame.center.x - frame.width / 2 - 15, frame.center.x + frame.width / 2 + 15),
            center.y.coerceIn(frame.center.y - frame.height / 2 - 15, frame.center.y + frame.height / 2 + 15))
    }
    private fun fitViewport() {
        if (width == 0 || height == 0) return
        val frame = floorFrame.getValue(floor)
        viewportScale = if (openAtShelfScale) density / 0.08f else
            minOf(width * (if (libraryHome) 0.71 else 0.92) / frame.width,
                height * (if (home) 0.65 else 0.88) / frame.height).toFloat().coerceAtLeast(0.1f)
    }

    private fun updateDescription() {
        val booth = highlightedBooth?.let(exhibitionBooths::get)
        val location = if (user?.floor == floor) ", 현재 위치 표시" else ""
        val selected = if (booth != null) ", ${booth.code} 게임 부스 표시" else ""
        contentDescription = "${library.dataset.venueName} ${floor}층 실내 지도$location$selected"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (home) 0xFF344050.toInt() else 0xFF252B31.toInt())
        canvas.save()
        canvas.translate(originX(), originY())
        canvas.scale(scale(), scale())
        canvas.translate(-center.x.toFloat(), -center.y.toFloat())
        for ((area, path) in geometry[floor].orEmpty()) {
            if (path.isEmpty) continue
            if (area.type != "opening") {
                paint.style = Paint.Style.FILL
                paint.color = fill(area)
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.color = if (area.type == "opening") gray(0x62) else gray(0x1D)
            paint.strokeWidth = (if (area.type == "level") 1.2f else 0.55f) * density / scale()
            canvas.drawPath(path, paint)
            if (area.id == highlightedBooth) {
                paint.style = Paint.Style.FILL
                paint.color = 0xB0DDF53C.toInt()
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.25f * density / scale()
                paint.color = Pobook.accent
                canvas.drawPath(path, paint)
            }
        }
        for (shelf in selectedShelves.filter { it.floor == floor }) {
            paint.style = Paint.Style.FILL
            paint.color = 0xAADDF53C.toInt()
            canvas.drawPath(shelf.fixture.asPath(), paint)
        }
        for ((index, visit) in visits.withIndex()) {
            var walked = Path()
            var previous: MapPoint? = null
            fun drawWalk() {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 3.2f * density / scale()
                paint.color = if (index == 0) Pobook.accent else 0xFF95A338.toInt()
                paint.pathEffect = DashPathEffect(floatArrayOf(0.25f, 6.2f * density / scale()), 0f)
                canvas.drawPath(walked, paint)
                paint.pathEffect = null
                walked = Path()
            }
            for (segment in visit.path) {
                if (segment.changesFloor || segment.from.floor != floor) {
                    if (previous != null) drawWalk()
                    previous = null
                    continue
                }
                if (previous != segment.points.first()) {
                    walked.moveTo(segment.points.first().x.toFloat(), segment.points.first().y.toFloat())
                }
                for (point in segment.points.drop(1)) walked.lineTo(point.x.toFloat(), point.y.toFloat())
                previous = segment.points.last()
            }
            if (previous != null) drawWalk()
        }
        canvas.restore()

        if (exhibition) drawExhibitionLabels(canvas)
        if (!exhibition && density / scale() <= 0.105f) {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Pobook.font(context, false)
            paint.color = 0xFFDBDBDB.toInt()
            paint.textSize = 14f * density
            paint.setShadowLayer(density, 0f, 0f, Color.BLACK)
            for (shelf in shelfMarks[floor].orEmpty()) {
                if (!home && !shelf.number.all(Char::isDigit)) continue
                val x = screenX(shelf.point)
                val y = screenY(shelf.point)
                if (x !in -35f..width + 35f || y !in -35f..height + 35f) continue
                canvas.save()
                canvas.rotate(shelf.angle, x, y)
                canvas.drawText(shelf.number, x, y - (paint.ascent() + paint.descent()) / 2, paint)
                canvas.restore()
            }
            paint.clearShadowLayer()
        }

        val occupied = ArrayList<MapPoint>()
        val activeMarkers = library.areas[floor].orEmpty().filter {
            it.type == "amenity" || (it.type == "unit" && markerIcon(it.category) != null &&
                it.category !in listOf("stairs", "elevator") && (exhibition || !it.category.startsWith("restroom")))
        }
        val groupedElevators = HashSet<String>()
        for (marker in activeMarkers) {
            val icon = markerIcon(marker.category) ?: continue
            if (home && !showAmenities && marker.category !in listOf("stairs", "elevator")) continue
            var p = marker.center ?: continue
            if (home && marker.category == "elevator") {
                if (!groupedElevators.add(marker.id)) continue
                val partners = activeMarkers.filter {
                    it.category == "elevator" && it.id != marker.id && it.id !in groupedElevators &&
                        (it.center?.distanceTo(p) ?: Double.MAX_VALUE) * scale() < 44 * density
                }
                if (partners.isNotEmpty()) {
                    val cluster = listOf(p) + partners.mapNotNull(MapArea::center)
                    p = MapPoint(cluster.map(MapPoint::x).average(), cluster.map(MapPoint::y).average())
                    groupedElevators.addAll(partners.map(MapArea::id))
                }
            }
            if (occupied.any { it.distanceTo(p) * scale() < (if (home) 27 else 15) * density }) continue
            occupied += p
            drawPin(canvas, p, icon, if (home || zoom > 1.8f) 22 else 17)
            if (home && density / scale() <= 0.15f) {
                val title = marker.name.ifEmpty { if (marker.category == "elevator") "엘리베이터" else "계단" }
                paint.color = Pobook.secondary
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 14 * density
                paint.typeface = Pobook.font(context, false)
                paint.setShadowLayer(1.5f * density, 0f, 0f, Color.BLACK)
                canvas.drawText(title, screenX(p), screenY(p) + 30 * density, paint)
                paint.clearShadowLayer()
            }
        }
        for (visit in visits) {
            if (visit == visits.first() && visit.start.floor == floor) drawPin(canvas, visit.start.point, R.drawable.map_start, 27)
            if (visit.destination.floor == floor) drawPin(canvas, visit.destination.point, R.drawable.map_end, 31)
            for (transition in visit.path.filter { it.changesFloor && it.from.floor == floor }) {
                drawPin(canvas, transition.from.point,
                    if (transition.to.floor > floor) R.drawable.map_up else R.drawable.map_down, 32)
            }
        }
        customPins.filter { it.floor == floor }.forEach { drawPin(canvas, it.point, it.icon, 30) }
        user?.takeIf { it.floor == null || it.floor == floor }?.let { fix ->
            val x = screenX(fix.point)
            val y = screenY(fix.point)
            if (fix.heading != null) {
                paint.style = Paint.Style.FILL
                paint.color = 0xAA5898FF.toInt()
                val arrow = Path().apply {
                    moveTo(x, y - 22 * density)
                    lineTo(x - 7 * density, y - 5 * density)
                    lineTo(x + 7 * density, y - 5 * density)
                    close()
                }
                canvas.save()
                canvas.rotate(fix.heading, x, y)
                canvas.drawPath(arrow, paint)
                canvas.restore()
            }
            drawPin(canvas, fix.point, R.drawable.map_user, 25)
        }
    }

    private fun drawExhibitionLabels(canvas: Canvas) {
        val labels = exhibitionLabels[floor].orEmpty()
        val selected = labels.firstOrNull { it.id == highlightedBooth }
        val labelAreas = if (selected != null) listOf(selected) + labels.filterNot { it.id == selected.id } else labels
        val occupied = mutableListOf<android.graphics.RectF>()
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Pobook.font(context, true)
        paint.textSize = 11f * density
        paint.setShadowLayer(2f * density, 0f, 0f, Pobook.bg)
        val boothLabels = setOf("게임 부스", "파트너 부스")
        for (area in labelAreas) {
            val booth = exhibitionBooths[area.id]
            val isBooth = area.name in boothLabels
            val highlighted = area.id == highlightedBooth
            if (isBooth && zoom < 2.8f && !highlighted) continue
            val label = booth?.code ?: area.name
            val point = area.center ?: continue
            val x = screenX(point)
            val y = screenY(point)
            val textWidth = paint.measureText(label) / 2f + 4 * density
            val box = android.graphics.RectF(x - textWidth, y - 8 * density, x + textWidth, y + 8 * density)
            if (!box.intersect(0f, 0f, width.toFloat(), height.toFloat()) ||
                occupied.any { android.graphics.RectF.intersects(it, box) }) continue
            occupied += box
            paint.color = when {
                highlighted -> Pobook.bg
                area.category == "stage" -> Pobook.accent
                else -> Pobook.text
            }
            if (highlighted) paint.clearShadowLayer()
            canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint)
            if (highlighted) paint.setShadowLayer(2f * density, 0f, 0f, Pobook.bg)
        }
        paint.clearShadowLayer()
    }

    private fun MapArea.polygonArea(): Double = polygons.sumOf { polygon ->
        polygon.firstOrNull()?.let { ring ->
            abs(ring.zipWithNext().sumOf { (a, b) -> a.x * b.y - b.x * a.y }) / 2
        } ?: 0.0
    }

    private fun drawPin(canvas: Canvas, point: MapPoint, id: Int, size: Int) {
        val bitmap = graphics.getOrPut(id) {
            val drawable = requireNotNull(context.getDrawable(id))
            Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { image -> drawable.setBounds(0, 0, image.width, image.height); drawable.draw(Canvas(image)) }
        }
        val x = screenX(point)
        val y = screenY(point)
        val half = size * density / 2
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(x - half, y - half, x + half, y + half), paint)
    }

    private fun markerIcon(category: String): Int? = when {
        category == "stairs" -> R.drawable.map_stairs
        category == "elevator" -> R.drawable.map_elevator
        category.startsWith("restroom") -> R.drawable.map_toilet
        category == "businesscenter" -> R.drawable.map_printer
        category == "cafe" -> R.drawable.map_cafe
        category == "recreation" -> R.drawable.map_game
        category == "lounge" -> R.drawable.map_lounge
        category == "conferenceroom" -> R.drawable.map_conference
        category == "meetingroom" -> R.drawable.map_study
        category == "conveniencestore" -> R.drawable.map_store
        category == "mothersroom" -> R.drawable.map_nursing
        category == "drinkingfountain" -> R.drawable.map_water
        category == "information" || category == "reception.desk" -> R.drawable.map_desk
        else -> null
    }

    private fun fill(area: MapArea): Int {
        if (exhibition) {
            return when {
                area.category == "stage" -> 0xFF6B792C.toInt()
                area.category == "entertainmentarea.performance" -> 0xFF444835.toInt()
                area.name == "게임 부스" -> 0xFF505C66.toInt()
                area.name == "파트너 부스" -> 0xFF5C586B.toInt()
                area.name.startsWith("제") && area.name.endsWith("전시실") -> 0xFF414A52.toInt()
                area.category.startsWith("restroom") -> 0xFF3D5260.toInt()
                area.name == "좌석" -> 0xFF777C82.toInt()
                else -> standardFill(area)
            }
        }
        return standardFill(area)
    }

    private fun standardFill(area: MapArea): Int = gray(when {
        area.type == "level" -> 0x35
        area.category == "structure" || area.category == "wall" -> 0x59
        area.category in setOf("walkway", "opentobelow", "recreation", "lounge") -> 0x20
        area.category in setOf("stairs", "elevator", "nonpublic") -> 0x54
        area.type == "fixture" || area.category.startsWith("restroom") -> 0x55
        else -> 0x35
    })
    private fun gray(value: Int) = Color.rgb(value, value, value)

    private fun MapArea.asPath() = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        for (polygon in polygons) for (ring in polygon) {
            if (ring.isEmpty()) continue
            moveTo(ring.first().x.toFloat(), ring.first().y.toFloat())
            for (point in ring.drop(1)) lineTo(point.x.toFloat(), point.y.toFloat())
            close()
        }
        if (line.isNotEmpty()) {
            moveTo(line.first().x.toFloat(), line.first().y.toFloat())
            for (point in line.drop(1)) lineTo(point.x.toFloat(), point.y.toFloat())
        }
    }
}
