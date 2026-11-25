package com.youngmin.pobook

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndoorLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledFloorGraphResolvesAndRoutesTwoRealBooks() {
        val library = IndoorLibrary(context)
        val catalog = Catalog(context)
        assertEquals(listOf(1, 2, 3, 4, 5), library.floors)
        assertEquals(318, library.shelves.size)
        val books = catalog.byRecords(listOf("alma991000416829703286", "alma991003575376203286"))
        assertEquals(2, books.size)
        val targets = books.map { RouteTarget(it, library.candidates(catalog.knownHoldings(it))) }
        assertTrue(targets.all { it.shelves.isNotEmpty() })
        val origin = library.entrances.first { it.floor == 1 }
        val plan = requireNotNull(library.plan(PlanRequest(targets, origin, accessible = false)))
        assertEquals(books.map(Book::id).toSet(), plan.visits.map { it.book.id }.toSet())
        assertTrue(plan.visits.flatMap(Visit::path).any(RouteSegment::changesFloor))
        assertEquals(plan.visits.first().destination.floor, plan.visits[1].start.floor)
        assertNotNull(library.reroute(plan.visits.drop(1), origin, accessible = false))
    }

    @Test fun setecExportLoadsItsSingleFloorWithoutLibrarySpecificData() {
        val setec = IndoorLibrary(context, IndoorDataset.SETEC)
        assertEquals(listOf(1), setec.floors)
        val features = setec.areas.getValue(1)
        assertEquals(100, features.count { it.name == "게임 부스" })
        assertEquals(16, features.count { it.name == "파트너 부스" })
        assertEquals(80, features.count { it.name == "좌석" })
        assertEquals(45, features.count { it.type == "opening" })
        val stage = features.single { it.category == "stage" }
        val stageCenter = requireNotNull(stage.center)
        assertTrue(stage.contains(stageCenter))
        assertTrue(setec.inside(stageCenter))
        assertFalse(setec.inside(MapPoint.geographic(129.325, 36.0129)))
        assertTrue(setec.shelves.isEmpty())
        assertTrue(setec.entrances.isEmpty())
    }

    @Test fun eachGameAppearsOnceAndAllGameBoothsUseTheirOwnMapArea() {
        val setec = IndoorLibrary(context, IndoorDataset.SETEC)
        val gameBoothIds = setec.areas.values.flatten().filter { it.name == "게임 부스" }.map(MapArea::id).toSet()
        val catalog = ExhibitionGames(context)
        assertEquals(gameBoothIds, catalog.byAreaId.keys)
        assertEquals((1..100).toList(), catalog.booths.map(ExhibitionBooth::number))
        assertEquals(mapOf(1 to 95, 2 to 5), catalog.booths.groupingBy { it.games.size }.eachCount())
        val games = catalog.booths.flatMap(ExhibitionBooth::games)
        assertEquals((1..105).toList(), games.map(ExhibitionGame::id).sorted())
        val images = games.flatMap(ExhibitionGame::images)
        assertEquals(463, images.toSet().size)
        assertTrue(games.all { it.images.isNotEmpty() })
        for (file in images) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open("exhibition-games/$file").use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            assertTrue(file, bounds.outWidth in 1..1200 && bounds.outHeight in 1..1200)
        }
    }

    @Test fun namedShelfMappingsUseTheSecondFloorFixtures() {
        val library = IndoorLibrary(context)
        val display = Holding("82POSTECH_INST", "MAIN", "", "삼각서가 D", "", "available")
        val newest = Holding("82POSTECH_INST", "MAIN", "c2n", "", "DS1.A2", "available")
        assertEquals("삼각서가 B", library.resolve(display).single().title)
        assertEquals("신착서가 DS-G", library.resolve(newest).single().title)
        assertTrue(library.candidates(listOf(display.copy(availability = "unavailable"))).isEmpty())
    }
}
