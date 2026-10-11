package app.walkquest.data.poi

/**
 * Прямоугольная область в гео-координатах (видимый viewport карты).
 * Своя модель вместо MapLibre LatLngBounds — AD-10: фильтр чисто JVM-тестируем.
 */
data class PoiBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon

    /** Область, расширенная на [fraction] своего размера в каждую сторону (запас за край экрана). */
    fun expanded(fraction: Double): PoiBounds {
        val dLat = (maxLat - minLat) * fraction
        val dLon = (maxLon - minLon) * fraction
        return PoiBounds(minLat - dLat, maxLat + dLat, minLon - dLon, maxLon + dLon)
    }
}

/**
 * Выдача POI в слой карты (история 1.9) — зафиксированное решение по 58k POI пакета.
 *
 * Все точки в слой не грузятся: выдача тирами по зуму и видимой области с лимитом.
 *   тир 1 «маяки-ориентиры» (есть wikidata или описание) — с любого зума;
 *   тир 2 «культура/природа» (tourism/historic/leisure/natural) — с [CULTURE_ZOOM];
 *   тир 3 «всё остальное» (shop/amenity/…) — с [ALL_ZOOM].
 * Область расширяется на [BOUNDS_MARGIN], чтобы маркеры не вспыхивали на краю экрана.
 * Переполнение [MAX_RESULTS] — срез по приоритету тиров: маяки выживают всегда.
 *
 * Чистый Kotlin (AD-10): JVM-тесты гоняют всю матрицу выдачи.
 */
object PoiFilter {

    /** Зум, с которого показываются тиры 1–2. */
    const val CULTURE_ZOOM = 13.0

    /** Зум, с которого показываются все категории. */
    const val ALL_ZOOM = 15.0

    /** Верхний лимит маркеров в одной выдаче (слой остаётся лёгким на любом зуме). */
    const val MAX_RESULTS = 800

    /** Запас видимой области за край экрана (доля размера области в каждую сторону). */
    const val BOUNDS_MARGIN = 0.2

    /** Ключи категорий тира 2 (часть category до «=»). */
    private val CULTURE_KEYS = setOf("tourism", "historic", "leisure", "natural")

    /** Тир видимости: 1 — маяк с wikidata/описанием; 2 — культура/природа; 3 — остальное. */
    fun tierOf(poi: Poi): Int = when {
        !poi.wikidata.isNullOrBlank() || !poi.description.isNullOrBlank() -> 1
        poi.category.substringBefore('=') in CULTURE_KEYS -> 2
        else -> 3
    }

    /**
     * Выборка POI для слоя: тиры, разрешённые зумом, в пределах [bounds] (+ запас),
     * не более [maxResults] с приоритетом низших тиров при переполнении.
     */
    fun selectPois(
        pois: List<Poi>,
        bounds: PoiBounds,
        zoom: Double,
        maxResults: Int = MAX_RESULTS,
    ): List<Poi> {
        val maxTier = when {
            zoom >= ALL_ZOOM -> 3
            zoom >= CULTURE_ZOOM -> 2
            else -> 1
        }
        val area = bounds.expanded(BOUNDS_MARGIN)
        val inArea = pois.filter { tierOf(it) <= maxTier && area.contains(it.lat, it.lon) }
        if (inArea.size <= maxResults) return inArea
        return inArea.sortedBy { tierOf(it) }.take(maxResults)
    }
}
