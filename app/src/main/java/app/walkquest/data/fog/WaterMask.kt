package app.walkquest.data.fog

import java.util.BitSet

/**
 * Водяная маска пакета (разбор владельца 2026-10-05: «вода видна сквозь туман»).
 *
 * Пакет может нести опциональный `water.grid` — формат идентичен walkability.grid v0
 * (JSON-заголовок + row-major lsb-first битпак), бит 1 = водяной тайл. Файла нет
 * (пакеты v1/v2 без воды) → пустая маска, поведение тумана как прежде
 * (обратная совместимость). Водяные тайлы исключаются из завесы тумана: Нева,
 * залив и каналы видны всегда как ориентиры, суша остаётся под туманом.
 * Процент открытия маска не трогает: знаменатель — walkability, как было (AD-4).
 *
 * Чистый JVM-код (AD-10): геометрия прогонов тестируется без Android.
 */

/** Непрерывный прогон водяных тайлов одной строки сетки: колонки [colStart]..[colEnd] включительно. */
data class RowRun(val row: Int, val colStart: Int, val colEnd: Int)

/**
 * Отрезок границы «вода/суша» в целых координатах сетки (узлы решётки):
 * от (c1, r1) до (c2, r2), вертикальный (c1 == c2) или горизонтальный (r1 == r2).
 * Слитые прогоны границ — геометрия мягкой кромки воды (blur-линия в слое карты);
 * рисовать кольца прогонов целиком нельзя: смежные прогоны дали бы двойные линии
 * внутри водоёма.
 */
data class GridEdge(val c1: Int, val r1: Int, val c2: Int, val r2: Int)

/**
 * Слияние водяной маски в прогоны по строкам: каждый прогон — один прямоугольный
 * просвет завесы вместо дырки на каждый тайл (Нева — сотни тайлов, но десятки
 * прогонов: GeoJSON слоя тумана остаётся лёгким).
 */
fun BitSet.toRowRuns(cols: Int, tileCount: Int): List<RowRun> {
    val runs = ArrayList<RowRun>()
    var i = nextSetBit(0)
    while (i >= 0 && i < tileCount) {
        val row = i / cols
        val colStart = i % cols
        var colEnd = colStart
        var j = nextSetBit(i + 1)
        while (j in 0 until tileCount && j / cols == row && j % cols == colEnd + 1) {
            colEnd++
            j = nextSetBit(j + 1)
        }
        runs += RowRun(row, colStart, colEnd)
        i = j
    }
    return runs
}

/**
 * Граница водяной маски слитыми отрезками (горизонтальные прогоны по колонкам,
 * вертикальные — по строкам). Граница проходит между водяным и сухим тайлами;
 * край сетки тоже считается границей, если крайний тайл водяной.
 */
fun BitSet.toBoundaryEdges(cols: Int, rows: Int): List<GridEdge> {
    fun isWater(col: Int, row: Int): Boolean =
        col in 0 until cols && row in 0 until rows && get(row * cols + col)
    val edges = ArrayList<GridEdge>()
    // Вертикальные границы между колонками c-1 и c, слитые по строкам.
    for (c in 0..cols) {
        var r = 0
        while (r < rows) {
            if (isWater(c - 1, r) != isWater(c, r)) {
                var r2 = r
                while (r2 + 1 < rows && isWater(c - 1, r2 + 1) != isWater(c, r2 + 1)) r2++
                edges += GridEdge(c, r, c, r2 + 1)
                r = r2 + 1
            } else {
                r++
            }
        }
    }
    // Горизонтальные границы между строками r-1 и r, слитые по колонкам.
    for (r in 0..rows) {
        var c = 0
        while (c < cols) {
            if (isWater(c, r - 1) != isWater(c, r)) {
                var c2 = c
                while (c2 + 1 < cols && isWater(c2 + 1, r - 1) != isWater(c2 + 1, r)) c2++
                edges += GridEdge(c, r, c2 + 1, r)
                c = c2 + 1
            } else {
                c++
            }
        }
    }
    return edges
}
