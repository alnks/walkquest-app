package app.walkquest.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.ui.theme.AccentTextColor
import app.walkquest.ui.theme.DimTextColor
import app.walkquest.ui.theme.HeadlineColor
import app.walkquest.ui.theme.InkSecondaryColor
import app.walkquest.viewmodel.ImportState
import app.walkquest.viewmodel.ProfileViewModel
import app.walkquest.viewmodel.formatCopyDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Вкладка «Профиль» (история 6.3, FR-20/FR-21; макет remaining-screens «Профиль»):
 * перенос прогресса (SAF: экспорт в выбранное место, импорт с подтверждением),
 * постоянное предупреждение о безвозвратной потере при удалении (Done when 2),
 * докачка недостающих регионов после импорта, «О приложении».
 *
 * SAF и ContentResolver живут здесь; viewmodel принимает потоки — логика тестируется на JVM.
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    /** «Мои регионы» (EXPERIENCE: Регионы — Профиль → «Мои регионы»). */
    onOpenRegions: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Возврат на вкладку → свежие ник/цель (могли измениться в других местах).
    LaunchedEffect(Unit) { viewModel.reload() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // use закрывает поток только после завершения suspend-записи.
            context.contentResolver.openOutputStream(uri)?.use { viewModel.export(it) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            context.contentResolver.openInputStream(uri)?.use { viewModel.readImportPreview(it) }
        }
    }

    val preview = uiState.importState as? ImportState.Preview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(text = "Импортировать копию?") },
            text = {
                Text(
                    text = "Текущие данные будут заменены копией от " +
                        formatCopyDate(preview.exportedAtEpochMillis) + "."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) { Text(text = "Заменить") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) { Text(text = "Отмена") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Профиль",
            color = HeadlineColor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
        )

        // Шапка: ник и участие (по макету). Без ника — предложение открыть Рейтинг.
        ProfileHeader(
            nickname = uiState.nickname,
            leaderboardVisible = uiState.leaderboardVisible,
            goalText = uiState.goalText,
        )

        // Мои регионы (1.10; EXPERIENCE: Регионы живут здесь, а не во вкладках).
        Surface(
            onClick = onOpenRegions,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Мои регионы",
                    color = InkSecondaryColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "→", color = AccentTextColor)
            }
        }

        // Предупреждение о потере — постоянная карточка (Done when 2).
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Данные живут только на этом устройстве: удаление приложения " +
                    "без экспорта безвозвратно удалит весь прогресс.",
                color = InkSecondaryColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        // Перенос прогресса.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "Перенос прогресса",
                    color = HeadlineColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Экспорт / импорт копии",
                        color = DimTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            exportLauncher.launch("walkquest-export-${todayStamp()}.zip")
                        },
                        enabled = !uiState.exportBusy,
                    ) {
                        Text(
                            text = if (uiState.exportBusy) "Сохранение…" else "Экспорт",
                            color = AccentTextColor,
                        )
                    }
                    TextButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = uiState.importState == ImportState.Idle,
                    ) {
                        Text(text = "Импорт", color = AccentTextColor)
                    }
                }
            }
        }

        // Недостающие регионы копии: пакеты не входят в архив — докачка (Decision 6.1).
        if (uiState.missingRegions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "Недостающие регионы копии",
                        color = HeadlineColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    uiState.missingRegions.forEach { regionId ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = regionId,
                                color = DimTextColor,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { viewModel.downloadMissing(regionId) },
                                enabled = uiState.downloadingRegionId == null,
                            ) {
                                Text(
                                    text = if (uiState.downloadingRegionId == regionId) "Скачивание…" else "Скачать",
                                    color = AccentTextColor,
                                )
                            }
                        }
                    }
                }
            }
        }

        uiState.message?.let { message ->
            Text(
                text = message,
                color = AccentTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        uiState.lastError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // О приложении.
        Text(
            text = "О приложении",
            color = DimTextColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Версия",
                        color = InkSecondaryColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = uiState.appVersion,
                        color = DimTextColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Surface(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uiState.privacyUrl)))
                }, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            text = "Политика конфиденциальности",
                            color = InkSecondaryColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = "→", color = AccentTextColor)
                    }
                }
            }
        }
    }
}

/** Шапка Профиля по макету: ник + статус участия, цель. */
@Composable
private fun ProfileHeader(
    nickname: String?,
    leaderboardVisible: Boolean,
    goalText: String?,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = nickname ?: "Ник не задан",
                color = HeadlineColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = when {
                    nickname == null -> "задаётся при первом открытии Рейтинга"
                    leaderboardVisible -> "в рейтинге · участие вкл"
                    else -> "в рейтинге · участие выкл"
                },
                color = DimTextColor,
                style = MaterialTheme.typography.labelSmall,
            )
            goalText?.let {
                Text(
                    text = "Моя цель: $it",
                    color = InkSecondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Имя файла копии: walkquest-export-2026-10-11.zip. */
private fun todayStamp(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
