#!/usr/bin/env python3
"""make-fixtures — генератор .track-фикстур сценариев симуляции (история 1.15).

Файлы — формат журнала AD-5 (JSONL) + поле `prov` (провайдер точки).
Сценарии покрывают то, что `emu geo fix` не умеет (accuracy, провайдер):

  sim-walk-ok.track        — чистая пешая прогулка (база: все точки приняты)
  sim-bad-first-fix.track  — первый фикс с вышек (acc 500, в 2 км) → брак,
                             первый валидный открывает тайл; «призрака» нет
  sim-network-indoor.track — вся серия NETWORK acc 80: маркер живёт (≤100 м),
                             тайлы НЕ открываются (>50 м) — разделение маркер/учёт
  sim-teleport.track       — прогулка, телепорт на 2 км и назад → прыжки
                             отсеяны фильтром (Teleport/TooFast)

Регенерация: python _qa/fixtures/make-fixtures.py
"""

import json
from pathlib import Path

OUT = Path(__file__).parent

# Центр СПб (Дворцовая) — внутри пакета spb-city.
LAT0, LON0 = 59.9343, 30.3351
STEP_LAT = 1.4 / 111_320.0  # ~1,4 м на север за секунду — пеший темп


def walk_points(start_lat, start_lon, count, start_t, acc=10.0, prov="gps", step_s=1):
    return [
        {
            "lat": round(start_lat + i * STEP_LAT * step_s, 7),
            "lon": start_lon,
            "t": start_t + i * step_s * 1000,
            "acc": acc,
            "prov": prov,
        }
        for i in range(count)
    ]


def write(name: str, points: list[dict], note: str) -> None:
    lines = [json.dumps({"formatVersion": 1, "startedAtEpochMillis": points[0]["t"],
                         "note": note}, ensure_ascii=False)]
    lines += [json.dumps(p, ensure_ascii=False) for p in points]
    (OUT / name).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{name}: {len(points)} точек — {note}")


T0 = 1_760_000_000_000

# 1. Чистая прогулка: 20 точек, ~28 м, все принимаются.
write("sim-walk-ok.track", walk_points(LAT0, LON0, 20, T0),
      "база: все точки приняты, тайлы открываются")

# 2. Грубый первый фикс с вышек: acc 500, в 2 км к западу от маршрута.
first = [{"lat": LAT0, "lon": round(LON0 - 2_000 / 64_000.0, 7), "t": T0, "acc": 500.0, "prov": "network"}]
write("sim-bad-first-fix.track", first + walk_points(LAT0, LON0, 15, T0 + 3_000),
      "первый фикс с вышек (acc 500) — брак без якоря; «призрака» нет")

# 3. NETWORK в помещении: маркер живёт, тайлы не открываются.
write("sim-network-indoor.track", walk_points(LAT0, LON0, 15, T0, acc=80.0, prov="network"),
      "network acc 80: маркер с кругом неточности, учёт молчит")

# 4. Телепорт: 10 точек прогулки, прыжок на 2 км, 5 точек там, прыжок назад.
jump_lat = LAT0 + 2_000 / 111_320.0
tp = (walk_points(LAT0, LON0, 10, T0)
      + walk_points(jump_lat, LON0, 5, T0 + 10_000)
      + walk_points(LAT0, LON0, 5, T0 + 15_000))
write("sim-teleport.track", tp,
      "прыжки на 2 км отсеяны фильтром (Teleport/TooFast), тайлы только дома")
