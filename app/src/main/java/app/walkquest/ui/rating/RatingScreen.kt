package app.walkquest.ui.rating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.walkquest.logging.ScreenLogEffect
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.components.WqLeaderboardRow
import app.walkquest.ui.components.WqPrimaryButton
import app.walkquest.ui.components.WqSegmentPill
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.RatingUiState
import app.walkquest.viewmodel.RatingViewModel
import java.util.Locale

/**
 * Экран рейтинга (эпик 5, FR-19; сведение к макету — сторя 7.7 эпика
 * design-alignment): сегменты «Город»/«Друзья» (WqSegmentPill), строки
 * лидерборда по макету (место · ник · значение; своя строка — WqCardActive
 * + чип «вы», WqLeaderboardRow), первый вход — CTA-карточка «Придумайте ник»
 * (.nick-cta макета: подпись, поле, «Вступить в рейтинг» акцентом и текст-
 * кнопка «Остаться невидимым» — копии/поведение EXPERIENCE сохранены).
 * Участие опционально, клиент отправляет только агрегаты (AD-3). Спецификация:
 * screens-spec.md §7; токены только WqTheme, hex только в ui/theme.
 */
@Composable
fun RatingScreen(viewModel: RatingViewModel) {
    ScreenLogEffect("RatingScreen")
    val ui by viewModel.uiState.collectAsState()

    // Топ подгружаем при первом показе вкладки (честное loading-состояние §7).
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WqTheme.colors.surfaceBase)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(WqTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.md),
    ) {
        Text(
            text = "Рейтинг",
            style = WqTheme.typography.headingScreen,
            color = WqTheme.colors.inkHeadline,
        )

        // Сегменты «Город / Друзья» (макет .seg; контурный активный — WqSegmentPill, 7.4)
        // + текст-кнопка «Обновить» (спека §7).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WqSegmentPill(
                label = "Город",
                selected = ui.cityTab,
                onClick = { viewModel.onTabSelected(true) },
            )
            WqSegmentPill(
                label = "Друзья",
                selected = !ui.cityTab,
                onClick = { viewModel.onTabSelected(false) },
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.refresh() }) {
                Text("Обновить", color = WqTheme.colors.inkDim)
            }
        }

        if (ui.nickname == null && !ui.declined) {
            NickPrompt(ui, viewModel)
        } else {
            Board(ui, viewModel)
        }
    }
}

/** Первый вход (ник не задан): CTA-карточка «Придумайте ник» (.nick-cta). */
@Composable
private fun NickPrompt(ui: RatingUiState, viewModel: RatingViewModel) {
    var input by remember { mutableStateOf("") }
    WqCard {
        Column(
            modifier = Modifier.padding(WqTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
        ) {
            Text(
                text = "Придумайте ник",
                style = WqTheme.typography.headingCard,
                color = WqTheme.colors.inkHeadline,
            )
            Text(
                text = "Так вас увидят другие. История передвижений остаётся " +
                    "на устройстве — в рейтинг уходят только ник и цифры.",
                style = WqTheme.typography.bodySmall,
                color = WqTheme.colors.inkDim,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Ник") },
                isError = ui.nicknameError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            ui.nicknameError?.let {
                Text(
                    text = it,
                    style = WqTheme.typography.meta,
                    color = WqTheme.colors.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            WqPrimaryButton(
                onClick = { viewModel.onNicknameSubmitted(input) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Вступить в рейтинг")
            }
            TextButton(onClick = { viewModel.onInvisibleMode() }) {
                Text("Остаться невидимым", color = WqTheme.colors.inkSecondary)
            }
        }
    }
}

/** Основное состояние: строка «Вы: …», лидерборд вкладки, приглашения, notice. */
@Composable
private fun Board(ui: RatingUiState, viewModel: RatingViewModel) {
    val nickname = ui.nickname
    Text(
        text = when {
            nickname.isNullOrBlank() -> "Вы: невидимый режим"
            !ui.visible -> "Вы: $nickname (невидимый режим)"
            else -> "Вы: $nickname"
        },
        style = WqTheme.typography.body,
        color = WqTheme.colors.inkPrimary,
    )
    // EXPERIENCE: скрытый из рейтингов — видит таблицу + подсказку.
    if (!ui.visible && !nickname.isNullOrBlank()) {
        Text(
            text = "вы скрыты; включите участие в настройках",
            style = WqTheme.typography.bodySmall,
            color = WqTheme.colors.inkDim,
        )
    }

    if (ui.cityTab) {
        if (ui.loading && ui.top.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = WqTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        ui.top.forEachIndexed { index, entry ->
            WqLeaderboardRow(
                rank = index + 1,
                nickname = entry.nickname,
                value = formatMetric(entry.distanceMeters, entry.fraction),
                isCurrentUser = entry.isCurrentUser,
            )
        }
        if (ui.top.isEmpty() && !ui.loading) {
            Text(
                text = "Пока пусто — станьте первым.",
                style = WqTheme.typography.bodySmall,
                color = WqTheme.colors.inkDim,
            )
        }
    } else {
        // «Друзья»: бэкенда дружбы нет (v1 — invite-коды), честное пустое
        // состояние по спеке §7 + приглашение по коду.
        Text(
            text = "Пока здесь только вы. Пригласите друга по ссылке",
            style = WqTheme.typography.bodySmall,
            color = WqTheme.colors.inkDim,
        )
        ui.inviteCode?.let {
            Text(
                text = "Код приглашения: $it — отправьте другу.",
                style = WqTheme.typography.bodySmall,
                color = WqTheme.colors.inkSecondary,
            )
        }
        TextButton(onClick = { viewModel.onCreateInvite() }) {
            Text("Пригласить друга", color = WqTheme.colors.accentText)
        }
        var inviteInput by remember { mutableStateOf("") }
        OutlinedTextField(
            value = inviteInput,
            onValueChange = { inviteInput = it },
            label = { Text("Код приглашения друга") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { viewModel.onAcceptInvite(inviteInput) }) {
            Text("Принять приглашение", color = WqTheme.colors.accentText)
        }
    }

    ui.notice?.let {
        Text(it, style = WqTheme.typography.bodySmall, color = WqTheme.colors.inkSecondary)
        TextButton(onClick = { viewModel.onNoticeDismissed() }) { Text("Понятно") }
    }
}

/** Метрика строки: «км · %» с десятичной запятой (ru-локаль), спека §7. */
private fun formatMetric(distanceMeters: Double, fraction: Double): String =
    String.format(
        Locale("ru"),
        "%.1f км · %.1f%%",
        distanceMeters / 1000.0,
        fraction * 100.0,
    )
