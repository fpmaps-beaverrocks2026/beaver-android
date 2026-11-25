package com.youngmin.pobook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.hypot

internal data class MapPoint(val x: Double, val y: Double) {
    fun distanceTo(other: MapPoint) = hypot(x - other.x, y - other.y)
    fun midpoint(other: MapPoint) = MapPoint((x + other.x) / 2, (y + other.y) / 2)

    fun distanceToSegment(start: MapPoint, end: MapPoint): Double {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val squaredLength = dx * dx + dy * dy
        if (squaredLength == 0.0) return distanceTo(start)
        val fraction = (((x - start.x) * dx + (y - start.y) * dy) / squaredLength).coerceIn(0.0, 1.0)
        return distanceTo(MapPoint(start.x + dx * fraction, start.y + dy * fraction))
    }

    companion object {
        fun geographic(longitude: Double, latitude: Double) =
            MapPoint((longitude - 129.325) * 90_000, (36.0129 - latitude) * 111_100)
        fun fromJson(value: JSONArray) = geographic(value.getDouble(0), value.getDouble(1))
    }
}

internal data class MapArea(
    val id: String,
    val floor: Int,
    val type: String,
    val category: String,
    val name: String,
    val polygons: List<List<List<MapPoint>>>,
    val line: List<MapPoint>,
    val center: MapPoint?,
) {
    fun contains(point: MapPoint): Boolean = polygons.any { polygon ->
        polygon.firstOrNull()?.let { ringContains(it, point) } == true &&
            polygon.drop(1).none { ringContains(it, point) }
    }

    private fun ringContains(ring: List<MapPoint>, point: MapPoint): Boolean {
        var inside = false
        var previous = ring.lastOrNull() ?: return false
        for (next in ring) {
            if ((next.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - next.x) * (point.y - next.y) / (previous.y - next.y) + next.x) {
                inside = !inside
            }
            previous = next
        }
        return inside
    }
}

internal data class Shelf(val fixture: MapArea, val number: String, val side: String, val from: String, val to: String, val topic: String) {
    val floor get() = fixture.floor
    val title get() = if (number.all(Char::isDigit)) "${number}번 서가 ${side}면" else number
    private val start = CallNumber.parse(from)
    private val end = CallNumber.parse(to)
    fun matches(call: CallNumber) = start != null && end != null && start <= call && (call <= end || end.prefixOf(call))
}

internal data class CallNumber(val classification: String, val number: BigDecimal, val cutters: List<String>) : Comparable<CallNumber> {
    override fun compareTo(other: CallNumber): Int {
        val classes = classification.compareTo(other.classification)
        if (classes != 0) return classes
        val numbers = number.compareTo(other.number)
        if (numbers != 0) return numbers
        for ((left, right) in cutters.zip(other.cutters)) {
            if (left == right) continue
            val leftKorean = left.first() in '가'..'힣'
            val rightKorean = right.first() in '가'..'힣'
            if (leftKorean != rightKorean) return if (leftKorean) -1 else 1
            return left.compareTo(right)
        }
        return cutters.size.compareTo(other.cutters.size)
    }

    fun prefixOf(other: CallNumber) = classification == other.classification &&
        number.compareTo(other.number) == 0 && cutters == other.cutters.take(cutters.size)

    companion object {
        private val head = Regex("^([A-Z]{1,3})\\s*([0-9]+(?:\\.[0-9]+)?)")
        private val cutter = Regex("^\\s*\\.?\\s*([\\p{L}]+[0-9]+|[가-힣]+(?=\\s*\\.))")
        private val suffix = Regex("^(?:[0-9]{4}(?:\\D|$)|[VC]\\.|권|복본)")
        fun parse(raw: String): CallNumber? {
            val value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).uppercase(Locale.ROOT)
            val match = head.find(value) ?: return null
            val parts = mutableListOf<String>()
            var tail = value.substring(match.range.last + 1)
            while (true) {
                val next = cutter.find(tail) ?: break
                parts += next.groupValues[1]
                tail = tail.substring(next.range.last + 1)
            }
            if (tail.isNotBlank() && !suffix.containsMatchIn(tail.trimStart())) return null
            return CallNumber(match.groupValues[1], match.groupValues[2].toBigDecimal(), parts)
        }
    }
}

internal data class ShelfMatch(val shelf: Shelf, val holding: Holding)
internal data class FloorPosition(val point: MapPoint, val floor: Int, val label: String)
internal data class RouteTarget(val book: Book, val shelves: List<ShelfMatch>)
internal data class RouteSegment(val from: FloorPosition, val to: FloorPosition, val points: List<MapPoint>, val kind: String, val seconds: Double) {
    val changesFloor get() = from.floor != to.floor
    val length get() = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
}
internal data class Visit(val book: Book, val target: ShelfMatch, val path: List<RouteSegment>, val start: FloorPosition) {
    val destination get() = path.lastOrNull()?.to ?: start
    val seconds get() = path.sumOf { it.seconds }
    val meters get() = path.filterNot(RouteSegment::changesFloor).sumOf { it.length }
}
internal data class RoutePlan(val visits: List<Visit>) {
    val seconds get() = visits.sumOf(Visit::seconds)
}
internal data class PlanRequest(val targets: List<RouteTarget>, val origin: FloorPosition, val accessible: Boolean)

internal enum class IndoorDataset(val assetDirectory: String, val venueName: String, val initialFloor: Int) {
    POSTECH("library", "박태준학술정보관", 3),
    SETEC("setec", "SETEC 전시장", 1),
}

internal class IndoorLibrary(context: Context, val dataset: IndoorDataset = IndoorDataset.POSTECH) {
    private val assets = context.assets
    private val mapFiles = assets.list("${dataset.assetDirectory}/IMDFData").orEmpty().toSet()

    private fun features(file: String) = assets.open("${dataset.assetDirectory}/$file").bufferedReader().use {
        JSONObject(it.readText()).getJSONArray("features").objects()
    }

    private val levels = features("IMDFData/level.geojson")
    private val floorById = levels.associate { it.getString("id") to (it.getJSONObject("properties").getInt("ordinal") + 1) }
    val floors = floorById.values.sorted()
    private val footprints = features("IMDFData/footprint.geojson").map { parse(it, "footprint") }
        .let { records -> records.filter { it.category == "ground" }.ifEmpty { records } }
    val areas = (levels.map { parse(it, "level") } + listOf("unit", "section", "fixture", "opening", "amenity")
        .filter { "$it.geojson" in mapFiles }
        .flatMap { type -> features("IMDFData/$type.geojson").map { parse(it, type) } })
        .filter { it.floor in floors }.groupBy(MapArea::floor)
    private val fixtures = areas.values.flatten().filter { it.type == "fixture" && it.name.startsWith("S-") }
    private val fixturesByName = fixtures.associateBy(MapArea::name)
    val shelves = if (dataset == IndoorDataset.POSTECH) {
        JSONArray(context.asset("library/shelfdata.json")).objects().mapNotNull { item ->
            val area = fixturesByName[item.text("fixtureNumber")] ?: return@mapNotNull null
            Shelf(area, item.text("realNumber"), item.text("side"), item.text("callStart"), item.text("callEnd"), item.text("other"))
        } + fixtures.filter { it.floor == 2 && it.name.startsWith("S-2F-") }.map {
            val name = it.name.removePrefix("S-2F-").trim()
            Shelf(it, name, "", "", "", name)
        }
    } else {
        emptyList()
    }
    val entrances = areas.values.flatten().filter { it.type == "opening" && it.category == "pedestrian.principal" }
        .mapNotNull { feature -> feature.center?.let { FloorPosition(it, feature.floor, "${feature.floor}층 출입구") } }
        .groupBy(FloorPosition::floor).values.map { openings ->
            val midpoint = MapPoint(openings.map { it.point.x }.average(), openings.map { it.point.y }.average())
            openings.minBy { it.point.distanceTo(midpoint) }
        }.sortedBy { it.floor }
    private val graph = if (dataset == IndoorDataset.POSTECH) features("routing-graph.geojson") else emptyList()
    private val nodes = graph.filter { it.optJSONObject("properties").text("role") == "node" }.associate {
        val attrs = it.getJSONObject("properties")
        it.getString("id") to FloorPosition(
            MapPoint.fromJson(it.getJSONObject("geometry").getJSONArray("coordinates")),
            floorById.getValue(attrs.getString("level_id")), attrs.text("name"),
        )
    }
    private data class Connection(val target: String, val segment: RouteSegment, val accessible: Boolean)
    private val edges = buildMap<String, MutableList<Connection>> {
        graph.filter { it.optJSONObject("properties").text("role") == "edge" }.forEach { item ->
            val attrs = item.getJSONObject("properties")
            val from = attrs.getString("from")
            val to = attrs.getString("to")
            val start = nodes.getValue(from)
            val finish = nodes.getValue(to)
            val category = attrs.getString("category")
            val points = ring(item.getJSONObject("geometry").getJSONArray("coordinates"))
            val meters = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
            val seconds = if (start.floor == finish.floor) meters / 1.3889 else
                attrs.optDouble("traversal_seconds", if (category == "elevator") 24.0 else 12.0)
            val accessible = attrs.optBoolean("accessible")
            val direction = attrs.text("direction")
            if (direction != "reverse") {
                getOrPut(from, ::mutableListOf).add(Connection(to, RouteSegment(start, finish, points, category, seconds), accessible))
            }
            if (direction != "forward") {
                getOrPut(to, ::mutableListOf).add(Connection(from, RouteSegment(finish, start, points.reversed(), category, seconds), accessible))
            }
        }
    }
    private val links = if (dataset == IndoorDataset.POSTECH) {
        JSONObject(context.asset("library/navigation-feature-links.json")).getJSONArray("features")
            .objects().associate { it.getString("feature_id") to it.getString("node_id") }
    } else {
        emptyMap()
    }

    fun inside(point: MapPoint): Boolean = footprints.any { it.contains(point) }

    fun candidates(holdings: List<Holding>): List<ShelfMatch> {
        val candidates = holdings.filter { it.institution == "82POSTECH_INST" && it.library == "MAIN" && it.availability != "unavailable" }
            .flatMap { holding -> resolve(holding).map { ShelfMatch(it, holding) } }
        val general = candidates.filter { it.holding.code.lowercase(Locale.ROOT) in generalFloors }
        return general.ifEmpty { candidates }.distinctBy { it.shelf.fixture.id }
    }

    fun resolve(holding: Holding): List<Shelf> {
        if (holding.institution != "82POSTECH_INST" || holding.library != "MAIN") return emptyList()
        val code = holding.code.lowercase(Locale.ROOT)
        val floor = generalFloors[code]
        if (floor != null) {
            val call = CallNumber.parse(holding.call) ?: return emptyList()
            return shelves.filter { it.floor == floor && it.matches(call) }
        }
        val name = Normalizer.normalize(holding.name, Normalizer.Form.NFC).uppercase(Locale.ROOT)
        val compact = name.filterNot(Char::isWhitespace)
        val topic = when {
            code == "c2n" || compact in setOf("신착서가(2층)", "신착서가(2F)") -> {
                val call = CallNumber.parse(holding.call)?.classification ?: return emptyList()
                val range = when {
                    call < "DS" -> "A-DS"
                    call < "H" -> "DS-G"
                    call < "HN" -> "H-HM"
                    call < "Q" -> "HN-P"
                    else -> return emptyList()
                }
                "신착서가 $range"
            }
            code == "c2sb2" -> "원형서가 B"
            code == "c2e" || compact in setOf("영어교양2층", "영어교양(2층)", "영어교양(2F)") -> "영어 교양 서가"
            else -> namedTopic(name) ?: return emptyList()
        }
        return shelves.filter { it.floor == 2 && it.topic == topic }
    }

    private fun namedTopic(name: String): String? {
        val circle = Regex("""(?:원형\s*서가|SPECIAL\s+COLLECTION)\s*(?:\(\s*2\s*(?:F|층)\s*\)\s*)?([AB])(?:[^A-Z]|$)""")
            .find(name)?.groupValues?.get(1)
        if (circle != null) return "원형서가 $circle"
        val display = Regex("""(?:삼각\s*서가|DISPLAY\s*SHELF)\s*([ABCD])(?:[^A-Z]|$)""").find(name)?.groupValues?.get(1)
        return display?.let { "삼각서가 ${if (it == "D") "B" else it}" }
    }

    fun plan(request: PlanRequest): RoutePlan? {
        if (request.targets.size !in 1..LibraryState.MAX_SELECTION) return null
        val start = nearestNode(request.origin, request.accessible) ?: return null
        val destinations = request.targets.associateWith { target ->
            target.shelves.mapNotNull { match -> links[match.shelf.fixture.id]?.takeIf(nodes::containsKey)?.let { it to match } }
        }
        val trees = HashMap<String, Paths>()
        var best: RoutePlan? = null

        fun explore(node: String, origin: FloorPosition, waiting: List<RouteTarget>, chosen: List<Visit>, cost: Double) {
            if (cost >= (best?.seconds ?: Double.POSITIVE_INFINITY)) return
            if (waiting.isEmpty()) {
                best = RoutePlan(chosen)
                return
            }
            val paths = trees.getOrPut(node) { shortestPaths(node, request.accessible) }
            for (target in waiting) {
                for ((end, match) in destinations.getValue(target)) {
                    val path = paths.pathTo(end) ?: continue
                    val startNode = nodes.getValue(node)
                    val approach = origin.point.distanceTo(startNode.point)
                    val segments = if (approach > 0.4) {
                        listOf(RouteSegment(origin, startNode, listOf(origin.point, startNode.point), "walkway", approach / 1.3889)) + path
                    } else path
                    val visit = Visit(target.book, match, segments, origin)
                    explore(end, nodes.getValue(end), waiting - target, chosen + visit, cost + visit.seconds)
                }
            }
        }
        explore(start, request.origin, request.targets, emptyList(), 0.0)
        return best
    }

    fun reroute(fixed: List<Visit>, origin: FloorPosition, accessible: Boolean): List<Visit>? {
        var start = origin
        return fixed.map { visit ->
            val node = nearestNode(start, accessible) ?: return null
            val end = links[visit.target.shelf.fixture.id] ?: return null
            val path = shortestPaths(node, accessible).pathTo(end) ?: return null
            val anchor = nodes.getValue(node)
            val distance = start.point.distanceTo(anchor.point)
            val approach = if (distance > 0.4) listOf(RouteSegment(start, anchor, listOf(start.point, anchor.point), "walkway", distance / 1.3889)) else emptyList()
            visit.copy(path = approach + path, start = start).also { start = nodes.getValue(end) }
        }
    }

    private fun nearestNode(origin: FloorPosition, accessible: Boolean): String? = nodes.asSequence()
        .filter { (id, point) -> point.floor == origin.floor && edges[id].orEmpty().any { !accessible || it.accessible } }
        .minByOrNull { (_, point) -> point.point.distanceTo(origin.point) }
        ?.takeIf { it.value.point.distanceTo(origin.point) <= 25.0 }?.key

    private data class QueueNode(val id: String, val cost: Double)
    private data class Previous(val id: String, val edge: RouteSegment)
    private class Paths(private val start: String, private val previous: Map<String, Previous>) {
        fun pathTo(target: String): List<RouteSegment>? {
            val segments = mutableListOf<RouteSegment>()
            var node = target
            while (node != start) {
                val entry = previous[node] ?: return null
                segments += entry.edge
                node = entry.id
            }
            return segments.asReversed()
        }
    }

    private fun shortestPaths(start: String, accessible: Boolean): Paths {
        val queue = PriorityQueue(compareBy(QueueNode::cost))
        val previous = HashMap<String, Previous>()
        val costs = hashMapOf(start to 0.0)
        queue += QueueNode(start, 0.0)
        while (queue.isNotEmpty()) {
            val next = queue.remove()
            if (next.cost != costs[next.id]) continue
            for (edge in edges[next.id].orEmpty()) {
                if (accessible && !edge.accessible) continue
                val newCost = next.cost + edge.segment.seconds
                if (newCost < (costs[edge.target] ?: Double.POSITIVE_INFINITY)) {
                    costs[edge.target] = newCost
                    previous[edge.target] = Previous(next.id, edge.segment)
                    queue += QueueNode(edge.target, newCost)
                }
            }
        }
        return Paths(start, previous)
    }

    private fun parse(feature: JSONObject, type: String): MapArea {
        val attrs = feature.getJSONObject("properties")
        val geometry = feature.optJSONObject("geometry")
        val coordinates = geometry?.optJSONArray("coordinates")
        val polygons = when (geometry.text("type")) {
            "Polygon" -> listOf(polygon(requireNotNull(coordinates)))
            "MultiPolygon" -> requireNotNull(coordinates).let { array ->
                (0 until array.length()).map { polygon(array.getJSONArray(it)) }
            }
            else -> emptyList()
        }
        val line = if (geometry.text("type") == "LineString") ring(requireNotNull(coordinates)) else emptyList()
        val center = attrs.optJSONObject("display_point")?.optJSONArray("coordinates")?.let(MapPoint::fromJson)
            ?: when {
                geometry.text("type") == "Point" -> MapPoint.fromJson(requireNotNull(coordinates))
                line.isNotEmpty() -> line.first().midpoint(line.last())
                else -> polygons.firstOrNull()?.firstOrNull()?.takeIf(List<MapPoint>::isNotEmpty)?.let { points ->
                    MapPoint(points.map(MapPoint::x).average(), points.map(MapPoint::y).average())
                }
            }
        val id = feature.getString("id")
        val floor = floorById[if (type == "level") id else attrs.text("level_id")] ?: 0
        val names = attrs.optJSONObject("name") ?: attrs.optJSONObject("alt_name")
        return MapArea(id, floor, type, attrs.text("category"), names.text("ko").ifEmpty { names.text("en") }, polygons, line, center)
    }

    private fun polygon(value: JSONArray) = (0 until value.length()).map { ring(value.getJSONArray(it)) }
    private fun ring(value: JSONArray) = (0 until value.length()).map { MapPoint.fromJson(value.getJSONArray(it)) }

    companion object {
        private val generalFloors = mapOf("c3b" to 3, "c4b" to 4)
    }
}

internal data class PositionFix(val point: MapPoint, val floor: Int?, val accuracy: Float, val timestamp: Long, val heading: Float?) {
    fun isFresh(now: Long) = now >= timestamp && now - timestamp <= 15_000 && accuracy <= 40f
}
