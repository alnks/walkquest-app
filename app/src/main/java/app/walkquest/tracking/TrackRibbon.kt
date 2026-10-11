package app.walkquest.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * «Свеча v2»: рендер-раскрытие тумана БУФЕРНОЙ ТРОПОЙ вокруг линии трека
 * (разбор владельца 2026-10-06 по полевому тесту 4: цепочка кругов отвергнута —
 * «бусы-гусеница, странные узоры, радиус слишком большой»; видение: «туман
 * рассеивается ореолом от движения… открывается небольшая часть дорожки»).
 *
 * Геометрия просвета завесы — ОДНО кольцо: буфер радиуса [REVEAL_RADIUS_METERS]
 * вокруг полилинии принятых точек трека с круглыми стыками и торцами
 * ([trailRing]). Одно кольцо вместо цепочки кругов-дырок: непрерывная гладкая
 * тропа без «бус» и без артефактов триангуляции пересекающихся дырок.
 * Учёт открытия (биткарта тайлов, процент — AD-4) этим файлом НЕ меняется:
 * клетка по-прежнему открывается первой валидной точкой через OpenTileAtPoint;
 * здесь — только рендер-геометрия.
 *
 * Производительность: лента строится инкрементально ([RibbonBuilder]) с
 * дедупликацией по шагу [RIBBON_DEDUP_METERS] — буферу густые точки не нужны;
 * многотысячные журналы сжимаются до сотен вершин осевой, кольцо буфера —
 * ~10 вершин на вершину осевой (замеры — deferred-work.md, «Свеча v2»).
 *
 * Чистый Kotlin без android / MapLibre (AD-10): кольца — голые lon/lat,
 * слой карты сам заворачивает их в GeoJSON.
 */

/**
 * Радиус буфера раскрытия вокруг линии трека (половина ширины тропы), метры.
 * Константа-тюнер: владелец выбирает по скриншотам 10/15/20 м
 * (_qa/emulator-run/fix8-verify/ и fix9-verify/, spike 1.11); выбор владельца
 * fix10 (2026-10-07) — 10 м: тропа ближе к «небольшой части дорожки» из видения.
 */
const val REVEAL_RADIUS_METERS = 10.0

/** Аппроксимация круга раскрытия правильным многоугольником (16 вершин). */
const val REVEAL_CIRCLE_VERTICES = 16

/**
 * Шаг дедупликации осевой ленты, метры: точка ближе этого расстояния к
 * последней ПРИНЯТОЙ в ленту точке её не продлевает. Буферу густые точки не
 * нужны — шаг 9 м при радиусе 10 м (fix10) держит стыки плавными и кольцо коротким.
 * Константа-гипотеза, калибровка — там же, где радиус (spike 1.11).
 */
const val RIBBON_DEDUP_METERS = 9.0

/** Угловой шаг аппроксимации круглых стыков и торцевых дуг буфера, градусы. */
private const val TRAIL_ARC_DEGREES = 22.5

/**
 * Предел вытягивания среза (miter) на внутренней стороне поворота, в радиусах:
 * при почти развороте пересечение офсетов улетает далеко — тогда вместо среза
 * в кольцо идут оба конца офсетных отрезков (маленькая самопересекающаяся
 * петля; MapLibre/earcut такое переваривает — зафиксированное допущение).
 */
private const val MITER_LIMIT_RADIUS = 4.0

/**
 * Замкнутое кольцо-«круг» радиуса [radiusMeters] вокруг точки — аппроксимация
 * окружности [vertices] вершинами ([vertices] + 1 точка, первая = последняя).
 * Обход ПО ЧАСОВОЙ (север → восток → юг → запад), как у FogGrid.tileRing:
 * MapLibre (classifyRings) признаёт дыркой завесы только кольцо с ориентацией,
 * противоположной мировому экстерьеру (CCW) — регрессия дефекта D2 (2026-10-05).
 * Радиус по долготе масштабируется косинусом широты (та же метрика, что у сетки).
 *
 * Остаётся для вырожденной тропы из одной точки и для кольца точности маркера.
 */
fun circleRing(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    vertices: Int = REVEAL_CIRCLE_VERTICES,
): List<DoubleArray> {
    require(vertices >= 3) { "вершин круга должно быть ≥ 3, получено $vertices" }
    val latRadius = radiusMeters / METERS_PER_DEGREE_LAT
    val lonRadius = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)))
    return (0..vertices).map { step ->
        val angle = 2.0 * Math.PI * step / vertices
        doubleArrayOf(
            longitude + lonRadius * sin(angle),
            latitude + latRadius * cos(angle),
        )
    }
}

/**
 * Буферная тропа: одно замкнутое кольцо (lon/lat, обход CW — дырка для
 * MapLibre) вокруг полилинии [points] (дедуплицированной осевой ленты).
 * Построение в локальной метровой проекции (эквидистантной на широте старта —
 * та же метрика, что у сетки тайлов): для каждого сегмента — пара офсетных
 * линий, на внешней стороне поворота — круглая дуга (round join), на
 * внутренней — срез (miter, ограничен [MITER_LIMIT_RADIUS]), торцы — круглые
 * полукруги (round cap). Вырожденные случаи: пусто → пустое кольцо; одна
 * точка → [circleRing]. Самопересечения на резких разворотах допустимы
 * (MapLibre переваривает как часть дырки).
 */
fun trailRing(points: List<TrackPoint>, radiusMeters: Double = REVEAL_RADIUS_METERS): List<DoubleArray> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) {
        return circleRing(points[0].latitude, points[0].longitude, radiusMeters)
    }
    val origin = points[0]
    val metersPerLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(origin.latitude))
    // Осевые в метрах; нулевые сегменты (повторная точка) выкидываем.
    val xs = ArrayList<Double>(points.size)
    val ys = ArrayList<Double>(points.size)
    for (p in points) {
        val x = (p.longitude - origin.longitude) * metersPerLon
        val y = (p.latitude - origin.latitude) * METERS_PER_DEGREE_LAT
        if (xs.isNotEmpty() && abs(x - xs.last()) < 1e-6 && abs(y - ys.last()) < 1e-6) continue
        xs.add(x)
        ys.add(y)
    }
    if (xs.size == 1) return circleRing(origin.latitude, origin.longitude, radiusMeters)

    val segCount = xs.size - 1
    val dx = DoubleArray(segCount)
    val dy = DoubleArray(segCount)
    for (i in 0 until segCount) {
        val len = sqrt((xs[i + 1] - xs[i]) * (xs[i + 1] - xs[i]) + (ys[i + 1] - ys[i]) * (ys[i + 1] - ys[i]))
        dx[i] = (xs[i + 1] - xs[i]) / len
        dy[i] = (ys[i + 1] - ys[i]) / len
    }
    // Левая нормаль сегмента i: (-dy, dx); правая — с минусом.
    val left = ArrayList<DoubleArray>(xs.size * 4)
    val right = ArrayList<DoubleArray>(xs.size * 4)
    left.add(offset(xs[0], ys[0], -dy[0], dx[0], radiusMeters))
    right.add(offset(xs[0], ys[0], dy[0], -dx[0], radiusMeters))
    for (i in 1 until xs.size - 1) {
        val cross = dx[i - 1] * dy[i] - dy[i - 1] * dx[i]
        // Поворот влево (cross > 0): левая сторона внутренняя (срез), правая внешняя (дуга).
        appendJoin(left, xs[i], ys[i], -dy[i - 1], dx[i - 1], -dy[i], dx[i], radiusMeters, outer = cross < 0)
        appendJoin(right, xs[i], ys[i], dy[i - 1], -dx[i - 1], dy[i], -dx[i], radiusMeters, outer = cross > 0)
    }
    val last = xs.size - 1
    left.add(offset(xs[last], ys[last], -dy[segCount - 1], dx[segCount - 1], radiusMeters))
    right.add(offset(xs[last], ys[last], dy[segCount - 1], -dx[segCount - 1], radiusMeters))

    // Кольцо: левая сторона вперёд → торец-полукруг через «вперёд» → правая
    // сторона назад → торец-полукруг через «назад». Дуги торцов дописывают
    // только промежуточные точки (их концы — уже стоящие в кольце офсеты).
    // Такой обход уже CW (вверх по западной стороне), но ориентацию страхуем
    // площадью в конце.
    val ring = ArrayList<DoubleArray>(left.size + right.size + 2 * 8)
    ring.addAll(left)
    appendCap(ring, xs[last], ys[last], dx[segCount - 1], dy[segCount - 1], radiusMeters, forward = true)
    for (i in right.size - 1 downTo 0) ring.add(right[i])
    appendCap(ring, xs[0], ys[0], dx[0], dy[0], radiusMeters, forward = false)
    ring.add(ring.first().copyOf())

    val lonLat = ring.map { v ->
        doubleArrayOf(origin.longitude + v[0] / metersPerLon, origin.latitude + v[1] / METERS_PER_DEGREE_LAT)
    }
    return if (signedArea(lonLat) < 0.0) lonLat else lonLat.reversed()
}

/** Точка офсета: (x,y) + r × (nx,ny). */
private fun offset(x: Double, y: Double, nx: Double, ny: Double, r: Double): DoubleArray =
    doubleArrayOf(x + nx * r, y + ny * r)

/**
 * Стык на вершине осевой: внешняя сторона — дуга радиуса [r] от нормали
 * предыдущего сегмента к нормали следующего (короткая сторона, < 180°);
 * внутренняя — срез (пересечение офсетных прямых, ограничено [MITER_LIMIT_RADIUS],
 * иначе оба конца офсетов — маленькая петля, допустимая для earcut).
 */
private fun appendJoin(
    out: MutableList<DoubleArray>,
    x: Double,
    y: Double,
    nx0: Double,
    ny0: Double,
    nx1: Double,
    ny1: Double,
    r: Double,
    outer: Boolean,
) {
    val a0 = atan2(ny0, nx0)
    val a1 = atan2(ny1, nx1)
    var sweep = a1 - a0
    while (sweep > Math.PI) sweep -= 2 * Math.PI
    while (sweep < -Math.PI) sweep += 2 * Math.PI
    if (outer || abs(sweep) < 1e-9) {
        appendArc(out, x, y, r, a0, sweep)
        return
    }
    // Внутренняя сторона: пересечение прямых (P + r·n0 + t·d0) и (P + r·n1 + s·d1),
    // направление сегмента — нормаль, повёрнутая на -90°: d = (ny, -nx).
    val d0x = ny0
    val d0y = -nx0
    val d1x = ny1
    val d1y = -nx1
    val det = d0x * d1y - d0y * d1x
    if (abs(det) > 1e-9) {
        // Решаем: r·n0 + t·d0 = r·n1 + s·d1.
        val bx = r * (nx1 - nx0)
        val by = r * (ny1 - ny0)
        val t = (bx * d1y - by * d1x) / det
        val mx = r * nx0 + t * d0x
        val my = r * ny0 + t * d0y
        if (sqrt(mx * mx + my * my) <= MITER_LIMIT_RADIUS * r) {
            out.add(doubleArrayOf(x + mx, y + my))
            return
        }
    }
    // Разворот/почти прямая: оба конца офсетов (петля — допустимо).
    out.add(doubleArrayOf(x + nx0 * r, y + ny0 * r))
    out.add(doubleArrayOf(x + nx1 * r, y + ny1 * r))
}

/**
 * Торцевой полукруг вокруг конца осевой: [forward] — конец тропы (дуга через
 * направление движения), иначе — начало (дуга через «назад»). Концы дуги —
 * уже стоящие в кольце офсетные точки, поэтому дописываются только
 * промежуточные (без первой и последней).
 */
private fun appendCap(
    out: MutableList<DoubleArray>,
    x: Double,
    y: Double,
    dx: Double,
    dy: Double,
    r: Double,
    forward: Boolean,
) {
    val heading = atan2(dy, dx)
    // Конец: от левой нормали (heading+90°) по часовой через «вперёд» к правой
    // (sweep -π). Начало: от правой нормали (heading-90°) по часовой через
    // «назад» к левой (тоже sweep -π).
    val a0 = heading + (if (forward) Math.PI / 2 else -Math.PI / 2)
    val sweep = -Math.PI
    val steps = maxOf(2, ceil(abs(sweep) / Math.toRadians(TRAIL_ARC_DEGREES)).toInt())
    for (k in 1 until steps) {
        val a = a0 + sweep * k / steps
        out.add(doubleArrayOf(x + r * cos(a), y + r * sin(a)))
    }
}

/** Дуга радиуса [r] от угла [a0] на [sweep] радиан, без первой точки. */
private fun appendArc(out: MutableList<DoubleArray>, x: Double, y: Double, r: Double, a0: Double, sweep: Double) {
    if (abs(sweep) < 1e-9) {
        out.add(doubleArrayOf(x + r * cos(a0), y + r * sin(a0)))
        return
    }
    val steps = maxOf(1, ceil(abs(sweep) / Math.toRadians(TRAIL_ARC_DEGREES)).toInt())
    for (k in 1..steps) {
        val a = a0 + sweep * k / steps
        out.add(doubleArrayOf(x + r * cos(a), y + r * sin(a)))
    }
}

/** Ориентированная площадь кольца (x=lon, y=lat): > 0 — CCW, < 0 — CW. */
private fun signedArea(ring: List<DoubleArray>): Double {
    var twice = 0.0
    for (i in 0 until ring.size - 1) {
        twice += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
    }
    return twice / 2.0
}

/**
 * Инкрементальный строитель осевой ленты раскрытия: принятые точки трека (уже
 * приписанные активному региону) → дедуплицированная осевая буферной тропы.
 * Дедупликация цепочкой — против последней ПРИНЯТОЙ в ленту точки (а не
 * последней входной): серия близких точек не накапливает дрейф порога.
 * Живёт в памяти viewmodel; начальное состояние после перезапуска собирается
 * из журналов треков (TrackHistory) тем же [add] — поведение идентично живой записи.
 */
class RibbonBuilder(private val dedupMeters: Double = RIBBON_DEDUP_METERS) {

    /** Цепочки осевой (по одной на сессию записи) — тропа рисуется поцепочечно. */
    private val chains = ArrayList<MutableList<TrackPoint>>()

    /** true, пока текущая цепочка открыта для [add]; [startNewChain] закрывает. */
    private var chainOpen = false

    /**
     * Цепочки осевой в порядке формирования (дедуплицированные точки).
     * Разделение по сессиям — полевой баг 2026-10-08: единая полилиния соединяла
     * разрозненные записи (разные дни/места) прямыми лентами через весь регион.
     */
    val chainsPoints: List<List<TrackPoint>> get() = chains

    /** Осевая лента плоско — все цепочки подряд (совместимость вызовов). */
    val points: List<TrackPoint> get() = chains.flatten()

    /**
     * Закрывает текущую цепочку: следующая точка [add] откроет новую.
     * Зовётся на границе сессий (старт новой записи, следующий журнал при replay).
     */
    fun startNewChain() {
        chainOpen = false
    }

    /**
     * Добавляет точку, если она дальше [dedupMeters] от последней точки ТЕКУЩЕЙ
     * цепочки. @return true, если осевая продлена (геометрия тропы изменилась).
     */
    fun add(point: TrackPoint): Boolean {
        val current: MutableList<TrackPoint> = when {
            chains.isEmpty() -> ArrayList<TrackPoint>().also { chains.add(it) }
            !chainOpen && chains.last().isNotEmpty() -> ArrayList<TrackPoint>().also { chains.add(it) }
            else -> chains.last()
        }
        chainOpen = true
        val last = current.lastOrNull()
        if (last != null && haversineMeters(last, point) < dedupMeters) return false
        current.add(point)
        return true
    }

    /** Массовое восстановление из журналов; @return число добавленных точек. */
    fun addAll(points: Iterable<TrackPoint>): Int {
        var added = 0
        for (point in points) if (add(point)) added += 1
        return added
    }

    /** Сброс (смена активного региона): лента строится заново. */
    fun reset() {
        chains.clear()
        chainOpen = false
    }
}

/** Метров в градусе широты (та же константа, что у сетки генератора, 111320). */
private const val METERS_PER_DEGREE_LAT = 111_320.0
