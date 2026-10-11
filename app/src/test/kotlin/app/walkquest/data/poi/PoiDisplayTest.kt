package app.walkquest.data.poi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Отображаемые строки POI (история 1.9): заголовок карточки и fallback-строка
 * категории для карточки без описания (description: null в poi.json, 2026-10-05).
 */
class PoiDisplayTest {

    private fun poi(
        name: String? = null,
        nameRu: String? = null,
        category: String = "tourism=museum",
        description: String? = null,
    ) = Poi(
        osmId = "123",
        name = name,
        nameRu = nameRu,
        category = category,
        lon = 30.0,
        lat = 59.0,
        wikidata = null,
        description = description,
    )

    @Test
    fun `display name prefers osm name`() {
        assertEquals("Домик Петра I", poi(name = "Домик Петра I", nameRu = "Другое").displayName)
    }

    @Test
    fun `display name falls back to russian name`() {
        assertEquals("Озёрная", poi(nameRu = "Озёрная").displayName)
    }

    @Test
    fun `display name falls back to human readable category`() {
        assertEquals("tourism: museum", poi().displayName)
    }

    @Test
    fun `display category is human readable key-value`() {
        assertEquals("shop: outpost", poi(category = "shop=outpost").displayCategory)
    }

    @Test
    fun `display name ignores blank osm name`() {
        assertEquals("Озёрная", poi(name = "  ", nameRu = "Озёрная").displayName)
    }
}
