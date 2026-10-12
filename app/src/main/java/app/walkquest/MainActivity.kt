package app.walkquest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.walkquest.ui.components.WqBottomNav
import app.walkquest.ui.components.WqNavItem
import app.walkquest.ui.map.MapScreen
import app.walkquest.ui.regions.RegionsScreen
import app.walkquest.ui.rating.RatingScreen
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.viewmodel.MapViewModel
import app.walkquest.viewmodel.RegionsViewModel
import app.walkquest.viewmodel.AchievementsViewModel
import app.walkquest.viewmodel.RatingViewModel
import app.walkquest.viewmodel.StatsViewModel

/**
 * Единственная Activity приложения (MVVM + UDF на Compose, architecture spine).
 *
 * Четыре вкладки (EXPERIENCE: Карта · Прогресс · Рейтинг · Профиль): «Карта»
 * (1.6–1.9), «Прогресс» — сегменты Статистика/Достижения/Задания (3.4, 4.4, 4.5),
 * «Рейтинг» (5.x), «Профиль» (6.3: перенос прогресса, «Мои регионы» 1.10,
 * «О приложении»). Регионы — экран первого уровня из Профиля → «Мои регионы»
 * (модальных стеков глубже одного уровня нет); настройки — оверлей с шестерёнки
 * главного экрана. MapViewModel общий для карты и региона: переход к карте региона
 * из списка = событие onRegionSelected во viewmodel + переключение вкладки;
 * состояние карты (активный регион, туман, POI) переживает переключения, т.к.
 * живёт во viewmodel активности.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Умные напоминания по цели (3.5, FR-14): ежедневная вечерняя проверка;
        // идемпотентно, час — из настроек. Переживание перезагрузки — к boot-
        // ресиверу (deferred-work: приложение стартует ежедневно и так).
        app.walkquest.reminder.GoalReminderReceiver.schedule(
            this,
            (application as WalkQuestApplication).container.settingsRepository.goalReminderHour(),
        )
        setContent {
            WalkQuestTheme {
                WalkQuestTabs()
            }
        }
    }
}

private enum class AppTab(val title: String, val icon: ImageVector) {
    Map("Карта", TabIcons.Map),
    Progress("Прогресс", TabIcons.Stats),
    Rating("Рейтинг", TabIcons.Rating),
    Profile("Профиль", TabIcons.Profile),
}

/**
 * Векторные иконки вкладок (DESIGN: «векторные иконки с подписями», эмодзи — только
 * плейсхолдеры макетов). Рисуются вручную 24dp-путями: material-icons-зависимость
 * не вводим ради двух глифов. Тон задаёт NavigationBarItem (тint).
 */
private object TabIcons {
    /** Маркер на карте. */
    val Map: ImageVector = pathIcon(
        "tab-map",
        "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7z" +
            "m0 9.5c-1.38 0-2.5-1.12-2.5-2.5S10.62 6.5 12 6.5s2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
    )

    /** Список регионов. */
    val Regions: ImageVector = pathIcon(
        "tab-regions",
        "M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z",
    )

    /** Статистика: восходящий столбец. */
    val Stats: ImageVector = pathIcon(
        "tab-stats",
        "M4 20V10h3v10H4zm6.5 0V4h3v16h-3zM17 20v-7h3v7h-3z",
    )

    /** Награды: кубок. */
    val Achievements: ImageVector = pathIcon(
        "tab-achievements",
        "M19 5h-2V3H7v2H5c-1.1 0-2 .9-2 2v1c0 2.55 1.92 4.63 4.39 4.94.63 1.5 1.98 2.63 3.61 2.96V19H7v2h10v-2h-4v-3.1c1.63-.33 2.98-1.46 3.61-2.96C19.08 12.63 21 10.55 21 8V7c0-1.1-.9-2-2-2zM5 8V7h2v3.82C5.84 10.4 5 9.3 5 8zm14 0c0 1.3-.84 2.4-2 2.82V7h2v1z",
    )

    /** Рейтинг: лента/флаг финиша. */
    val Rating: ImageVector = pathIcon(
        "tab-rating",
        "M12 2 4 5v2h16V5l-8-3zm-8 8v2h4v9h2v-9h4v9h2v-9h4v-2H4z",
    )

    /** Профиль: силуэт человека (история 6.3). */
    val Profile: ImageVector = pathIcon(
        "tab-profile",
        "M12 12c2.7 0 4.8-2.1 4.8-4.8S14.7 2.4 12 2.4 7.2 4.5 7.2 7.2 9.3 12 12 12zm0 " +
            "2.4c-3.2 0-9.6 1.6-9.6 4.8v2.4h19.2v-2.4c0-3.2-6.4-4.8-9.6-4.8z",
    )

    private fun pathIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
}

@Composable
private fun ComponentActivity.WalkQuestTabs() {
    val container = (application as WalkQuestApplication).container
    val mapViewModel: MapViewModel = viewModel(factory = container.mapViewModelFactory)
    val regionsViewModel: RegionsViewModel = viewModel(factory = container.regionsViewModelFactory)
    val statsViewModel: StatsViewModel = viewModel(factory = container.statsViewModelFactory)
    val achievementsViewModel: AchievementsViewModel = viewModel(factory = container.achievementsViewModelFactory)
    val ratingViewModel: RatingViewModel = viewModel(factory = container.ratingViewModelFactory)
    val profileViewModel: app.walkquest.viewmodel.ProfileViewModel =
        viewModel(factory = container.profileViewModelFactory)
    var tab by rememberSaveable { mutableStateOf(AppTab.Map.name) }
    // Оверлеи первого уровня (EXPERIENCE: модальных стеков глубже одного нет):
    // регионы — из Профиля → «Мои регионы», настройки — с шестерёнки главного.
    var regionsOverlayVisible by remember { mutableStateOf(false) }
    var settingsOverlayVisible by remember { mutableStateOf(false) }
    // Онбординг первого запуска полноэкранный — нижнюю навигацию под ним скрываем.
    val mapUiState by mapViewModel.uiState.collectAsStateWithLifecycle()

    // Перечитываем статистику и награды при каждом входе на вкладку Прогресс:
    // прогулка могла завершиться, пока вкладка была неактивна (FR-13/FR-15).
    androidx.compose.runtime.LaunchedEffect(tab) {
        if (tab == AppTab.Progress.name) {
            statsViewModel.reload()
            achievementsViewModel.reload()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (AppTab.valueOf(tab)) {
                AppTab.Map -> MapScreen(
                    viewModel = mapViewModel,
                    statsViewModel = statsViewModel,
                    onOpenSettings = { settingsOverlayVisible = true },
                )
                AppTab.Progress -> app.walkquest.ui.progress.ProgressScreen(
                    statsViewModel = statsViewModel,
                    achievementsViewModel = achievementsViewModel,
                    mapViewModel = mapViewModel,
                )
                AppTab.Rating -> RatingScreen(viewModel = ratingViewModel)
                AppTab.Profile -> app.walkquest.ui.profile.ProfileScreen(
                    viewModel = profileViewModel,
                    onOpenRegions = { regionsOverlayVisible = true },
                )
            }
            // «Мои регионы» (1.10): список регионов поверх вкладки; тап по региону —
            // его карта на вкладке Карта; выход — явная кнопка «Готово» (без стрелок).
            if (regionsOverlayVisible) {
                RegionsScreen(
                    viewModel = regionsViewModel,
                    onOpenRegion = { regionId ->
                        mapViewModel.onRegionSelected(regionId)
                        tab = AppTab.Map.name
                        regionsOverlayVisible = false
                    },
                    onClose = { regionsOverlayVisible = false },
                )
            }
            if (settingsOverlayVisible) {
                app.walkquest.ui.settings.SettingsScreen(
                    viewModel = mapViewModel,
                    onClose = { settingsOverlayVisible = false },
                )
            }
        }
        // Навигация скрыта, пока идёт онбординг: он полноэкранный (4 вкладки,
        // EXPERIENCE; подписи 12px в одну строку — без переносов «Статистика»).
        if (!mapUiState.showOnboarding) {
            WqBottomNav(
                items = AppTab.entries.map { WqNavItem(label = it.title, icon = it.icon) },
                selectedIndex = AppTab.entries.indexOfFirst { it.name == tab }.coerceAtLeast(0),
                onSelect = { index -> tab = AppTab.entries[index].name },
            )
        }
    }
}
