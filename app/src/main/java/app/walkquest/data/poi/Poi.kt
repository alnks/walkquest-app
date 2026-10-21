package app.walkquest.data.poi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Точка интереса региона (история 1.9, FR-18) — доменная модель POI-маяка.
 * Источник — poi.json установленного пакета (генератор 1.3, OSM + Википедия).
 * Чистый Kotlin (AD-10): ни Android, ни MapLibre здесь не импортируется.
 */
data class Poi(
    /** Идентификатор OSM: «21626280» (узел) или «w5052616»/«r123» (путь/отношение) — всегда строка. */
    val osmId: String,
    val name: String?,
    val nameRu: String?,
    /** Категория OSM в формате генератора: «shop=outpost», «tourism=museum», … */
    val category: String,
    val lon: Double,
    val lat: Double,
    val wikidata: String?,
    /** Краткая выдержка из Википедии; может отсутствовать (деградация генератора). */
    val description: String?,
    /**
     * Фото места (пакет v2): относительный путь от корня пакета (`photos/poi-<osmId>.jpg`).
     * Поле опциональное — в пакете v1 его нет, карточка показывается без фото-полосы.
     * Если задано массивом [photos] — одиночное поле игнорируется (массив приоритетнее).
     */
    val photo: String? = null,
    /**
     * Полоса фото карточки (дизайн-эпик 7.10, AL-10): до [MAX_PHOTOS] относительных
     * путей от корня пакета, как в макете poi-card (.photos — ряд из 3 фото).
     * Поле опциональное — пакеты до v6 знают только одиночное `photo`; обратная
     * совместимость: пустой список → карточка берёт одиночное фото.
     */
    val photos: List<String> = emptyList(),
    /**
     * Ссылка на статью Википедии (пакет v5, полевой тест 3): карточка обрезает
     * длинное описание и показывает «Подробнее» → браузер. Поле опциональное —
     * в пакетах v1–v4 его нет, ссылка скрыта (обратная совместимость).
     */
    val url: String? = null,
) {
    /** Заголовок карточки: name → name_ru → человекочитаемая категория. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: nameRu?.takeIf { it.isNotBlank() }
            ?: category.replace("=", ": ")

    /**
     * Человекочитаемая категория («tourism: museum») — fallback-строка карточки,
     * когда у места нет описания (description: null в poi.json).
     */
    val displayCategory: String
        get() = category.replace("=", ": ")

    /**
     * Пути фото-полосы карточки: массив [photos] (до [MAX_PHOTOS]) или одиночное
     * `photo` как список из одного элемента; оба отсутствуют — пусто (без полосы).
     */
    val photoPaths: List<String>
        get() = photos.takeIf { it.isNotEmpty() }?.take(MAX_PHOTOS)
            ?: listOfNotNull(photo?.takeIf { it.isNotBlank() })

    companion object {
        /** Макет poi-card: ряд из трёх фото — больше в полосу не влезает. */
        const val MAX_PHOTOS = 3
    }
}

/** Корень poi.json: {"pois": [...]} — фактический формат region-spb-city-v1.zip (1.3). */
@Serializable
internal data class PoiFile(
    val pois: List<PoiJson> = emptyList(),
)

/**
 * DTO записи poi.json. Поля snake_case — контракт генератора (tools/region-pack),
 * сверены с фактическим файлом: osm_id, name, name_ru, category, lon, lat,
 * wikidata, description (последние три nullable). Пакет v2 добавил `photo`,
 * пакет v5 — `url` (Википедия), дизайн-эпик 7.10 — `photos` (полоса до 3 фото);
 * все опциональные: v4 без них парсится как прежде.
 */
@Serializable
internal data class PoiJson(
    @Serializable(with = OsmIdSerializer::class)
    @SerialName("osm_id") val osmId: String,
    val name: String? = null,
    @SerialName("name_ru") val nameRu: String? = null,
    val category: String,
    val lon: Double,
    val lat: Double,
    val wikidata: String? = null,
    val description: String? = null,
    /** Фото места (пакет v2): относительный путь от корня пакета; поле опциональное. */
    val photo: String? = null,
    /** Полоса фото (дизайн-эпик 7.10): до 3 путей; опциональное — пакеты v2–v6 без него. */
    @SerialName("photos") val photos: List<String> = emptyList(),
    /** Ссылка на Википедию (пакет v5); опциональное — в v1–v4 отсутствует. */
    val url: String? = null,
) {
    /** Категория интернируется: 514 уникальных значений на 58 580 записей пакета. */
    fun toPoi(): Poi = Poi(osmId, name, nameRu, category.intern(), lon, lat, wikidata, description, photo, photos, url)
}

/**
 * osm_id в фактическом poi.json смешанный: число для узлов OSM (52 239 записей)
 * и строка «w…»/«r…» для путей и отношений (6 341 запись). Нормализуем к строке —
 * иначе парсер падает на первом же пути (зеркало _qa/check-1-9-poi.py).
 */
internal object OsmIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OsmId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: throw SerializationException("osm_id читается только из JSON")
        return (element as? JsonPrimitive)?.content
            ?: throw SerializationException("osm_id не скаляр: $element")
    }
}
