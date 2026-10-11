# Review: актуальность технологий (web-verified) — architecture-walkquest

Дата: 2026-10-01. Проверка каждой названной технологии по живым источникам.

| Технология | В spine | Проверено | Источник | Вердикт |
| --- | --- | --- | --- | --- |
| MapLibre Native Android | 13.5.x, Vulkan-дефолт, opengl-артефакт | android-v13.5.2 (сент 2026); Vulkan дефолт с 13.0.0; `android-sdk-opengl` существует | github.com/maplibre/maplibre-native/releases; maplibre.org newsletter Feb 2026 | OK |
| Supabase Free | 500 МБ, 50k MAU, unlim API, ~10 ГБ egress, пауза 7 дней | подтверждено (5+5 ГБ egress; пауза после 7 дней неактивности) | сводки тарифов Supabase 2026 (несколько независимых) | OK |
| Kotlin | 2.3.x | 2.3.10 (май 2026) | Kotlin docs / сводки | OK |
| Compose BOM | 2026.09.00 (ядро 1.12) | 2026.09.00 — Sep 09, 2026 | mvnrepository.com | OK |
| Room | 2.8.x | 2.8.5 — Sep 09, 2026, stable; Room 3.0 сменил координаты (androidx.room3) — отмечено в spine, миграция не нужна | developer.android.com, mvnrepository.com | OK |
| supabase-kt | 3.8.x | 3.8.0 — Aug 27, 2026, MIT, minSdk 26 (наш minSdk 29 — совместимо) | mvnrepository.com | OK |
| FusedLocationProvider | «актуальная стабильная, пин при bootstrap» | сознательно не пинится | — | OK (seed) |
| Cloudflare R2 | бесплатные 10 ГБ, нулевая плата за egress | публичная политика R2 (zero egress fees) — не перепроверялась в этой сессии, выбор отложен (Deferred) | — | допустимо: решение в Deferred, обратимо |

## Вердикт

PASS. Все запиненные версии подтверждены; неподтверждённое сознательно отправлено в Deferred/пин при bootstrap.
