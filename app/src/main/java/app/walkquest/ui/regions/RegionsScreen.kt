package app.walkquest.ui.regions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.logging.ScreenLogEffect
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.components.WqGhostButton
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.RegionRowUi
import app.walkquest.viewmodel.RegionsViewModel
import java.util.Locale

/**
 * Экран «Мои регионы» (история 1.10, FR-4; EXPERIENCE: Профиль → «Мои регионы»,
 * модальных стеков глубже одного уровня нет): список установленных регионов
 * строками «процент · название · подпись». Спецификация: screens-spec.md §9.
 * Процент домашнего — акцентом (accentText), чужих — приглушённым ink-secondary;
 * подпись домашнего — «домашний регион». Действия:
 * тап по строке → карта региона; «Домашний» — сделать домашним (персистентно);
 * [onClose] — явный выход («Готово» внизу, стрелок «назад» нет, DESIGN).
 * Прогресс регионов не смешивается: каждая строка — доля из domain по своему
 * пакету и своей биткарте (AD-9). UI только форматирует («17,4%», ru-локаль).
 */
@Composable
fun RegionsScreen(
    viewModel: RegionsViewModel,
    onOpenRegion: (String) -> Unit,
    onClose: (() -> Unit)? = null,
) {
    ScreenLogEffect("RegionsScreen")
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Возврат на вкладку (например, после установки региона с карты) → свежие проценты.
    LaunchedEffect(Unit) { viewModel.reload() }

    // Edge-to-edge (дефект 5 разбора 2026-10-05): заголовок уезжал под статус-бар —
    // фон по токену surface-base (обе темы) + отступ статус-бара у корня вкладки.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WqTheme.colors.surfaceBase)
            .statusBarsPadding(),
    ) {
        Text(
            text = "Регионы",
            color = WqTheme.colors.inkHeadline,
            style = WqTheme.typography.headingScreen,
            modifier = Modifier.padding(
                start = WqTheme.spacing.lg,
                end = WqTheme.spacing.lg,
                top = WqTheme.spacing.lg,
                bottom = 10.dp,
            ),
        )
        uiState.lastError?.let { error ->
            Text(
                text = error,
                color = WqTheme.colors.error,
                style = WqTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 4.dp),
            )
        }
        if (uiState.rows.isEmpty()) {
            Text(
                text = "Пока нет установленных регионов. Загрузите домашний регион — карточка на вкладке «Карта».",
                color = WqTheme.colors.inkSecondary,
                style = WqTheme.typography.body,
                modifier = Modifier.padding(horizontal = WqTheme.spacing.lg),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = WqTheme.spacing.lg,
                end = WqTheme.spacing.lg,
                bottom = WqTheme.spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
        ) {
            items(uiState.rows, key = { it.regionId }) { row ->
                RegionRow(
                    row = row,
                    updating = uiState.updatingRegionId == row.regionId,
                    updateInProgress = uiState.updatingRegionId != null,
                    onOpen = { onOpenRegion(row.regionId) },
                    onSetHome = { viewModel.onSetHomeRegion(row.regionId) },
                    onUpdate = { viewModel.onUpdateRegion(row.regionId) },
                )
            }
            // Явный выход с экрана (DESIGN: «назад» — жестом или кнопкой-выходом
            // внизу; видна, только когда экран открыт оверлеем из Профиля).
            if (onClose != null) {
                item(key = "close") {
                    WqGhostButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(text = "Готово") }
                }
            }
        }
    }
}

/** Строка региона: процент (домашний — акцентом), название, подпись, действия. */
@Composable
private fun RegionRow(
    row: RegionRowUi,
    updating: Boolean,
    updateInProgress: Boolean,
    onOpen: () -> Unit,
    onSetHome: () -> Unit,
    onUpdate: () -> Unit,
) {
    WqCard(onClick = onOpen, tonalElevation = 1.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.md),
        ) {
            Text(
                text = row.fraction?.let { formatPercent(it) } ?: "—",
                color = if (row.isHome) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
                style = WqTheme.typography.headingCard,
                modifier = Modifier.padding(end = WqTheme.spacing.md),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    color = WqTheme.colors.inkHeadline,
                    style = WqTheme.typography.headingCard,
                )
                Text(
                    text = when {
                        row.isHome -> "домашний регион"
                        else -> "установлен"
                    },
                    color = WqTheme.colors.inkDim,
                    style = WqTheme.typography.meta,
                )
            }
            // Обновление пакета (дефект 7 разбора 2026-10-05): манифест предлагает
            // версию выше установленной → действие «Обновить» через InstallRegionPackage.
            if (row.updateAvailable) {
                TextButton(onClick = onUpdate, enabled = !updateInProgress) {
                    Text(
                        text = if (updating) "Обновляется…" else "Обновить",
                        color = WqTheme.colors.accentText,
                    )
                }
            }
            if (!row.isHome) {
                TextButton(onClick = onSetHome) {
                    Text(text = "Домашний", color = WqTheme.colors.inkSecondary)
                }
            }
        }
    }
}

/**
 * Единственное место форматирования процента во вкладке: доля → «17,4%» (ru-локаль, запятая).
 * Первые тайлы региона — доли процента: вместо обнуляющего «0,0%» честный «<0,1%» (дефект D4).
 */
private fun formatPercent(fraction: Double): String {
    val percent = fraction * 100.0
    if (percent > 0.0 && percent < PERCENT_LOW_THRESHOLD) return "<0,1%"
    return String.format(Locale.forLanguageTag("ru-RU"), "%.1f%%", percent)
}

/** Порог «<0,1%»: всё ниже 0,05% округлялось бы «%.1f» в «0,0%». */
private const val PERCENT_LOW_THRESHOLD = 0.05
