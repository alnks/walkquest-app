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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestPrimaryButton
import app.walkquest.viewmodel.RatingViewModel

/**
 * Экран рейтинга (эпик 5, FR-19): ник при первом открытии («Придумайте ник —
 * так вас увидят другие»), невидимый режим, вкладки «Город»/«Друзья», друг по
 * коду приглашения. Клиент отправляет только агрегаты (AD-3) — UI ничего не
 * решает сам.
 */
@Composable
fun RatingScreen(viewModel: RatingViewModel) {
    val ui by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Рейтинг", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        val nickname = ui.nickname
        if (nickname == null) {
            var input by remember { mutableStateOf("") }
            Text("Придумайте ник — так вас увидят другие.")
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Ник") },
                isError = ui.nicknameError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            ui.nicknameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            WalkQuestPrimaryButton(onClick = { viewModel.onNicknameSubmitted(input) }) {
                Text("Вступить в рейтинг")
            }
            TextButton(onClick = { viewModel.onInvisibleMode() }) {
                Text("Остаться невидимым")
            }
        } else {
            Text("Вы: $nickname" + if (!ui.visible) " (невидимый режим)" else "")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.onTabSelected(true) }) {
                    Text(if (ui.cityTab) "[ Город ]" else "Город")
                }
                TextButton(onClick = { viewModel.onTabSelected(false) }) {
                    Text(if (!ui.cityTab) "[ Друзья ]" else "Друзья")
                }
                TextButton(onClick = { viewModel.refresh() }) { Text("Обновить") }
            }
            ui.top.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${index + 1}. ${entry.nickname}")
                    Text("%.1f км · %.1f%%".format(entry.distanceMeters / 1000.0, entry.fraction * 100.0))
                }
            }
            if (ui.top.isEmpty() && !ui.loading) Text("Пока пусто — станьте первым.")
            ui.inviteCode?.let { Text("Код приглашения: $it — отправьте другу.") }
            TextButton(onClick = { viewModel.onCreateInvite() }) { Text("Пригласить друга") }
            var inviteInput by remember { mutableStateOf("") }
            OutlinedTextField(
                value = inviteInput,
                onValueChange = { inviteInput = it },
                label = { Text("Код приглашения друга") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { viewModel.onAcceptInvite(inviteInput) }) { Text("Принять приглашение") }
            ui.notice?.let {
                Text(it)
                TextButton(onClick = { viewModel.onNoticeDismissed() }) { Text("Понятно") }
            }
        }
    }
}
