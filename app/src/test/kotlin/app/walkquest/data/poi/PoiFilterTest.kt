package app.walkquest.data.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выдача POI-маяков в слой (история 1.9, зафиксированное решение по 58k POI):
 * тиры по зуму (1 — wikidata/описание с любого; 2 — tourism/historic/leisure/natural
 * с 13; 3 — всё с 15), видимая область с запасом 20%, лимит с приоритетом тиров.
 * Покрывает матрицу истории: старт/зум к району/уличный зум, переполнение, пустота.
 */
class PoiFilterTest {

    private fun poi(
        osmId: String = "1",
        category: String = "shop=outpost",
        lat: Double = 59.93,
        lon: Double = 30.33,
        wikidata: String? = null,
        description: String? = null,
    ) = Poi(osmId, "name-$osmId", null, category, lon, lat, wikidata, description)

    private val bounds = PoiBounds(minLat = 59.9, maxLat = 60.0, minLon = 30.2, maxLon = 30.4)

    // --- тиры ----------------------------------------------------------------

    @Test
    fun `poi with wikidata is tier 1`() {
        assertEquals(1, PoiFilter.tierOf(poi(wikidata = "Q101093708")))
    }

    @Test
    fun `poi with description only is tier 1`() {
        assertEquals(1, PoiFilter.tierOf(poi(description = "Памятник")))
    }

    @Test
    fun `culture categories are tier 2`() {
        listOf("tourism=museum", "historic=memorial", "leisure=park", "natural=beach").forEach {
            assertEquals(it, 2, PoiFilter.tierOf(poi(category = it)))
        }
    }

    @Test
    fun `shop and amenity are tier 3`() {
        listOf("shop=outpost", "amenity=cafe", "amenity=pharmacy").forEach {
            assertEquals(it, 3, PoiFilter.tierOf(poi(category = it)))
        }
    }

    @Test
    fun `culture category with description promotes to tier 1`() {
        assertEquals(1, PoiFilter.tierOf(poi(category = "tourism=museum", description = "d")))
    }

    // --- пороги зума -----------------------------------------------------------

    private val tier1 = poi(osmId = "1", wikidata = "Q1")
    private val tier2 = poi(osmId = "2", category = "tourism=museum")
    private val tier3 = poi(osmId = "3")
    private val all = listOf(tier1, tier2, tier3)

    @Test
    fun `overview zoom yields only tier 1 beacons`() {
        assertEquals(listOf(tier1), PoiFilter.selectPois(all, bounds, zoom = 10.0))
        assertEquals(listOf(tier1), PoiFilter.selectPois(all, bounds, zoom = 12.99))
    }

    @Test
    fun `zoom 13 adds culture tier`() {
        assertEquals(listOf(tier1, tier2), PoiFilter.selectPois(all, bounds, zoom = 13.0))
        assertEquals(listOf(tier1, tier2), PoiFilter.selectPois(all, bounds, zoom = 14.9))
    }

    @Test
    fun `street zoom 15 yields everything`() {
        assertEquals(all, PoiFilter.selectPois(all, bounds, zoom = 15.0))
        assertEquals(all, PoiFilter.selectPois(all, bounds, zoom = 18.0))
    }

    // --- видимая область --------------------------------------------------------

    @Test
    fun `poi outside bounds is excluded`() {
        val far = poi(osmId = "9", lat = 61.0, lon = 31.0, wikidata = "Q9")
        assertTrue(PoiFilter.selectPois(listOf(far), bounds, zoom = 16.0).isEmpty())
    }

    @Test
    fun `bounds margin keeps poi just outside the edge`() {
        // Запас 20% от высоты 0.1° → 0.02°: точка на 0.01° севернее края ещё видна.
        val edge = poi(osmId = "9", lat = 60.01, lon = 30.3, wikidata = "Q9")
        assertEquals(listOf(edge), PoiFilter.selectPois(listOf(edge), bounds, zoom = 16.0))
    }

    @Test
    fun `poi beyond the margin is excluded`() {
        val beyond = poi(osmId = "9", lat = 60.03, lon = 30.3, wikidata = "Q9")
        assertTrue(PoiFilter.selectPois(listOf(beyond), bounds, zoom = 16.0).isEmpty())
    }

    // --- лимит и приоритет -------------------------------------------------------

    @Test
    fun `overflow keeps tier priority — beacons survive`() {
        val beacons = (1..10).map { poi(osmId = it.toString(), wikidata = "Q$it") }
        val mass = (100..1200).map { poi(osmId = it.toString()) }
        val selected = PoiFilter.selectPois(beacons + mass, bounds, zoom = 16.0, maxResults = 100)
        assertEquals(100, selected.size)
        assertTrue(selected.containsAll(beacons))
    }

    @Test
    fun `under the cap everything passes unsorted`() {
        val pois = listOf(tier3, tier1)
        assertEquals(pois, PoiFilter.selectPois(pois, bounds, zoom = 16.0))
    }

    @Test
    fun `empty input and empty area yield empty selection`() {
        assertTrue(PoiFilter.selectPois(emptyList(), bounds, zoom = 16.0).isEmpty())
        val nowhere = PoiBounds(0.0, 0.01, 0.0, 0.01)
        assertTrue(PoiFilter.selectPois(all, nowhere, zoom = 16.0).isEmpty())
    }

    // --- displayName (карточка: строка «POI без названия») -----------------------

    @Test
    fun `display name falls back to name_ru then readable category`() {
        assertEquals("Эрмитаж", poi().copy(name = null, nameRu = "Эрмитаж").displayName)
        assertEquals("shop: outpost", poi().copy(name = null, nameRu = null).displayName)
        assertEquals("name-1", poi().displayName)
        assertFalse(poi().copy(name = " ").displayName.isBlank())
    }
}
