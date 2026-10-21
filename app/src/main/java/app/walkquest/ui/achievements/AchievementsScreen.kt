package app.walkquest.ui.achievements

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.components.WqLockedCard
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.AchievementsViewModel
import app.walkquest.viewmodel.AchievementRowUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сегмент «Достижения» вкладки «Прогресс» (история 7.5, AL-5): две сворачиваемые
 * секции «Полученные · N» / «Неполученные · N» по утверждённому макету
 * progress-tasks-duo.html (секция «Прогресс · Достижения»). Состояние по умолчанию
 * — развёрнуты, индикатор — шеврон «▾»/«▸». Неполученные — пунктирная рамка
 * (WqLockedCard) и приглушение значка (прозрачность по макету `.lock .ic`);
 * у близких (прогресс > 0) — полоска 4dp в колонке текста. Только токены WqTheme.
 * Перепроверка по истории — во viewmodel при входе (FR-15).
 */
@Composable
fun AchievementsScreen(viewModel: AchievementsViewModel, embedded: Boolean = false) {
    val rows by viewModel.uiState.collectAsState()
    val earned = rows.filter { it.earnedAt != null }
    val locked = rows.filter { it.earnedAt == null }

    var earnedExpanded by rememberSaveable { mutableStateOf(true) }
    var lockedExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
            .verticalScroll(rememberScrollState())
            .padding(if (embedded) WqTheme.spacing.lg else WqTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
    ) {
        if (!embedded) {
            Text(
                text = "Награды",
                style = WqTheme.typography.headingScreen,
                color = WqTheme.colors.inkHeadline,
            )
        }
        AchievementSectionHeader(
            label = "Полученные",
            count = earned.size,
            expanded = earnedExpanded,
            onToggle = { earnedExpanded = !earnedExpanded },
        )
        AnimatedVisibility(
            visible = earnedExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm)) {
                if (earned.isEmpty()) {
                    // Честное пустое состояние чистой установки (spec §5: короткая
                    // пустая подпись — экран не пустой, секция на месте).
                    Text(
                        text = "Пока ни одного — первая прогулка его откроет",
                        style = WqTheme.typography.meta,
                        color = WqTheme.colors.inkDim,
                    )
                } else {
                    earned.forEach { AchievementRow(it) }
                }
            }
        }
        AchievementSectionHeader(
            label = "Неполученные",
            count = locked.size,
            expanded = lockedExpanded,
            onToggle = { lockedExpanded = !lockedExpanded },
        )
        AnimatedVisibility(
            visible = lockedExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm)) {
                locked.forEach { AchievementRow(it) }
            }
        }
    }
}

/** Заголовок сворачиваемой секции: мета-стиль макета `.sec-l` (uppercase,
 *  разрядка, ink-dim), счётчик и шеврон состояния. Hit-area ≥ 48dp. */
@Composable
private fun AchievementSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(top = WqTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${label.uppercase(Locale("ru", "RU"))} · $count ${if (expanded) "▾" else "▸"}",
            style = WqTheme.typography.meta.copy(letterSpacing = 0.1.em),
            color = WqTheme.colors.inkDim,
        )
    }
}

/** Строка достижения: значок 32dp в подложке (полученный/приглушенный),
 *  название, подпись-условие/дата, у близких — полоска прогресса 4dp. */
@Composable
private fun AchievementRow(row: AchievementRowUi) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = WqTheme.spacing.sm, horizontal = WqTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(WqTheme.shapes.small)
                    .background(WqTheme.colors.surfaceBase)
                    // Приглушение неполученного значка — прозрачность по макету
                    // (.aitem.lock .ic: opacity .45; grayscale макета эмодзи не тонирует).
                    .alpha(if (row.earnedAt != null) 1f else 0.45f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.icon,
                    style = WqTheme.typography.headingSection,
                )
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        style = WqTheme.typography.headingCard,
                        color = WqTheme.colors.inkHeadline,
                    )
                    if (row.rare) {
                        Text(
                            text = " ✦",
                            color = WqTheme.colors.accentText,
                            style = WqTheme.typography.headingCard,
                        )
                    }
                }
                val subtitle = when {
                    row.earnedAt != null ->
                        "Получено " + SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU"))
                            .format(Date(row.earnedAt))
                    else -> row.conditionText
                }
                Text(
                    text = subtitle,
                    style = WqTheme.typography.meta,
                    color = WqTheme.colors.inkDim,
                )
                // Полоска только у «близких» с визуально ненулевым прогрессом
                // (по макету у «Хозяина города» с 0% её нет). Порог 1% цели:
                // меньше занимает ~точку и читается как «пустой трек» (ревью 7.5) —
                // суб-пороговый прогресс («<0,1%» региона) не рисуем вообще.
                row.progress
                    ?.takeIf { (current, target) -> target > 0 && current / target >= 0.01 }
                    ?.let { (current, target) ->
                        LinearProgressIndicator(
                            progress = { (current / target).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = WqTheme.spacing.xs)
                                .height(4.dp),
                            color = WqTheme.colors.accent,
                            trackColor = WqTheme.colors.progressTrack,
                            // Макет .pbar — чистая полоска без круглого stop-indicator.
                            drawStopIndicator = {},
                        )
                    }
            }
        }
    }
    if (row.earnedAt != null) {
        WqCard { content() }
    } else {
        WqLockedCard { content() }
    }
}
