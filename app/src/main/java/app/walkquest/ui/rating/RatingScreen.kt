package app.walkquest.ui.rating

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.walkquest.ui.components.WqPrimaryButton
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.RatingViewModel

/**
 * Экран рейтинга (эпик 5, FR-19): ник при первом открытии («Придумайте ник —
 * так вас увидят другие»), невидимый режим, вкладки «Город»/«Друзья», друг по
 * коду приглашения. Клиент отправляет только агрегаты (AD-3) — UI ничего не
 * решает сам. Спецификация: screens-spec.md §7.
 */
@Composable
fun RatingScreen(viewModel: RatingViewModel) {
    val ui by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
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

        val nickname = ui.nickname
        if (nickname == null) {
            var input by remember { mutableStateOf("") }
            Text(
                text = "Придумайте ник — так вас увидят другие.",
                style = WqTheme.typography.body,
                color = WqTheme.colors.inkSecondary,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Ник") },
                isError = ui.nicknameError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            ui.nicknameError?.let { Text(it, color = WqTheme.colors.error) }
            WqPrimaryButton(onClick = { viewModel.onNicknameSubmitted(input) }) {
                Text("Вступить в рейтинг")
            }
            TextButton(onClick = { viewModel.onInvisibleMode() }) {
                Text("Остаться невидимым", color = WqTheme.colors.inkSecondary)
            }
        } else {
            Text(
                text = "Вы: $nickname" + if (!ui.visible) " (невидимый режим)" else "",
                style = WqTheme.typography.body,
                color = WqTheme.colors.inkPrimary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewModel.onTabSelected(true) }) {
                    Text(
                        if (ui.cityTab) "[ Город ]" else "Город",
                        color = if (ui.cityTab) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
                    )
                }
                TextButton(onClick = { viewModel.onTabSelected(false) }) {
                    Text(
                        if (!ui.cityTab) "[ Друзья ]" else "Друзья",
                        color = if (!ui.cityTab) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
                    )
                }
                TextButton(onClick = { viewModel.refresh() }) {
                    Text("Обновить", color = WqTheme.colors.inkDim)
                }
            }
            ui.top.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${index + 1}. ${entry.nickname}",
                        style = WqTheme.typography.body,
                        color = WqTheme.colors.inkPrimary,
                    )
                    Text(
                        "%.1f км · %.1f%%".format(entry.distanceMeters / 1000.0, entry.fraction * 100.0),
                        style = WqTheme.typography.body,
                        color = WqTheme.colors.inkSecondary,
                    )
                }
                // Подсветка текущего пользователя card-active (DESIGN leaderboard-row):
                // ждёт флага isCurrentUser в RatingEntryUi (эпик 5) — deferred-work.
            }
            if (ui.top.isEmpty() && !ui.loading) {
                Text(
                    text = "Пока пусто — станьте первым.",
                    style = WqTheme.typography.bodySmall,
                    color = WqTheme.colors.inkDim,
                )
            }
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
            ui.notice?.let {
                Text(it, style = WqTheme.typography.bodySmall, color = WqTheme.colors.inkSecondary)
                TextButton(onClick = { viewModel.onNoticeDismissed() }) { Text("Понятно") }
            }
        }
    }
}
