package app.walkquest.ui.map

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.walkquest.WalkQuestApplication
import app.walkquest.data.fog.FogGrid
import app.walkquest.data.poi.Poi
import app.walkquest.data.poi.PoiBounds
import app.walkquest.tracking.LocationFixQuality
import app.walkquest.tracking.REVEAL_RADIUS_METERS
import app.walkquest.tracking.TrackPoint
import app.walkquest.tracking.TrackingService
import app.walkquest.tracking.WalkMode
import app.walkquest.tracking.WalkState
import app.walkquest.ui.onboarding.OnboardingOverlay
import app.walkquest.viewmodel.WalkSummaryUi
import app.walkquest.tracking.circleRing
import app.walkquest.tracking.trailRing
import app.walkquest.ui.theme.AccentTextColor
import app.walkquest.ui.theme.BorderStrongColor
import app.walkquest.ui.theme.DimTextColor
import app.walkquest.ui.theme.FogCoveredColor
import app.walkquest.ui.theme.FogGlowColor
import app.walkquest.ui.theme.HeadlineColor
import app.walkquest.ui.theme.InkSecondaryColor
import app.walkquest.ui.theme.MeCursorColor
import app.walkquest.ui.theme.PoiMarkerColor
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WalkQuestPrimaryButton
import app.walkquest.ui.common.PlanDateTimePicker
import app.walkquest.viewmodel.MapUiState
import app.walkquest.viewmodel.MapViewModel
import app.walkquest.viewmodel.RegionSuggestionUi
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Экран карты (история 1.6).
 * Рендерит MapLibre со стилем установленного пакета региона (или asset-запасом) и
 * кастомный слой тумана с собственной геометрией поверх базовой карты (AD-1):
 * мировой полигон (полевой тест 3) с просветами-«свечой» — кругами в принятых точках
 * трека, сливающимися в ленту (полевой тест 4, 2026-10-06). Вода региона — точными
 * полигонами OSM из векторных тайлов пакета поверх завесы и эффектов тропы
 * (2026-10-09: берег всегда чёткий, без сеточных ступеней и blur-кромки).
 * Debug-режим (2026-10-09, только debuggable): кнопка-тогл «DBG» / 5 тапов —
 * панель статистики (1.14) и ползунков радиуса/силы размытия кромки тропы и
 * прозрачности тумана с живыми значениями для переноса в константы.
 * Разбор владельца 2026-10-05 (дефект 1) + «свеча v3» (2026-10-06, контраст):
 * туман СКРЫВАЕТ базовую карту — слой непрозрачный, цвет FogCoveredColor (светлая
 * тема — чистый токен fog #B29ED1, заметно плотнее бледной базы карты); улицы
 * под завесой не читаются, открытая лента показывает карту.
 * FAB «к моей точке» (дефект 2): справа внизу над кнопкой записи (в макете кнопки
 * нет — позиция по брифу), стиль icon-btn прототипа; видна всегда, без фикса —
 * приглушена и неактивна. Компас MapLibre (дефект 3): отступы с учётом статус-бара.
 * Запись трека (1.7): старт/стоп foreground-сервиса с запросом разрешений
 * (FINE/COARSE + POST_NOTIFICATIONS), состояние — из MapUiState (UDF).
 * POI-маяки (1.9, FR-18): CircleLayer поверх слоя тумана (маяки видны сквозь туман),
 * тап по маркеру открывает карточку места из poi.json пакета — офлайн (AD-2);
 * фото карточки (дефект 6) — файл пакета v2 (`photo` в poi.json), опционально.
 * Маркер «я» (2026-10-05): белое ядро с кольцом токена cursor + свечение + круг
 * точности, поверх всех слоёв (DESIGN, слои карты). Позиция: при записи — из шины
 * TrackingStateBus (тот же поток, что трек, 1.7); без записи — лёгкий слушатель
 * LocationManager, живущий, пока карта на экране. Разрешение на геолокацию
 * запрашивается при первом входе на карту (EXPERIENCE Flow 2 до эпика онбординга).
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel = viewModel(
        factory = (LocalContext.current.applicationContext as WalkQuestApplication)
            .container.mapViewModelFactory,
    ),
    /** Статистика для панели главного экрана (серия, км за неделю); null — колонки скрыты. */
    statsViewModel: app.walkquest.viewmodel.StatsViewModel? = null,
    /** Шестерёнка справа сверху (панель настроек показывает Activity-оверлей). */
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Старт записи требует runtime-разрешений; при отказе запись не стартует,
    // состояние остаётся «не записываем» (кнопка не меняется — без краша).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START),
            )
        }
    }

    // Маркер «я»: разрешение на геолокацию запрашивается при первом входе на карту
    // (EXPERIENCE Flow 2: онбординг — эпик 2, до него карта сама честно спрашивает;
    // отказ → карта работает, маркера нет, запись спросит своё при старте).
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val mapPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!locationGranted) {
            mapPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // Лёгкий слушатель позиции, пока карта на экране и запись выключена (во время
    // записи маркер кормит шина TrackingStateBus — тот же источник, что трек, 1.7).
    // GPS-провайдер, как у сервиса записи (офлайн-first, AD-2); раз в 2 с / от 2 м.
    DisposableEffect(locationGranted, uiState.isRecording) {
        if (!locationGranted || uiState.isRecording) {
            onDispose { }
        } else {
            val manager = context.getSystemService(LocationManager::class.java)
            val listener = LocationListener { location ->
                viewModel.onUserLocation(
                    location.latitude,
                    location.longitude,
                    if (location.hasAccuracy()) location.accuracy else null,
                    if (location.time > 0L) location.time else System.currentTimeMillis(),
                )
            }
            // Последний известный фикс — с ЕГО временем, не текущим (полевой тест 2,
            // дефект 3: старый lastKnownLocation показывался как свежая позиция —
            // «в 20 минутах езды» от реального места; UI отсекает фиксы старше ~30 с).
            val lastKnown = runCatching {
                manager.getProviders(true).firstNotNullOfOrNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
            }.getOrNull()
            if (lastKnown != null) {
                viewModel.onUserLocation(
                    lastKnown.latitude,
                    lastKnown.longitude,
                    if (lastKnown.hasAccuracy()) lastKnown.accuracy else null,
                    if (lastKnown.time > 0L) lastKnown.time else System.currentTimeMillis(),
                )
            }
            runCatching {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    ME_LOCATION_MIN_INTERVAL_MS,
                    ME_LOCATION_MIN_DISTANCE_METERS,
                    listener,
                )
            }
            onDispose {
                runCatching { manager.removeUpdates(listener) }
            }
        }
    }

    // FAB «к моей точке» (дефект 2 разбора 2026-10-05): тап инкрементирует счётчик
    // запросов — FogMap анимирует камеру на последний фикс. Видна всегда; без фикса
    // приглушена и неактивна (решение зафиксировано: кнопка-привычка как в карт-сервисах).
    var recenterRequest by remember { mutableIntStateOf(0) }

    // Оверлей заданий прогулки (4.5): «+ Задание» справа во время записи открывает
    // поток заданий поверх прогулки (EXPERIENCE: запись не останавливается).
    var questOverlayVisible by remember { mutableStateOf(false) }
    // Подтверждение обрыва сессии (DESIGN: «Завершить» — призрак с подтверждением).
    var finishConfirmVisible by remember { mutableStateOf(false) }
    // Планировщик панели главного экрана: «+» на карточке плана.
    var planPickerVisible by remember { mutableStateOf(false) }
    val statsUi = statsViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    // Debug-режим карты (история 1.14 + правки 2026-10-09): только в debuggable-
    // сборке (release — ни тогла, ни панели, ни детектора). Включение — видимой
    // кнопкой-тоглом внизу слева ИЛИ прежним жестом 5 быстрых тапов по карте
    // (окно 1,5 с). В режиме: статистика оверлея 1.14 + ползунки подбора
    // параметров кромки тропы и прозрачности тумана (DebugFogParams) — значения
    // показаны рядом, выбранные переносятся в константы слоёв.
    val debuggable = remember {
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    var debugOverlayVisible by remember { mutableStateOf(false) }
    var debugFogParams by remember { mutableStateOf(DebugFogParams()) }
    var fogStats by remember { mutableStateOf<FogRenderStats?>(null) }
    val debugTapTimes = remember { ArrayDeque<Long>() }
    val onDebugMapTap: () -> Unit = {
        if (debuggable) {
            val now = System.currentTimeMillis()
            debugTapTimes.addLast(now)
            while (debugTapTimes.isNotEmpty() && now - debugTapTimes.first() > DEBUG_TAP_WINDOW_MS) {
                debugTapTimes.removeFirst()
            }
            if (debugTapTimes.size >= DEBUG_TAP_COUNT) {
                debugTapTimes.clear()
                debugOverlayVisible = !debugOverlayVisible
            }
        }
    }

    // Возврат на вкладку карты: MapScreen пересоздаётся при переключении вкладок
    // (when по tab в MainActivity), а Activity остаётся RESUMED — поэтому проверка
    // «пакет обновился на диске» зовётся и на входе в композицию, и на ON_RESUME
    // (дефект разбора 2026-10-05: «Обновить» v1→v2 в «Регионах» не инвалидировал
    // состояние карты — старые POI без фото, старый style.json, старая сетка тумана).
    LaunchedEffect(Unit) { viewModel.onMapShown() }
    val resumeLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(resumeLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onMapShown()
        }
        resumeLifecycle.addObserver(observer)
        onDispose { resumeLifecycle.removeObserver(observer) }
    }

    // Достоинство фикса для маркера «я» (полевой тест 2, дефект 3): маркер только при
    // свежем (≤30 с) и достаточно точном (≤100 м) фиксе. Тикер раз 10 с: если GPS
    // замолчал, зависший маркер сменяется плашкой «Определяю местоположение…».
    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(LOCATION_FRESHNESS_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val meFixVisible = uiState.userLatitude != null && uiState.userLongitude != null &&
        LocationFixQuality.isMarkerWorthy(
            uiState.userAccuracyMeters,
            uiState.userFixEpochMillis,
            nowMillis,
        )

    Box(modifier = Modifier.fillMaxSize()) {
        FogMap(
            uiState = uiState,
            meFixVisible = meFixVisible,
            recenterRequest = recenterRequest,
            debugParams = debugFogParams,
            onViewportChanged = viewModel::onViewportChanged,
            onPoiTapped = viewModel::onPoiTapped,
            onMapTappedOutside = viewModel::onPoiCardDismissed,
            modifier = Modifier.fillMaxSize(),
            onDebugMapTap = onDebugMapTap,
            onFogStats = { fogStats = it },
        )
        // Debug-режим (1.14 + ползунки 2026-10-09): статистика + регуляторы
        // кромки тропы/тумана поверх карты. Только debuggable-сборки.
        if (debuggable && debugOverlayVisible) {
            DebugOverlay(
                uiState = uiState,
                fogStats = fogStats,
                nowMillis = nowMillis,
                params = debugFogParams,
                onParamsChange = { debugFogParams = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 8.dp),
            )
        }
        // Тогл debug-режима (2026-10-09): видимая кнопка в debuggable-сборке —
        // без неё режим доступен только тайным жестом 5 тапов.
        if (debuggable) {
            DebugToggleFab(
                active = debugOverlayVisible,
                onClick = { debugOverlayVisible = !debugOverlayVisible },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 16.dp,
                        bottom = if (uiState.isRecording) FAB_BOTTOM_RECORDING else FAB_BOTTOM_IDLE,
                    ),
            )
        }
        // Индикатор процента открытия (1.8): готовая доля из domain, здесь только формат.
        // Edge-to-edge: отступ от статус-бара, иначе плашка уезжает под шторку.
        uiState.progressFraction?.let { fraction ->
            RegionProgressBadge(
                fraction = fraction,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp),
            )
        }
        // Состояние «определяю местоположение» (полевой тест 2, дефект 3): пока нет
        // свежего точного фикса — честная плашка вместо маркера в случайном месте.
        // Только при выданном разрешении: без разрешения фикс не придёт никогда.
        if (locationGranted && !meFixVisible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(
                        top = if (uiState.isRecording) 140.dp else 76.dp,
                    ),
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = "Определяю местоположение…",
                    color = InkSecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        // Статус «вне области пакета» (разбор владельца 2026-10-05): принятые точки
        // записи вне всех установленных регионов не открывают туман — честная плашка
        // вместо молчания. Под карточкой предложения региона, если та видна.
        if (uiState.isRecording && uiState.outsideCoverage) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(
                        top = when {
                            uiState.suggestion != null -> 190.dp
                            uiState.isRecording -> 140.dp
                            else -> 76.dp
                        },
                    ),
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = "Вы вне области пакета — прогулка не засчитывается",
                    color = InkSecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        // Карточка предложения загрузить регион (1.10, Flow 4): локация в чужом
        // регионе → «Загрузить?»; установка не прерывает запись трека.
        uiState.suggestion?.let { suggestion ->
            RegionSuggestionCard(
                suggestion = suggestion,
                onInstall = viewModel::onInstallSuggested,
                onDismiss = viewModel::onDismissSuggestion,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 76.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
            )
        }
        // Карточка места (1.9): поверх карты и над прототипными кнопками записи.
        uiState.selectedPoi?.let { poi ->
            PoiCard(
                poi = poi,
                photoFile = uiState.selectedPoiPhoto,
                onClose = viewModel::onPoiCardDismissed,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 228.dp)
                    .fillMaxWidth(),
            )
        }
        // FAB «к моей точке»: справа внизу над кнопкой записи (в макете её нет —
        // позиция по брифу владельца), стиль icon-btn прототипа (круг sheet + рамка).
        MeLocationFab(
            hasFix = meFixVisible,
            onClick = { recenterRequest += 1 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = if (uiState.isRecording) FAB_BOTTOM_RECORDING else FAB_BOTTOM_IDLE,
                ),
        )
        // Тогл режима прогулки (2.2, FR-6; DESIGN toggle-mode): значки без текста,
        // активная сторона — значок на белом кружке с кольцом акцента; слева сверху.
        // Виден и до, и во время записи; во время записи тап шлёт ACTION_SET_MODE.
        ModeToggle(
            mode = uiState.walkMode,
            onSelect = { mode ->
                viewModel.onWalkModeSelected(mode)
                if (uiState.isRecording) {
                    context.startService(
                        Intent(context, TrackingService::class.java)
                            .setAction(TrackingService.ACTION_SET_MODE)
                            .putExtra(TrackingService.EXTRA_MODE, mode.name),
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 16.dp),
        )
        // Шестерёнка настроек (DESIGN: верх экрана — информация и редкие элементы)
        // справа сверху; настройки — оверлей (автопауза, напоминания, задания, режим).
        SettingsGearButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 16.dp, top = 16.dp),
        )
        if (uiState.isRecording) {
            // Чип состояния записи (DESIGN: «Идёт запись» / «На паузе» сверху).
            RecordingChip(
                paused = uiState.walkState == WalkState.PAUSED,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 88.dp),
            )
        }
        if (uiState.regionInstalled) {
            if (uiState.isRecording) {
                // FAB «+ Задание» (EXPERIENCE: поток заданий поверх прогулки; запись
                // не останавливается) — справа, над панелью прогулки.
                if (uiState.showQuestButton) {
                    QuestFab(
                        onClick = { questOverlayVisible = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = PANEL_OFFSET_RECORDING + 64.dp),
                    )
                }
                // Активное задание — компактная карточка над панелью (card-task).
                uiState.questActive?.let { quest ->
                    ActiveQuestCard(
                        quest = quest,
                        onCompleted = viewModel::onQuestCompleted,
                        onDeclined = viewModel::onQuestDeclined,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = PANEL_OFFSET_RECORDING + 8.dp),
                    )
                }
                // Панель прогулки (DESIGN): агрегаты мини-колонками + «Пауза» акцентом
                // (крупное частое действие) + «Завершить» призраком с подтверждением.
                WalkPanel(
                    distanceMeters = uiState.walkDistanceMeters,
                    activeMillis = uiState.walkActiveMillis,
                    openedCells = uiState.recordedPoints,
                    paused = uiState.walkState == WalkState.PAUSED,
                    onPauseResume = {
                        context.startService(
                            Intent(context, TrackingService::class.java).setAction(
                                if (uiState.walkState == WalkState.PAUSED) {
                                    TrackingService.ACTION_RESUME
                                } else {
                                    TrackingService.ACTION_PAUSE
                                },
                            ),
                        )
                    },
                    onFinishRequest = { finishConfirmVisible = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                // Статичная панель главного экрана (DESIGN sheet): статистика
                // мини-колонками без разделителей, карточка планировщика, CTA.
                // Не выдвигается, ручки-grab нет.
                HomeSheet(
                    progressFraction = uiState.progressFraction,
                    streakDays = statsUi?.streakDays,
                    weekDistanceMeters = statsUi?.weekDistanceMeters,
                    planText = statsUi?.plans?.firstOrNull { !it.past }
                        ?.let { formatPlanShort(it.plannedAtEpochMillis) + " — прогулка по плану" },
                    onAddPlan = { planPickerVisible = true },
                    onStartWalk = { permissionLauncher.launch(RECORDING_PERMISSIONS) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        // Подтверждение обрыва сессии (DESIGN: «Завершить» — призрак с подтверждением,
        // не одним случайным тапом).
        if (finishConfirmVisible) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { finishConfirmVisible = false },
                title = { Text("Завершить прогулку?") },
                text = { Text("Прогулка будет сохранена в историю.") },
                confirmButton = {
                    TextButton(onClick = {
                        finishConfirmVisible = false
                        context.startService(
                            Intent(context, TrackingService::class.java)
                                .setAction(TrackingService.ACTION_STOP),
                        )
                    }) { Text("Завершить") }
                },
                dismissButton = {
                    TextButton(onClick = { finishConfirmVisible = false }) {
                        Text("Продолжить прогулку")
                    }
                },
            )
        }
        // Планировщик панели (3.6): «+» на карточке плана — диалог даты/времени;
        // напоминание — тот же PlanReminderReceiver, что и в статистике.
        if (planPickerVisible && statsViewModel != null) {
            PlanDateTimePicker(
                onDismiss = { planPickerVisible = false },
                onPlan = { atMillis ->
                    planPickerVisible = false
                    val planId = statsViewModel.planWalk(atMillis)
                    if (planId != null) {
                        app.walkquest.reminder.PlanReminderReceiver.schedule(
                            context,
                            planId,
                            atMillis,
                            statsViewModel.planReminderLeadMinutes(),
                        )
                        statsViewModel.reload()
                    }
                },
            )
        }
        // Поток заданий поверх прогулки (4.5, EXPERIENCE: запись не останавливается;
        // «Готово» возвращает именно на экран прогулки, а не во вкладку).
        if (questOverlayVisible) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                app.walkquest.ui.quests.QuestsContent(
                    viewModel = viewModel,
                    onClose = { questOverlayVisible = false },
                )
            }
        }
        // Финальный экран прогулки (2.8, FR-8): поверх всего при завершённой
        // сессии (ручной стоп или автозавершение по батарее). Сводка — готовые
        // значения из domain/viewmodel (AD-4), здесь только рендер и шеринг.
        uiState.walkSummary?.let { summary ->
            WalkSummaryOverlay(
                summary = summary,
                onShare = { context.startActivity(shareWalkIntent(summary)) },
                onClose = { viewModel.onWalkSummaryDismissed() },
            )
        }
        // Онбординг первого запуска (3.3, FR-12): поверх всего, до первой прогулки.
        if (uiState.showOnboarding) {
            OnboardingOverlay(
                onComplete = { goal -> viewModel.onOnboardingComplete(goal) },
            )
        }
    }
}

/** Разрешения записи трека (minSdk 29 / target 36): локация + уведомление сервиса. */
private val RECORDING_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
)

/**
 * Плашка процента открытия над картой (история 1.8): значение акцентом + подпись
 * «открыто» — паттерн statcol из DESIGN-макета карты. Доля приходит готовой из
 * domain (AD-4); форматирование «17,4%» (русская локаль, запятая, 1 знак) живёт
 * только здесь (Consistency Conventions).
 */
@Composable
private fun RegionProgressBadge(fraction: Double, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatPercent(fraction),
                color = AccentTextColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "открыто",
                color = DimTextColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Единственное место форматирования процента: доля → «17,4%» (ru-локаль, запятая).
 * Первые тайлы региона — доли процента: вместо обнуляющего «0,0%» честный «<0,1%»
 * (дефект D4 прогона 2026-10-05: «нажал — число изменилось»).
 */
private fun formatPercent(fraction: Double): String {
    val percent = fraction * 100.0
    if (percent > 0.0 && percent < PERCENT_LOW_THRESHOLD) return "<0,1%"
    return String.format(Locale.forLanguageTag("ru-RU"), "%.1f%%", percent)
}

/** Порог «<0,1%»: всё ниже 0,05% округлялось бы «%.1f» в «0,0%». */
private const val PERCENT_LOW_THRESHOLD = 0.05

// --- Компоненты утверждённой компоновки главного экрана (DESIGN.md, map-duo) ---

/** Высота панели прогулки + отступ: FAB и карточки встают над ней, а не под ней. */
private val PANEL_OFFSET_RECORDING = 148.dp

/** FAB «к моей точке»/debug: над панелью прогулки / над панелью главного экрана. */
private val FAB_BOTTOM_RECORDING = PANEL_OFFSET_RECORDING + 12.dp
private val FAB_BOTTOM_IDLE = 216.dp

/**
 * Тогл режима прогулки (2.2, FR-6; DESIGN toggle-mode): пилюля со значками без
 * текста; активная сторона — значок на белом кружке с кольцом акцента (контраст
 * эмодзи/глифа на акцентной заливке недостаточен — значок на белом).
 */
@Composable
private fun ModeToggle(
    mode: WalkMode,
    onSelect: (WalkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            WalkMode.entries.forEach { entry ->
                val selected = mode == entry
                val icon = when (entry) {
                    WalkMode.WALKING -> WalkQuestIcons.Walk
                    WalkMode.CYCLING -> WalkQuestIcons.Bike
                }
                Surface(
                    onClick = { onSelect(entry) },
                    shape = CircleShape,
                    color = if (selected) Color.White else Color.Transparent,
                    border = if (selected) {
                        BorderStroke(1.5.dp, AccentTextColor)
                    } else {
                        null
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = when (entry) {
                                WalkMode.WALKING -> "Режим: пешком"
                                WalkMode.CYCLING -> "Режим: вело"
                            },
                            tint = if (selected) AccentTextColor else DimTextColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Шестерёнка настроек (DESIGN: эмодзи ⚙ читался как метка локации — вектор). */
@Composable
private fun SettingsGearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = WalkQuestIcons.Gear,
                contentDescription = "Настройки",
                tint = InkSecondaryColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Чип состояния записи (DESIGN: «Идёт запись» / «На паузе» с точкой-индикатором). */
@Composable
private fun RecordingChip(paused: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (paused) DimTextColor else AccentTextColor,
                modifier = Modifier.size(8.dp),
            ) {}
            Text(
                text = if (paused) "На паузе" else "Идёт запись",
                color = InkSecondaryColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** FAB «+ Задание» во время прогулки (EXPERIENCE: поток заданий поверх прогулки). */
@Composable
private fun QuestFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = WalkQuestIcons.Plus,
                contentDescription = "Задание на прогулку",
                tint = AccentTextColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Карточка активного задания над панелью прогулки (DESIGN card-task). */
@Composable
private fun ActiveQuestCard(
    quest: app.walkquest.viewmodel.QuestUi,
    onCompleted: () -> Unit,
    onDeclined: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AccentTextColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, AccentTextColor.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "Активное задание",
                color = DimTextColor,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "${quest.icon} ${quest.name}",
                color = HeadlineColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = quest.description,
                color = InkSecondaryColor,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCompleted) { Text("Выполнено", color = AccentTextColor) }
                TextButton(onClick = onDeclined) { Text("Снять", color = InkSecondaryColor) }
            }
        }
    }
}

/**
 * Панель прогулки (DESIGN): агрегаты мини-колонками (время / км / клетки),
 * «Пауза» (или «Продолжить») — крупная акцентная, «Завершить» — призрак
 * (обрыв сессии только с подтверждением).
 */
@Composable
private fun WalkPanel(
    distanceMeters: Double,
    activeMillis: Long,
    openedCells: Int,
    paused: Boolean,
    onPauseResume: () -> Unit,
    onFinishRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 1.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatMiniColumn(value = formatDuration(activeMillis), label = "время")
                StatMiniColumn(value = "%.1f".format(distanceMeters / 1000.0), label = "км")
                StatMiniColumn(value = openedCells.toString(), label = "клеток открыто")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WalkQuestPrimaryButton(
                    onClick = onPauseResume,
                    modifier = Modifier.weight(1f),
                ) { Text(text = if (paused) "Продолжить" else "Пауза") }
                app.walkquest.ui.theme.WalkQuestGhostButton(
                    onClick = onFinishRequest,
                    modifier = Modifier.weight(1f),
                ) { Text(text = "Завершить") }
            }
        }
    }
}

/**
 * Статичная панель главного экрана (DESIGN sheet): статистика мини-колонками без
 * разделителей и подложек, карточка планировщика, CTA «Начать прогулку».
 * Не выдвигается; скруглённые углы прозрачны до карты.
 */
@Composable
private fun HomeSheet(
    progressFraction: Double?,
    streakDays: Int?,
    weekDistanceMeters: Double?,
    planText: String?,
    onAddPlan: () -> Unit,
    onStartWalk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 1.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatMiniColumn(
                    value = progressFraction?.let { formatPercent(it) } ?: "—",
                    label = "открыто",
                )
                StatMiniColumn(
                    value = streakDays?.let { pluralDaysShort(it) } ?: "—",
                    label = "серия",
                )
                StatMiniColumn(
                    value = weekDistanceMeters?.let { "%.1f км".format(it / 1000.0) } ?: "—",
                    label = "за неделю",
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Карточка планировщика (3.6): ближайший план; «+» — диалог даты/времени.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        text = planText ?: "Нет планов — нажмите +, чтобы запланировать прогулку",
                        color = if (planText != null) InkSecondaryColor else DimTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAddPlan) {
                        Icon(
                            imageVector = WalkQuestIcons.Plus,
                            contentDescription = "Запланировать прогулку",
                            tint = AccentTextColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            WalkQuestPrimaryButton(onClick = onStartWalk, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Начать прогулку")
            }
        }
    }
}

/** Мини-колонка статистики (DESIGN stat-line): значение акцентом над подписью dim. */
@Composable
private fun StatMiniColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = AccentTextColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = label,
            color = DimTextColor,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Чистое время прогулки: «мм:ss», при ≥1 ч — «ч:мм». */
private fun formatDuration(activeMillis: Long): String {
    val totalSeconds = activeMillis / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours >= 1L) {
        "%d:%02d".format(hours, minutes)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** «1 день» / «2 дня» / «5 дней» — короткая форма серии для мини-колонки. */
private fun pluralDaysShort(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..19 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
    return "$n $word"
}

/** Ближайший план для карточки панели: «Сегодня, 12:00» / «Завтра, 19:30» / «12.10, 19:30». */
private fun formatPlanShort(epochMillis: Long): String {
    val ru = Locale.forLanguageTag("ru-RU")
    val time = java.text.SimpleDateFormat("HH:mm", ru).format(java.util.Date(epochMillis))
    val now = java.util.Calendar.getInstance()
    val plan = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    val dayStart = { c: java.util.Calendar ->
        c.clone() as java.util.Calendar
    }
    val today = dayStart(now).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val planDay = dayStart(plan).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val diffDays = ((planDay.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
    return when (diffDays) {
        0 -> "Сегодня, $time"
        1 -> "Завтра, $time"
        else -> java.text.SimpleDateFormat("d MMM, HH:mm", ru).format(java.util.Date(epochMillis))
    }
}

/**
 * Debug-панель карты (история 1.14 + ползунки 2026-10-09; только debuggable-сборки —
 * тогл и рендер gated по ApplicationInfo.FLAG_DEBUGGABLE в MapScreen; в release панели нет).
 * Живые значения состояния: версия пакета в памяти (диагностика «старая версия»
 * — дефект fix4), тайлы/лента/вершины завесы, последний фикс (провайдер,
 * accuracy, возраст), счётчики и последний вердикт фильтра, источник пакетов.
 * Тогл — кнопка внизу слева или 5 быстрых тапов по карте (окно DEBUG_TAP_WINDOW_MS).
 * Секция ползунков (2026-10-09): подбор параметров кромки тропы (радиус и сила
 * размытия пера) и прозрачности завесы; текущие значения показаны рядом с
 * ползунком — выбранные владелец переносит в константы слоёв (FEATHER_*,
 * FOG_OPACITY). Панель интерактивна: жесты под ней не доходят до карты.
 */
@Composable
private fun DebugOverlay(
    uiState: MapUiState,
    fogStats: FogRenderStats?,
    nowMillis: Long,
    params: DebugFogParams,
    onParamsChange: (DebugFogParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixAgeSec = uiState.userFixEpochMillis?.let { (nowMillis - it) / 1000 }
    val grid = uiState.grid
    val lines = buildList {
        add("DEBUG · тогл слева внизу / 5 тапов — скрыть")
        add("pkg: ${uiState.activeRegionId ?: "—"} v${uiState.packageVersion ?: "—"} · epoch ${uiState.fogEpoch}")
        add("baseUrl: ${uiState.packagesBaseUrl.ifBlank { "—" }}")
        add("tiles: ${uiState.openedIndices.size} откр · сетка ${grid?.cols ?: "?"}×${grid?.rows ?: "?"}")
        add("trail: ${uiState.ribbonChains.sumOf { it.size }} осевых (${uiState.ribbonChains.size} сесс.) · кольцо ${fogStats?.ringVertices ?: "—"} верш")
        add(
            fogStats?.let { "fog build %.1f мс · upload %.1f мс".format(it.buildMs, it.uploadMs) }
                ?: "fog: стиль не загружен",
        )
        add(
            "fix: ${uiState.userFixProvider ?: "—"} · acc ${uiState.userAccuracyMeters?.let { "%.0f м".format(it) } ?: "—"}" +
                " · ${fixAgeSec?.let { "$it с назад" } ?: "нет фикса"}",
        )
        add(
            "rec: ${if (uiState.isRecording) "ON" else "off"} · +${uiState.recordedPoints} / −${uiState.rejectedPoints}" +
                (uiState.lastRejectedVerdict?.let { " · последний брак: $it" } ?: "") +
                (if (uiState.outsideCoverage) " · ВНЕ ПАКЕТА" else ""),
        )
        add("poi: ${uiState.visiblePois.size} видимо / ${uiState.poiTotalCount}")
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xB3000000),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    color = Color(0xFFE8E8E8),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            // Ползунки подбора параметров рендера (2026-10-09). Диапазоны —
            // разумные пределы вокруг текущей калибровки (константы FEATHER_*,
            // FOG_OPACITY); выбранные значения читаются из подписей.
            DebugSlider(
                label = "Блюр кромки тропы",
                value = params.featherBlurMeters,
                valueRange = DEBUG_FEATHER_BLUR_RANGE,
                format = { "%.1f м".format(it) },
                onChange = { onParamsChange(params.copy(featherBlurMeters = it)) },
            )
            DebugSlider(
                label = "Сила блюра кромки",
                value = params.featherStrength,
                valueRange = DEBUG_FEATHER_STRENGTH_RANGE,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(featherStrength = it)) },
            )
            DebugSlider(
                label = "Прозрачность тумана",
                value = params.fogOpacity,
                valueRange = DEBUG_FOG_OPACITY_RANGE,
                format = { "%.2f".format(it) },
                onChange = { onParamsChange(params.copy(fogOpacity = it)) },
            )
        }
    }
}

/**
 * Параметры рендера, подбираемые debug-ползунками (2026-10-09). Дефолты = текущие
 * константы слоёв: без действий пользователя картинка не меняется. Живут в памяти
 * композиции (персистентность не нужна): выбранные значения переносятся правкой
 * констант FEATHER_BLUR_METERS / FEATHER_OPACITY / FOG_OPACITY.
 */
private data class DebugFogParams(
    /** Радиус размытия пера кромки тропы, метры (дефолт [FEATHER_BLUR_METERS]). */
    val featherBlurMeters: Float = FEATHER_BLUR_METERS.toFloat(),
    /** Сила (непрозрачность) пера кромки тропы 0..1 (дефолт [FEATHER_OPACITY]). */
    val featherStrength: Float = FEATHER_OPACITY,
    /** Непрозрачность завесы 0..1 (дефолт [FOG_OPACITY] — туман скрывает карту). */
    val fogOpacity: Float = FOG_OPACITY,
)

/**
 * Один ползунок debug-панели: подпись + живое значение рядом (чтобы выбранное
 * значение можно было списать в константы), сам Slider. Формат — ru-подобный
 * (%.1f/%.2f через format с точкой; подбору точности хватает).
 */
@Composable
private fun DebugSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            text = "$label: ${format(value)}",
            color = Color(0xFFE8E8E8),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = valueRange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
    )
}

/**
 * Кнопка-тогл debug-режима (2026-10-09): видима только в debuggable-сборке.
 * Стиль — как у FAB «к моей точке» (круг sheet + рамка); активный режим —
 * акцентная подпись. Глиф не вводим — короткая подпись «DBG» честнее.
 */
@Composable
private fun DebugToggleFab(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, BorderStrongColor),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "DBG",
                color = if (active) AccentTextColor else InkSecondaryColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

/**
 * FAB «к моей точке» (разбор владельца 2026-10-05, дефект 2). В макете кнопки нет,
 * поэтому стиль — icon-btn прототипа (круг 48dp, заливка surface-raised, рамка
 * border-strong, глиф ink-secondary). Без фикса позиции — приглушена (ink-dim)
 * и неактивна: центровать не на чём. Глиф «my location» — вектор 24dp вручную,
 * material-icons-зависимость не вводим (как TabIcons в MainActivity).
 */
@Composable
private fun MeLocationFab(hasFix: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        enabled = hasFix,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, BorderStrongColor),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = MyLocationIcon,
                contentDescription = "К моей точке",
                tint = if (hasFix) InkSecondaryColor else DimTextColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Глиф перекрестия «моя позиция» (24dp viewport; тон задаёт Icon tint). */
private val MyLocationIcon: ImageVector = ImageVector.Builder(
    name = "my-location",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94" +
            "V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06" +
            "c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

/**
 * Карточка предложения загрузить регион (история 1.10, FR-4, EXPERIENCE Flow 4):
 * «Похоже, вы в регионе X» → «Загрузить» / «Позже». Первый запуск без регионов
 * (дефект D1 прогона 2026-10-05, Flow 2) — та же карточка с текстом «домашний регион».
 * Во время установки — состояние «Загружается…»; ошибка установки — типизированное
 * сообщение из domain (1.5) как есть. Запись трека не прерывается (сервис живёт
 * отдельно, 1.7). Токены — как у PoiCard: фон surface-raised, радиус rounded.lg.
 */
@Composable
private fun RegionSuggestionCard(
    suggestion: RegionSuggestionUi,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = if (suggestion.isInitialHome) {
                    "Загрузите домашний регион"
                } else {
                    "Похоже, вы в регионе «${suggestion.name}»"
                },
                color = HeadlineColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = if (suggestion.isInitialHome) {
                    "Начните с региона «${suggestion.name}»: его карта, туман и прогресс ваших прогулок."
                } else {
                    "Загрузить его карту и туман? Прогулки там будут засчитываться этому региону."
                },
                color = InkSecondaryColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            suggestion.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalkQuestPrimaryButton(onClick = onInstall, enabled = !suggestion.installing) {
                    Text(text = if (suggestion.installing) "Загружается…" else "Загрузить")
                }
                TextButton(onClick = onDismiss, enabled = !suggestion.installing) {
                    Text(text = "Позже", color = InkSecondaryColor)
                }
            }
        }
    }
}

/**
 * Карточка места (история 1.9, токен poi-card): нижняя зона поверх карты,
 * фон surface-raised, радиус rounded.lg (16dp); заголовок — heading (Nunito-эквивалент
 * titleLarge ExtraBold, цвет ink-headline), описание — body (ink-secondary).
 * Фото (пакет v2, разбор 2026-10-05 дефект 6): поле `photo` в poi.json — относительный
 * путь от корня пакета; файл читается с диска установленного пакета (офлайн) и
 * показывается полосой сверху, как .photos в макете poi-card. Фото нет (старый
 * пакет v1) или файл не читается — полосы нет, карточка как прежде (fallback).
 * Если описания нет в данных пакета (description: null — деградация генератора 1.3),
 * вместо пустоты — человекочитаемая категория места строкой meta (разумный fallback:
 * макет состояния без описания не задаёт, категория — существующие данные, не выдумка).
 * Длинное описание (полевой тест 3): обрезка до ~4 строк с многоточием; если обрезано
 * и у POI есть `url` (пакет v5) — ссылка «Подробнее» акцентным текстом (токен
 * accent-text, DESIGN) открывает браузер (ACTION_VIEW); без url — просто обрезка.
 * Закрытие: кнопка или свайп вниз (DESIGN);
 * тап вне карточки обрабатывает карта (onMapTappedOutside → onPoiCardDismissed).
 * Данные — из уже загруженного poi.json пакета: сеть не нужна (AD-2).
 */
@Composable
private fun PoiCard(poi: Poi, photoFile: File?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var dragDown by remember(poi.osmId) { mutableStateOf(0f) }
    val context = LocalContext.current
    Surface(
        modifier = modifier.pointerInput(poi.osmId) {
            detectVerticalDragGestures(
                onVerticalDrag = { _, amount -> if (amount > 0) dragDown += amount },
                onDragEnd = {
                    if (dragDown > POI_CARD_DISMISS_DRAG_PX) onClose()
                    dragDown = 0f
                },
            )
        },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            // Фото места (пакет v2): полоса во всю ширину сверху, Surface обрезает
            // по радиусу карточки. Без фото (старый пакет) полосы нет вообще.
            if (photoFile != null) {
                PoiPhoto(photoFile)
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = poi.displayName,
                        color = HeadlineColor,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) {
                        Text(text = "Закрыть", color = InkSecondaryColor)
                    }
                }
                val description = poi.description?.takeIf { it.isNotBlank() }
                if (description != null) {
                    // Полевой тест 3: длинное описание — максимум ~4 строки с многоточием;
                    // если текст обрезан и у POI есть url (пакет v5) — ссылка «Подробнее»
                    // в браузер (ACTION_VIEW). Без url (пакеты v1–v4) — просто обрезка.
                    var truncated by remember(poi.osmId) { mutableStateOf(false) }
                    Text(
                        text = description,
                        color = InkSecondaryColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = POI_DESCRIPTION_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { truncated = it.hasVisualOverflow },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val url = poi.url?.takeIf { it.isNotBlank() }
                    if (truncated && url != null) {
                        Text(
                            text = "Подробнее",
                            color = AccentTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                                .padding(bottom = 8.dp),
                        )
                    }
                } else {
                    // Описания в пакете нет — показываем категорию места вместо пустоты.
                    Text(
                        text = poi.displayCategory,
                        color = DimTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Фото POI из установленного пакета (офлайн, AD-2): декодирование JPEG с диска
 * в фоне (BitmapFactory, без Coil — зависимость не вводим ради одной полосы).
 * Пока декодируется — плашка surface-base высотой полосы (макет .photos, ~96dp);
 * файл битый — полоса схлопывается, карточка остаётся текстовой (fallback).
 */
@Composable
private fun PoiPhoto(photoFile: File) {
    val decoded by produceState<DecodedPoiPhoto>(initialValue = DecodedPoiPhoto.Loading, photoFile) {
        value = withContext(Dispatchers.IO) {
            val bitmap = runCatching { BitmapFactory.decodeFile(photoFile.absolutePath) }.getOrNull()
            if (bitmap != null) DecodedPoiPhoto.Ready(bitmap.asImageBitmap()) else DecodedPoiPhoto.Failed
        }
    }
    when (val state = decoded) {
        DecodedPoiPhoto.Failed -> Unit // файл не читается — полосы нет вообще
        DecodedPoiPhoto.Loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(POI_PHOTO_HEIGHT)
                .background(MaterialTheme.colorScheme.background),
        )
        is DecodedPoiPhoto.Ready -> Image(
            bitmap = state.bitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(POI_PHOTO_HEIGHT),
            contentScale = ContentScale.Crop,
        )
    }
}

/** Состояние декодирования фото карточки места. */
private sealed interface DecodedPoiPhoto {
    data object Loading : DecodedPoiPhoto
    data object Failed : DecodedPoiPhoto
    data class Ready(val bitmap: ImageBitmap) : DecodedPoiPhoto
}

@Composable
private fun FogMap(
    uiState: MapUiState,
    meFixVisible: Boolean,
    recenterRequest: Int,
    onViewportChanged: (PoiBounds, Double) -> Unit,
    onPoiTapped: (String) -> Unit,
    onMapTappedOutside: () -> Unit,
    modifier: Modifier = Modifier,
    // Debug-режим (1.14 + ползунки 2026-10-09): каждый тап по карте (детектор
    // 5 тапов), метрики завесы и подбираемые параметры кромки/тумана.
    debugParams: DebugFogParams = DebugFogParams(),
    onDebugMapTap: () -> Unit = {},
    onFogStats: (FogRenderStats) -> Unit = {},
) {
    val mapView = rememberMapViewWithLifecycle()
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val markerArgb = PoiMarkerColor.toArgb()
    val cursorArgb = MeCursorColor.toArgb()
    val fogArgb = FogCoveredColor.toArgb()
    val glowArgb = FogGlowColor.toArgb()
    // Актуальные значения для асинхронных колбэков (setStyle, слушатели карты):
    // события, пришедшие до конца загрузки стиля, не должны потерять состояние.
    val currentState by rememberUpdatedState(uiState)
    val currentMeFixVisible by rememberUpdatedState(meFixVisible)
    val currentMarkerArgb by rememberUpdatedState(markerArgb)
    val currentCursorArgb by rememberUpdatedState(cursorArgb)
    val currentFogArgb by rememberUpdatedState(fogArgb)
    val currentGlowArgb by rememberUpdatedState(glowArgb)
    val currentDebugParams by rememberUpdatedState(debugParams)
    val currentOnViewport by rememberUpdatedState(onViewportChanged)
    val currentOnPoiTapped by rememberUpdatedState(onPoiTapped)
    val currentOnOutsideTap by rememberUpdatedState(onMapTappedOutside)
    val currentOnDebugMapTap by rememberUpdatedState(onDebugMapTap)
    val currentOnFogStats by rememberUpdatedState(onFogStats)

    AndroidView(factory = { mapView }, modifier = modifier) { view ->
        // Debug-оверлей (1.14): детектор 5 тапов слушает СЫРЫЕ касания
        // (ACTION_DOWN через OnTouchListener, возврат false — жесты карты
        // не перехватываются). На уровне addOnMapClickListener быстрые тапы
        // доходить не должны: MapLibre съедает их как double-tap-зум.
        // Побочка: при тогле карта подзумируется — приемлемо для debug-жеста.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                currentOnDebugMapTap()
            }
            false
        }
        if (mapLibreMap == null) {
            view.getMapAsync { map ->
                mapLibreMap = map
                // Компас (дефект 3 разбора 2026-10-05): правый верхний угол, но НЕ под
                // шторкой — верхний отступ = высота статус-бара + поля (edge-to-edge).
                val density = view.resources.displayMetrics.density
                val margin = (COMPASS_MARGIN_DP * density).toInt()
                map.uiSettings.apply {
                    compassGravity = Gravity.TOP or Gravity.END
                    setCompassMargins(margin, statusBarHeightPx(view) + margin, margin, margin)
                    // fix10 (2026-10-07): логотип MapLibre убран; кнопка атрибуции
                    // оставлена (лицензия OSM требует), но приглушена — прозрачность/
                    // масштаб ниже, отступы от краёв (не бросается в глаза).
                    isLogoEnabled = false
                    setAttributionMargins(margin, 0, 0, margin)
                }
                muteAttributionButton(view)
                // Остановка камеры → новая выдача POI по viewport и зуму (UDF-событие).
                map.addOnCameraIdleListener {
                    val b = map.projection.visibleRegion.latLngBounds
                    currentOnViewport(
                        PoiBounds(b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast),
                        map.cameraPosition.zoom,
                    )
                }
                // Тап: маркер POI → карточка; вне маркера → закрытие карточки
                // (тап вне, DESIGN) — событие не перехватываем, жесты карты живут.
                map.addOnMapClickListener { latLng ->
                    // Слоя может ещё/уже не быть (стиль не загружен, пакет не установлен):
                    // запрос по несуществующему слою пропускаем — это тап вне маркера.
                    val hasPoiLayer = map.style?.getLayer(POI_LAYER_ID) != null
                    val hit = if (hasPoiLayer) {
                        val p = map.projection.toScreenLocation(latLng)
                        map.queryRenderedFeatures(
                            RectF(
                                p.x - POI_TAP_TOLERANCE_PX, p.y - POI_TAP_TOLERANCE_PX,
                                p.x + POI_TAP_TOLERANCE_PX, p.y + POI_TAP_TOLERANCE_PX,
                            ),
                            POI_LAYER_ID,
                        ).firstOrNull()?.getStringProperty(POI_PROPERTY_OSM_ID)
                    } else {
                        null
                    }
                    if (hit != null) {
                        currentOnPoiTapped(hit)
                        true
                    } else {
                        currentOnOutsideTap()
                        false
                    }
                }
            }
        }
    }

    // Стиль и стартовая камера: asset-запас до чтения диска, затем style.json пакета.
    // Один вход установки стиля — без двойной загрузки при первом кадре.
    // Порядок добавления слоёв решает «вода поверх завесы» (полевой тест 2, дефект 1),
    // «маяки сквозь туман» (FR-18) и «маркер "я" поверх всего» (DESIGN, слои карты):
    // туман → свеча/перо кромки → вода (вектор, выше эффектов тропы — берег всегда
    // чёткий, 2026-10-09) → POI → маркер «я» — MapLibre рисует слои в порядке
    // добавления; слои, создаваемые позже (glow/feather при первой записи),
    // кладутся addLayerBelow под водяной — инвариант держится всегда.
    LaunchedEffect(mapLibreMap, uiState.styleUri) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.cameraPosition = cameraOf(uiState)
        map.setStyle(Style.Builder().fromUri(uiState.styleUri)) { style ->
            updateFogLayer(
                style,
                currentState,
                fogArgb = currentFogArgb,
                fogOpacity = currentDebugParams.fogOpacity,
                onStats = currentOnFogStats,
            )
            updateFogGlowLayer(style, currentState, glowArgb = currentGlowArgb)
            updateTrailFeatherLayer(
                style,
                currentState,
                fogArgb = currentFogArgb,
                blurMeters = currentDebugParams.featherBlurMeters.toDouble(),
                strength = currentDebugParams.featherStrength,
            )
            updateWaterVectorLayer(style, currentState)
            updatePoiLayer(style, currentState, markerArgb = currentMarkerArgb)
            updateMeLayer(style, currentState, cursorArgb = currentCursorArgb, visible = currentMeFixVisible)
        }
    }

    // Границы мира (полевой тест 2, дефект 5): за bbox пакета тайлов нет — пустой фон.
    // Камера ограничена bbox региона с запасом 20% пролёта: мир не обрывается в серое,
    // подглядеть соседнюю воду/сушу за краем ещё можно. Без региона ограничения нет.
    LaunchedEffect(mapLibreMap, uiState.regionInstalled, uiState.grid) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val grid = uiState.grid
        if (!uiState.regionInstalled || grid == null) {
            map.setLatLngBoundsForCameraTarget(null)
            return@LaunchedEffect
        }
        val latSpan = grid.northLat - grid.originLat
        val lonSpan = grid.eastLon - grid.originLon
        map.setLatLngBoundsForCameraTarget(
            LatLngBounds.Builder()
                .include(
                    LatLng(
                        grid.originLat - latSpan * CAMERA_BOUNDS_MARGIN,
                        grid.originLon - lonSpan * CAMERA_BOUNDS_MARGIN,
                    ),
                )
                .include(
                    LatLng(
                        grid.northLat + latSpan * CAMERA_BOUNDS_MARGIN,
                        grid.eastLon + lonSpan * CAMERA_BOUNDS_MARGIN,
                    ),
                )
                .build(),
        )
    }

    // Изменение биткарты (fogEpoch), сетки, темы или debug-параметров (2026-10-09)
    // → перестроение геометрии/свойств слоёв завесы и кромки тропы.
    LaunchedEffect(mapLibreMap, uiState.fogEpoch, uiState.grid, fogArgb, glowArgb, debugParams) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        updateFogLayer(
            style,
            uiState,
            fogArgb = fogArgb,
            fogOpacity = debugParams.fogOpacity,
            onStats = currentOnFogStats,
        )
        updateFogGlowLayer(style, uiState, glowArgb = glowArgb)
        updateTrailFeatherLayer(
            style,
            uiState,
            fogArgb = fogArgb,
            blurMeters = debugParams.featherBlurMeters.toDouble(),
            strength = debugParams.featherStrength,
        )
    }

    // FAB «к моей точке»: каждый тап — новый запрос; камера анимируется на последний
    // фикс позиции (зум не опускаем, но поднимаем до районного, если смотрим с обзорного).
    LaunchedEffect(mapLibreMap, recenterRequest) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (recenterRequest == 0) return@LaunchedEffect
        val lat = currentState.userLatitude ?: return@LaunchedEffect
        val lon = currentState.userLongitude ?: return@LaunchedEffect
        val zoom = maxOf(map.cameraPosition.zoom, RECENTER_MIN_ZOOM)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
    }

    // Новая выдача POI (visiblePois) или смена темы → перестроение слоя маяков.
    LaunchedEffect(mapLibreMap, uiState.visiblePois, markerArgb) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        updatePoiLayer(style, uiState, markerArgb = markerArgb)
    }

    // Новый фикс позиции, его достоинство или смена темы → перестроение маркера «я».
    LaunchedEffect(
        mapLibreMap,
        uiState.userLatitude,
        uiState.userLongitude,
        uiState.userAccuracyMeters,
        uiState.userFixEpochMillis,
        meFixVisible,
        cursorArgb,
    ) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        updateMeLayer(style, uiState, cursorArgb = cursorArgb, visible = meFixVisible)
    }
}

private fun cameraOf(state: MapUiState): CameraPosition = CameraPosition.Builder()
    .target(LatLng(state.cameraLatitude, state.cameraLongitude))
    .zoom(state.cameraZoom)
    .build()

/**
 * Слой тумана: один GeoJsonSource, Feature = Polygon(внешнее кольцо = ВЕСЬ МИР
 * ([-180, -85.05]..[180, 85.05], полевой тест 3 — квадратной кромки по bbox региона
 * нет, за пределами пакета сплошная завеса), дырки = БУФЕРНАЯ ТРОПА «свечи v2»
 * (одно кольцо вокруг осевой принятых точек, радиус REVEAL_RADIUS_METERS,
 * круглые стыки/торцы — разбор владельца 2026-10-06: цепочка кругов fix7
 * отвергнута как «бусы-гусеница»).
 * Изначально дырок нет — мир полностью под завесой (камера ограничена bbox+20 %, fix5).
 * Дефект 1 разбора владельца 2026-10-05: слой НЕПРОЗРАЧНЫЙ (fillOpacity 1.0
 * по умолчанию; debug-ползунок 2026-10-09 подбирает [fogOpacity] живьём),
 * цвет — FogCoveredColor («свеча v3», 2026-10-06: светлая тема — чистый токен fog
 * #B29ED1; композит прототипа #CBBEE1 сливался с бледной базой карты):
 * закрытые тайлы скрывают базовую карту полностью, открытая тропа показывает её.
 * Учёт (биткарта/процент, AD-4) рендером не меняется: клетка открывается первой
 * валидной точкой через OpenTileAtPoint, как прежде — тропа лишь рисует просвет.
 * Вода (2026-10-09): сеточные просветы water.grid УБРАНЫ — берег рисует только
 * точный векторный слой [updateWaterVectorLayer] поверх завесы (полигоны OSM),
 * ступеней 100 м у берега больше нет; над водой лежит обычная завеса.
 * Производительность: перестроение на каждую новую точку осевой; дедупликация
 * (RIBBON_DEDUP_METERS = 9 м) держит осевую в сотнях точек даже на
 * многотысячных треках (замеры — deferred-work.md, «Свеча v2: буферная тропа»);
 * в лог пишется время сборки полигона и заливки в источник (диагностика просадок).
 */
private fun updateFogLayer(
    style: Style,
    state: MapUiState,
    fogArgb: Int,
    fogOpacity: Float = FOG_OPACITY,
    onStats: ((FogRenderStats) -> Unit)? = null,
) {
    val grid = state.grid
    if (!state.regionInstalled || grid == null) {
        style.removeLayer(FOG_LAYER_ID)
        style.removeSource(FOG_SOURCE_ID)
        return
    }
    val buildStart = System.nanoTime()
    // Кольцо буфера на цепочку-сессию (2026-10-08): цепочки не соединяются.
    val trails = state.ribbonChains.map { trailRing(it, REVEAL_RADIUS_METERS) }
    val feature = Feature.fromGeometry(fogPolygon(grid, trails))
    val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0

    val uploadStart = System.nanoTime()
    val source = style.getSource(FOG_SOURCE_ID) as? GeoJsonSource
    if (source == null) {
        style.addSource(GeoJsonSource(FOG_SOURCE_ID, feature))
        style.addLayer(
            FillLayer(FOG_LAYER_ID, FOG_SOURCE_ID).withProperties(
                PropertyFactory.fillColor(fogArgb),
                PropertyFactory.fillOpacity(fogOpacity),
            ),
        )
    } else {
        source.setGeoJson(feature)
        (style.getLayer(FOG_LAYER_ID) as? FillLayer)?.setProperties(
            PropertyFactory.fillColor(fogArgb),
            PropertyFactory.fillOpacity(fogOpacity),
        )
    }
    val uploadMs = (System.nanoTime() - uploadStart) / 1_000_000.0
    Log.d(
        FOG_TAG,
        "fog geometry: trailChains=${state.ribbonChains.size} " +
            "trailPoints=${state.ribbonChains.sumOf { it.size }} trailRing=${trails.sumOf { it.size }}, " +
            "build=%.2f ms, upload=%.2f ms".format(buildMs, uploadMs),
    )
    // Debug-оверлей (1.14): те же цифры, что в logcat, — на экране без чтения логов.
    onStats?.invoke(
        FogRenderStats(
            trailPoints = state.ribbonChains.sumOf { it.size },
            ringVertices = trails.sumOf { it.size },
            buildMs = buildMs,
            uploadMs = uploadMs,
            epoch = state.fogEpoch,
        ),
    )
}

/** Метрики последнего перестроения завесы — debug-оверлей (история 1.14). */
private data class FogRenderStats(
    val trailPoints: Int,
    val ringVertices: Int,
    val buildMs: Double,
    val uploadMs: Double,
    val epoch: Int,
)

/**
 * Точные контуры воды поверх завесы (полевой тест 2, дефект 1: вырезание воды сеткой
 * 100 м давало рваный берег, узкие реки < клетки пропадали). FillLayer из того же
 * векторного источника тайлов пакета (source-layer `water`, схема OpenMapTiles),
 * цветом воды базовой карты — береговая линия проступает сквозь туман по полигонам,
 * а не по клеткам.
 * Единственный рендер воды (2026-10-09): сеточные просветы water.grid и blur-кромка
 * убраны — контур всегда чёткий, точный полигон OSM; слой непрозрачный, туман над
 * водой не читается при любом значении debug-ползунка прозрачности завесы.
 * Слой рисуется ПОВЕРХ завесы, ореола и пера кромки тропы (под POI и маркером «я»):
 * эффекты тропы не заливают береговую линию; слои glow/feather, создаваемые позже
 * (первая запись в сессии), кладутся addLayerBelow под этот слой.
 * Фильтр «только крупная вода»: исключаем классы `pond`/`swimming_pool` (мелочь);
 * классы `ocean`/`lake`/`river`/`canal` (Нева, залив, каналы) видны всегда. Мельче
 * класса (пруды, идущие в OMT классом `lake`) не отфильтровать — показываются,
 * это зафиксированное допущение. Источника/слоя в стиле нет (asset-запас, старый
 * пакет) — слоя нет, вода остаётся под завесой, как до появления воды (v1/v2).
 */
private fun updateWaterVectorLayer(style: Style, state: MapUiState) {
    val hasTilesSource = style.getSource(REGION_TILES_SOURCE_ID) != null
    if (!state.regionInstalled || !hasTilesSource) {
        style.removeLayer(WATER_VECTOR_LAYER_ID)
        return
    }
    if (style.getLayer(WATER_VECTOR_LAYER_ID) != null) return
    val layer = FillLayer(WATER_VECTOR_LAYER_ID, REGION_TILES_SOURCE_ID)
    layer.setSourceLayer(WATER_TILES_SOURCE_LAYER)
    layer.setFilter(
        Expression.not(
            Expression.any(
                Expression.eq(Expression.get(WATER_CLASS_PROPERTY), Expression.literal("pond")),
                Expression.eq(Expression.get(WATER_CLASS_PROPERTY), Expression.literal("swimming_pool")),
            ),
        ),
    )
    layer.setProperties(PropertyFactory.fillColor(waterColorOf(style)))
    style.addLayer(layer)
}

/**
 * Цвет воды базовой карты из её слоя `water` (стиль пакета может меняться версиями —
 * читаем живое значение, а не копируем константу). Слой выражением или отсутствует —
 * запасной цвет текущего стиля пакета (#9ec9e0, style.json tools/region-pack).
 */
private fun waterColorOf(style: Style): Int {
    val base = style.getLayer(BASE_WATER_LAYER_ID) as? FillLayer
    val property = base?.fillColor
    val colorString = if (property != null && property.isValue) property.value else null
    return colorString?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
        ?: FALLBACK_WATER_COLOR
}

/**
 * «Свеча» вокруг тропы просветов (разбор владельца 2026-10-06 по полевому
 * тесту 4: «рядом ярко, дальше темнее» — край проёма мягкий; бусины-ореолы
 * по точкам отвергнуты вместе с цепочкой кругов). Реализация — LineLayer по
 * дедуплицированной осевой трека с lineBlur: мягкая полоса шириной
 * ~1,75 × ширины тропы ([GLOW_TRAIL_RATIO] × 2 × REVEAL_RADIUS_METERS) цветом
 * открытой карты (токен FogGlowColor, светлая — тёплый почти белый) поверх
 * завесы — кромка просвета тает, колец/бус нет. Ширина и размытие — метры
 * (интерполяция по зуму, lineWidth/lineBlur — экранные единицы).
 * Слой рисуется поверх тумана, но ПОД водяным векторным (2026-10-09: ореол не
 * заливает чёткий берег; под POI и маркером «я»). При осевой
 * короче двух точек ореола нет — просвет-точка виден и без него.
 */
private fun updateFogGlowLayer(style: Style, state: MapUiState, glowArgb: Int) {
    val grid = state.grid
    // Осевая — по цепочкам-сессиям (2026-10-08): ореол не соединяет записи.
    val chains = state.ribbonChains.filter { it.size >= 2 }
    if (!state.regionInstalled || grid == null || chains.isEmpty()) {
        style.removeLayer(GLOW_LAYER_ID)
        style.removeSource(GLOW_SOURCE_ID)
        return
    }
    val collection = FeatureCollection.fromFeatures(
        chains.map { chain ->
            Feature.fromGeometry(
                LineString.fromLngLats(chain.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        },
    )
    val centerLat = gridCenterLat(grid)
    val widthStops = zoomStops(REVEAL_RADIUS_METERS * 2.0 * GLOW_TRAIL_RATIO, centerLat)
    val blurStops = zoomStops(REVEAL_RADIUS_METERS * GLOW_BLUR_RATIO, centerLat)
    val source = style.getSource(GLOW_SOURCE_ID) as? GeoJsonSource
    if (source == null) {
        style.addSource(GeoJsonSource(GLOW_SOURCE_ID, collection))
        addTrailEffectLayer(
            style,
            LineLayer(GLOW_LAYER_ID, GLOW_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(glowArgb),
                PropertyFactory.lineWidth(widthStops),
                PropertyFactory.lineBlur(blurStops),
                PropertyFactory.lineOpacity(GLOW_OPACITY),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    } else {
        source.setGeoJson(collection)
        (style.getLayer(GLOW_LAYER_ID) as? LineLayer)?.setProperties(
            PropertyFactory.lineColor(glowArgb),
            PropertyFactory.lineWidth(widthStops),
            PropertyFactory.lineBlur(blurStops),
        )
    }
}

/**
 * Слои эффектов тропы (ореол, перо) кладутся ПОД водяной векторный слой
 * (2026-10-09): береговая линия остаётся чёткой, даже когда слой создаётся
 * mid-session (первая запись) поверх уже стоящей воды. Водяного слоя нет
 * (asset-запас) — обычное добавление наверх.
 */
private fun addTrailEffectLayer(style: Style, layer: Layer) {
    if (style.getLayer(WATER_VECTOR_LAYER_ID) != null) {
        style.addLayerBelow(layer, WATER_VECTOR_LAYER_ID)
    } else {
        style.addLayer(layer)
    }
}

/**
 * Перо кромки тропы («свеча v4», fix10, 2026-10-07): жёсткий срез проёма завесы
 * читался как линия — владелец просила «немного блюра на краях, чтобы ближе к
 * истории с туманом». FillLayer дырок блюрить нельзя, поэтому вдоль КРОМКИ тропы
 * (то же кольцо [trailRing] с радиусом проёма, как замкнутая линия) кладётся
 * LineLayer цветом завесы (FogCoveredColor) с lineBlur: половина полосы лежит на
 * проёме (туманка цвета завесы ползёт внутрь), половина — на завесе (незаметна),
 * градиент размытия съедает ступеньку — переход мягкий
 * ([FEATHER_WIDTH_METERS] + размытие). Выбран из трёх вариантов
 * (кольцо-перышко / линия по кромке / усиление ореола) как дающий градиент, а не
 * ступеньку полупрозрачности, и не трогающий светлую «историю» ореола.
 * Слой поверх тумана и ореола, но ПОД водяным векторным (2026-10-09: берег
 * всегда чёткий; под POI и маркером «я»). Без тропы слоя нет.
 * Радиус [blurMeters] и сила [strength] размытия — параметры (debug-ползунки
 * 2026-10-09; прод—дефолты [FEATHER_BLUR_METERS] / [FEATHER_OPACITY]). Внешний
 * хвост привязан фиксированными отношениями ([FEATHER_OUTER_BLUR_RATIO],
 * [FEATHER_OUTER_OPACITY_RATIO]) — два ползунка вместо четырёх.
 */
private fun updateTrailFeatherLayer(
    style: Style,
    state: MapUiState,
    fogArgb: Int,
    blurMeters: Double = FEATHER_BLUR_METERS,
    strength: Float = FEATHER_OPACITY,
) {
    val grid = state.grid
    if (!state.regionInstalled || grid == null || state.ribbonChains.isEmpty()) {
        style.removeLayer(FEATHER_LAYER_ID)
        style.removeSource(FEATHER_SOURCE_ID)
        style.removeLayer(FEATHER_OUTER_LAYER_ID)
        style.removeSource(FEATHER_OUTER_SOURCE_ID)
        return
    }
    // Кольцо кромки на цепочку-сессию (2026-10-08), как у слоя тумана.
    val rings = state.ribbonChains.map { trailRing(it, REVEAL_RADIUS_METERS) }.filter { it.size >= 2 }
    if (rings.isEmpty()) {
        style.removeLayer(FEATHER_LAYER_ID)
        style.removeSource(FEATHER_SOURCE_ID)
        style.removeLayer(FEATHER_OUTER_LAYER_ID)
        style.removeSource(FEATHER_OUTER_SOURCE_ID)
        return
    }
    val collection = FeatureCollection.fromFeatures(
        rings.map { ring -> Feature.fromGeometry(LineString.fromLngLats(ring.map { it.toPoint() })) },
    )
    val centerLat = gridCenterLat(grid)
    val widthStops = zoomStops(FEATHER_WIDTH_METERS, centerLat)
    val blurStops = zoomStops(blurMeters, centerLat)
    val source = style.getSource(FEATHER_SOURCE_ID) as? GeoJsonSource
    if (source == null) {
        style.addSource(GeoJsonSource(FEATHER_SOURCE_ID, collection))
        addTrailEffectLayer(
            style,
            LineLayer(FEATHER_LAYER_ID, FEATHER_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(fogArgb),
                PropertyFactory.lineWidth(widthStops),
                PropertyFactory.lineBlur(blurStops),
                PropertyFactory.lineOpacity(strength),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    } else {
        source.setGeoJson(collection)
        (style.getLayer(FEATHER_LAYER_ID) as? LineLayer)?.setProperties(
            PropertyFactory.lineColor(fogArgb),
            PropertyFactory.lineWidth(widthStops),
            PropertyFactory.lineBlur(blurStops),
            PropertyFactory.lineOpacity(strength),
        )
    }
    // Внешний хвост градиента (2026-10-08, по багу владельца «края без блюра»):
    // та же кромка, размытие шире и слабее — туман вокруг тропы становится
    // слегка прозрачным на десятки метров, а не на ~6 м одним пером. Следует за
    // debug-ползунками через отношения к основному перу (2026-10-09).
    val outerBlurStops = zoomStops(blurMeters * FEATHER_OUTER_BLUR_RATIO, centerLat)
    val outerOpacity = strength * FEATHER_OUTER_OPACITY_RATIO
    val outerSource = style.getSource(FEATHER_OUTER_SOURCE_ID) as? GeoJsonSource
    if (outerSource == null) {
        style.addSource(GeoJsonSource(FEATHER_OUTER_SOURCE_ID, collection))
        addTrailEffectLayer(
            style,
            LineLayer(FEATHER_OUTER_LAYER_ID, FEATHER_OUTER_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(fogArgb),
                PropertyFactory.lineWidth(zoomStops(FEATHER_OUTER_WIDTH_METERS, centerLat)),
                PropertyFactory.lineBlur(outerBlurStops),
                PropertyFactory.lineOpacity(outerOpacity),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    } else {
        outerSource.setGeoJson(collection)
        (style.getLayer(FEATHER_OUTER_LAYER_ID) as? LineLayer)?.setProperties(
            PropertyFactory.lineColor(fogArgb),
            PropertyFactory.lineWidth(zoomStops(FEATHER_OUTER_WIDTH_METERS, centerLat)),
            PropertyFactory.lineBlur(outerBlurStops),
            PropertyFactory.lineOpacity(outerOpacity),
        )
    }
}

/**
 * Интерполяция по зуму для величины [meters], пересчитанной в экранные пиксели
 * на широте центра сетки (circleRadius/lineWidth в MapLibre — экранные единицы,
 * а тайл сетки — метры): стопы через 2 зума, экспонента 2 (масштаб ×2 на зум).
 */
private fun zoomStops(meters: Double, centerLat: Double): Expression {
    val stops = ArrayList<Expression.Stop>()
    for (zoom in ZOOM_STOPS) {
        val metersPerPixel = METERS_PER_PIXEL_EQUATOR *
            kotlin.math.cos(Math.toRadians(centerLat)) / (1 shl zoom)
        stops.add(Expression.stop(zoom, (meters / metersPerPixel).toFloat()))
    }
    return Expression.interpolate(
        Expression.exponential(2f),
        Expression.zoom(),
        *stops.toTypedArray(),
    )
}

/** Широта центра сетки — для пересчёта метров в пиксели (косинус широты). */
private fun gridCenterLat(grid: FogGrid): Double = (grid.originLat + grid.northLat) / 2.0

/** Высота статус-бара в px (edge-to-edge): системный ресурс, 0 на экзотике. */
private fun statusBarHeightPx(view: MapView): Int {
    val id = view.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) view.resources.getDimensionPixelSize(id) else 0
}

/**
 * Кнопка атрибуции «i» — приглушена (fix10, 2026-10-07): MapLibre не отдаёт её
 * view наружу (UiSettings управляет только отступами/гравитацией), поэтому кнопка
 * находится среди детей MapView по contentDescription (строка SDK) и получает
 * пониженные прозрачность и масштаб. Сама кнопка остаётся кликабельной — лицензия
 * OSM требует доступной атрибуции; логотип выключается отдельно (isLogoEnabled).
 */
private fun muteAttributionButton(mapView: MapView) {
    val description = mapView.context.getString(
        org.maplibre.android.R.string.maplibre_attributionsIconContentDescription,
    )
    val button = (0 until mapView.childCount)
        .asSequence()
        .map { mapView.getChildAt(it) }
        .filterIsInstance<ImageView>()
        .firstOrNull { it.contentDescription == description } ?: return
    button.alpha = ATTRIBUTION_BUTTON_ALPHA
    button.scaleX = ATTRIBUTION_BUTTON_SCALE
    button.scaleY = ATTRIBUTION_BUTTON_SCALE
}

/**
 * Геометрия завесы: внешнее кольцо — весь мир ([FogGrid.worldRing], полевой тест 3),
 * внутренние — буферная тропа «свечи v2» по принятым точкам трека ([trailRing],
 * CW — дырка для MapLibre). Дырки лежат только там, где ходил пользователь;
 * за пределами пакета завеса сплошная. Вода сеткой больше не вырезается
 * (2026-10-09): берег рисует точный векторный слой [updateWaterVectorLayer]
 * поверх завесы — без ступеней 100 м и blur-полосы у воды.
 */
private fun fogPolygon(grid: FogGrid, trails: List<List<DoubleArray>>): Polygon {
    val rings = ArrayList<List<Point>>(trails.size + 1)
    rings.add(FogGrid.worldRing().map { it.toPoint() })
    // «Свеча v2» (2026-10-06): кольцо — буфер вокруг осевой трека; по кольцу на
    // СЕССИЮ записи (2026-10-08): цепочки не соединяются — прямых линий между
    // разными прогулками нет (полевой баг с телефона владельца).
    trails.forEach { trail -> if (trail.isNotEmpty()) rings.add(trail.map { it.toPoint() }) }
    return Polygon.fromLngLats(rings)
}

/**
 * Слой POI-маяков (история 1.9, токен poi-marker): точки акцентом с мягким свечением
 * (circleBlur) и белым кольцом, радиус растёт с зумом. Выдача уже порезана фильтром
 * (тиры по зуму, лимит) — в слой уходит ≤ 800 точек, а не все 58k пакета.
 * Слой добавляется после fog-слоя (в setStyle-колбэке) → рисуется поверх тумана (FR-18).
 * osmId уезжает в свойства Feature — по нему тап находит POI в состоянии viewmodel.
 */
private fun updatePoiLayer(style: Style, state: MapUiState, markerArgb: Int) {
    val pois = state.visiblePois
    if (pois.isEmpty()) {
        style.removeLayer(POI_LAYER_ID)
        style.removeSource(POI_SOURCE_ID)
        return
    }
    val collection = FeatureCollection.fromFeatures(
        pois.map { poi ->
            Feature.fromGeometry(Point.fromLngLat(poi.lon, poi.lat)).apply {
                addStringProperty(POI_PROPERTY_OSM_ID, poi.osmId)
            }
        },
    )
    val source = style.getSource(POI_SOURCE_ID) as? GeoJsonSource
    if (source == null) {
        style.addSource(GeoJsonSource(POI_SOURCE_ID, collection))
        style.addLayer(
            CircleLayer(POI_LAYER_ID, POI_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(markerArgb),
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10f, 4f),
                        Expression.stop(16f, 6.5f),
                    ),
                ),
                PropertyFactory.circleBlur(POI_CIRCLE_BLUR), // мягкое свечение маяка
                PropertyFactory.circleOpacity(POI_CIRCLE_OPACITY),
                PropertyFactory.circleStrokeColor(Color.White.toArgb()),
                PropertyFactory.circleStrokeWidth(POI_STROKE_WIDTH),
            ),
        )
    } else {
        source.setGeoJson(collection)
        // Смена темы на живом слое: цвет маркера перечитывается, как опacность тумана.
        (style.getLayer(POI_LAYER_ID) as? CircleLayer)?.setProperties(
            PropertyFactory.circleColor(markerArgb),
        )
    }
}

private fun DoubleArray.toPoint(): Point = Point.fromLngLat(this[0], this[1])

/**
 * Маркер «я» (DESIGN, слои карты: «маркер "я" — белое ядро, кольцо cursor»):
 * белое ядро + кольцо токеном cursor (#9379E0 тёмная / #7A5FC0 светлая) и мягкое
 * свечение (Elevation: свечение — привилегия маркера пользователя). Поверх всех
 * слоёв карты (добавляется последним). Круг точности — приглушённый cursor-заливкой
 * реального радиуса в метрах (полигон; circleRadius в MapLibre — экранные единицы);
 * прячем при точности лучше 10 м (шум) и хуже 300 м (пятно на весь регион).
 * [visible] = false (фикс устарел/грубый, полевой тест 2 дефект 3) — слои снимаются,
 * UI вместо маркера показывает «Определяю местоположение…».
 * Камера за маркером не следует: кнопки «моё местоположение» в макете нет (DESIGN/прототип).
 */
private fun updateMeLayer(style: Style, state: MapUiState, cursorArgb: Int, visible: Boolean) {
    val lat = state.userLatitude
    val lon = state.userLongitude
    if (lat == null || lon == null || !visible) {
        style.removeLayer(ME_CORE_LAYER_ID)
        style.removeLayer(ME_GLOW_LAYER_ID)
        style.removeSource(ME_SOURCE_ID)
        style.removeLayer(ME_ACCURACY_LAYER_ID)
        style.removeSource(ME_ACCURACY_SOURCE_ID)
        return
    }

    // Круг точности: полигон радиусом accuracy метров под маркером.
    val accuracy = state.userAccuracyMeters
    if (accuracy != null && accuracy in ME_ACCURACY_MIN_METERS..ME_ACCURACY_MAX_METERS) {
        val polygon = Polygon.fromLngLats(listOf(accuracyRing(lat, lon, accuracy.toDouble())))
        val accuracySource = style.getSource(ME_ACCURACY_SOURCE_ID) as? GeoJsonSource
        if (accuracySource == null) {
            style.addSource(GeoJsonSource(ME_ACCURACY_SOURCE_ID, Feature.fromGeometry(polygon)))
            style.addLayer(
                FillLayer(ME_ACCURACY_LAYER_ID, ME_ACCURACY_SOURCE_ID).withProperties(
                    PropertyFactory.fillColor(cursorArgb),
                    PropertyFactory.fillOpacity(ME_ACCURACY_OPACITY),
                ),
            )
        } else {
            accuracySource.setGeoJson(polygon)
            (style.getLayer(ME_ACCURACY_LAYER_ID) as? FillLayer)?.setProperties(
                PropertyFactory.fillColor(cursorArgb),
            )
        }
    } else {
        style.removeLayer(ME_ACCURACY_LAYER_ID)
        style.removeSource(ME_ACCURACY_SOURCE_ID)
    }

    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    val source = style.getSource(ME_SOURCE_ID) as? GeoJsonSource
    if (source == null) {
        style.addSource(GeoJsonSource(ME_SOURCE_ID, feature))
        // Свечение под ядром (мягкое, cursor-цветом).
        style.addLayer(
            CircleLayer(ME_GLOW_LAYER_ID, ME_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(cursorArgb),
                PropertyFactory.circleRadius(ME_GLOW_RADIUS),
                PropertyFactory.circleBlur(1f),
                PropertyFactory.circleOpacity(ME_GLOW_OPACITY),
            ),
        )
        // Белое ядро с кольцом cursor (токен me-marker).
        style.addLayer(
            CircleLayer(ME_CORE_LAYER_ID, ME_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(Color.White.toArgb()),
                PropertyFactory.circleRadius(ME_CORE_RADIUS),
                PropertyFactory.circleStrokeColor(cursorArgb),
                PropertyFactory.circleStrokeWidth(ME_RING_WIDTH),
            ),
        )
    } else {
        source.setGeoJson(feature)
        // Смена темы на живом слое: кольцо и свечение перечитывают cursor-токен.
        (style.getLayer(ME_GLOW_LAYER_ID) as? CircleLayer)?.setProperties(
            PropertyFactory.circleColor(cursorArgb),
        )
        (style.getLayer(ME_CORE_LAYER_ID) as? CircleLayer)?.setProperties(
            PropertyFactory.circleStrokeColor(cursorArgb),
        )
    }
}

/** Кольцо радиуса [radiusMeters] вокруг точки — общий генератор кругов (tracking/TrackRibbon). */
private fun accuracyRing(lat: Double, lon: Double, radiusMeters: Double): List<Point> =
    circleRing(lat, lon, radiusMeters, vertices = ACCURACY_RING_VERTICES).map { it.toPoint() }

/** MapView с привязкой к жизненному циклу композиции. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

private const val FOG_SOURCE_ID = "fog-source"
private const val FOG_LAYER_ID = "fog-layer"
/**
 * Туман скрывает карту (разбор владельца 2026-10-05): слой полностью непрозрачен.
 * Дефолт; debug-ползунок (2026-10-09) подбирает прозрачность живьём.
 */
/**
 * Непрозрачность завесы тумана (решение владельца 2026-10-11): 85% — лавандовая
 * завеса плотная, город скрыт, но едва угадывается силуэт («за ним что-то есть»).
 */
private const val FOG_OPACITY = 0.85f

private const val GLOW_SOURCE_ID = "fog-glow-source"
private const val GLOW_LAYER_ID = "fog-glow-layer"
/**
 * «Свеча v2» (2026-10-06): ширина ореола — в радиусах тропы (полоса по осевой
 * трека с lineBlur): 1,75 × радиуса проёма — кромка тропы перекрыта, дальше
 * свечение тает в тумане (калибровка по скриншотам fix8-verify, spike 1.11).
 */
private const val GLOW_TRAIL_RATIO = 1.75
/** Размытие края ореола в радиусах тропы: мягкий спад без «колец». */
private const val GLOW_BLUR_RATIO = 0.5
/** Ореол спокойный (разбор владельца 2026-10-06): ниже прежних 0,45. */
private const val GLOW_OPACITY = 0.30f

private const val FEATHER_SOURCE_ID = "fog-feather-source"
private const val FEATHER_LAYER_ID = "fog-feather-layer"
/** Ядро линии пера вдоль кромки проёма, метры («свеча v4», fix10). */
private const val FEATHER_WIDTH_METERS = 2.0
/**
 * Размытие пера по обе стороны кромки, метры. Баг владельца 2026-10-08 («края
 * тропы без блюра»): 6 м читались жёстким срезом на реальном экране — удвоено
 * с расширением внешнего хвоста отдельным слоем (FEATHER_OUTER_*).
 */
private const val FEATHER_BLUR_METERS = 14.0
/** Перо полупрозрачное: градиент blur доводит растушёвку, кромка не линия. */
private const val FEATHER_OPACITY = 0.55f

/**
 * Внешний хвост градиента кромки (2026-10-08): широкое слабое размытие поверх
 * пера — туман вокруг тропы «немного прозрачный» на десятки метров (запрос
 * владельца: «блюр заходит в туман»). Тот же источник-кромка, отдельный слой.
 * Размытие и сила хвоста привязаны к основному перу отношениями (2026-10-09,
 * debug-ползунки двигают перо — хвост следует): 34 м / 14 м и 0,22 / 0,55.
 */
private const val FEATHER_OUTER_SOURCE_ID = "fog-feather-outer-source"
private const val FEATHER_OUTER_LAYER_ID = "fog-feather-outer-layer"
private const val FEATHER_OUTER_WIDTH_METERS = 1.5
private const val FEATHER_OUTER_BLUR_RATIO = 34.0 / FEATHER_BLUR_METERS
private const val FEATHER_OUTER_OPACITY_RATIO = 0.22f / FEATHER_OPACITY

/** Кнопка атрибуции (fix10): лицензия OSM требует её сохранить, вес снижен. */
private const val ATTRIBUTION_BUTTON_ALPHA = 0.5f
private const val ATTRIBUTION_BUTTON_SCALE = 0.8f

/** Тег диагностических логов слоя тумана (время перестроения геометрии «свечи»). */
private const val FOG_TAG = "WalkQuestFog"

/** Debug-режим (1.14 + 2026-10-09): второй тогл — 5 тапов по карте за 1,5 с (debuggable-сборки). */
private const val DEBUG_TAP_COUNT = 5
private const val DEBUG_TAP_WINDOW_MS = 1_500L

/** Диапазоны debug-ползунков (2026-10-09): вокруг текущей калибровки с запасом. */
private val DEBUG_FEATHER_BLUR_RANGE = 0f..40f // метры размытия пера кромки
private val DEBUG_FEATHER_STRENGTH_RANGE = 0f..1f // непрозрачность пера
private val DEBUG_FOG_OPACITY_RANGE = 0f..1f // непрозрачность завесы

// Точные контуры воды поверх завесы (полевой тест 2, дефект 1; единственный рендер
// воды с 2026-10-09): слой из векторного источника пакета; конвенция
// tools/region-pack — источник «region», слой OMT «water».
private const val WATER_VECTOR_LAYER_ID = "water-veil-layer"
private const val REGION_TILES_SOURCE_ID = "region"
private const val WATER_TILES_SOURCE_LAYER = "water"
private const val BASE_WATER_LAYER_ID = "water"
private const val WATER_CLASS_PROPERTY = "class"
/** Запасной цвет воды = fill-color слоя water в style.json пакета (#9ec9e0). */
private val FALLBACK_WATER_COLOR = android.graphics.Color.parseColor("#9ec9e0")

/** Запас границ камеры за bbox региона (дефект 5): доля пролёта по каждой оси. */
private const val CAMERA_BOUNDS_MARGIN = 0.20

/** Период перепроверки свежести фикса маркера «я» (дефект 3), мс. */
private const val LOCATION_FRESHNESS_TICK_MS = 10_000L

/** Зумы-стопы интерполяции метров в пиксели (шаг 2, покрытие обзорный..домовой). */
private val ZOOM_STOPS = 8..18 step 2
/** Метров на пиксель на экваторе на зуме 0 (Web Mercator). */
private const val METERS_PER_PIXEL_EQUATOR = 156543.03392

private const val POI_SOURCE_ID = "poi-source"
private const val POI_LAYER_ID = "poi-layer"
private const val POI_PROPERTY_OSM_ID = "osmId"
private const val POI_CIRCLE_BLUR = 0.7f // токен poi-marker: мягкое свечение
private const val POI_CIRCLE_OPACITY = 0.95f
private const val POI_STROKE_WIDTH = 1.5f
private const val POI_TAP_TOLERANCE_PX = 24f // допуск попадания пальцем в маркер
private const val POI_CARD_DISMISS_DRAG_PX = 120f // свайп вниз, закрывающий карточку
private val POI_PHOTO_HEIGHT = 96.dp // полоса фото карточки (макет poi-card .photos)
/** Описание карточки: максимум строк с многоточием (полевой тест 3); дальше — «Подробнее». */
private const val POI_DESCRIPTION_MAX_LINES = 4

/** Компас: базовые поля от углов экрана; верхний — ещё + высота статус-бара. */
private const val COMPASS_MARGIN_DP = 8

/** Центровка «к моей точке»: с обзорного зума поднимаемся до районного. */
private const val RECENTER_MIN_ZOOM = 15.0

private const val ME_SOURCE_ID = "me-source"
private const val ME_GLOW_LAYER_ID = "me-glow-layer"
private const val ME_CORE_LAYER_ID = "me-core-layer"
private const val ME_ACCURACY_SOURCE_ID = "me-accuracy-source"
private const val ME_ACCURACY_LAYER_ID = "me-accuracy-layer"
private const val ME_CORE_RADIUS = 6f // белое ядро (прототип .me: точка 18px с кольцом 4px)
private const val ME_RING_WIDTH = 3f // кольцо токеном cursor
private const val ME_GLOW_RADIUS = 12f
private const val ME_GLOW_OPACITY = 0.45f
private const val ME_ACCURACY_OPACITY = 0.15f // приглушённый cursor
private const val ME_ACCURACY_MIN_METERS = 10f // точнее — круг визуальный шум
private const val ME_ACCURACY_MAX_METERS = 300f // грубее — пятно на весь регион
private const val ACCURACY_RING_VERTICES = 64

/** Лёгкий слушатель маркера «я» (без записи): фикс раз в 2 с / от 2 м — реже записи (1 с/1 м). */
private const val ME_LOCATION_MIN_INTERVAL_MS = 2_000L
private const val ME_LOCATION_MIN_DISTANCE_METERS = 2f

/**
 * Финальный экран прогулки (история 2.8, FR-8): похвала, процент открытия,
 * время, дистанция, новые ачивки (порт; пусто до epic-achievements), подпись
 * про завтра, «Поделиться» и «Закрыть». Все значения сводки — готовые из
 * viewmodel/domain (AD-4): здесь только рендер и форматирование.
 */
@Composable
private fun WalkSummaryOverlay(
    summary: WalkSummaryUi,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = summary.praise,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(24.dp))
            summary.progressFraction?.let { fraction ->
                Text(
                    text = formatPercent(fraction),
                    style = MaterialTheme.typography.displaySmall,
                    color = AccentTextColor,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "территории открыто",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondaryColor,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            val minutes = summary.activeMillis / 60_000L
            Text(
                text = "%.1f км · %d мин".format(summary.distanceMeters / 1000.0, minutes),
                style = MaterialTheme.typography.titleMedium,
            )
            if (summary.achievements.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                summary.achievements.forEach { achievement ->
                    Text(
                        text = "🏆 $achievement",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = app.walkquest.domain.WalkPraise.TOMORROW_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondaryColor,
            )
            Spacer(modifier = Modifier.height(32.dp))
            WalkQuestPrimaryButton(onClick = onShare) { Text(text = "Поделиться") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onClose) { Text(text = "Закрыть") }
        }
    }
}

/** Текстовая карточка результата для ACTION_SEND (2.8, FR-8). */
private fun shareWalkIntent(summary: WalkSummaryUi): Intent {
    val percent = summary.progressFraction?.let { " · " + formatPercent(it) } ?: ""
    val text = "${summary.praise}\n" +
        "%.1f км · %d мин$percent\n".format(summary.distanceMeters / 1000.0, summary.activeMillis / 60_000L) +
        "\nWalkQuest — город под туманом"
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
}
