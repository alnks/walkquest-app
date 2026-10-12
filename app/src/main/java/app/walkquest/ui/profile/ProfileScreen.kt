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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.theme.WqTheme
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
 * Спецификация: screens-spec.md §8.
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
            .background(WqTheme.colors.surfaceBase)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Профиль",
            color = WqTheme.colors.inkHeadline,
            style = WqTheme.typography.headingScreen,
            modifier = Modifier.padding(
                start = WqTheme.spacing.lg,
                end = WqTheme.spacing.lg,
                top = WqTheme.spacing.lg,
                bottom = 10.dp,
            ),
        )

        // Шапка: ник и участие (по макету). Без ника — предложение открыть Рейтинг.
        WqCard(
            tonalElevation = 1.dp,
            modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = 10.dp)) {
                Text(
                    text = uiState.nickname ?: "Ник не задан",
                    color = WqTheme.colors.inkHeadline,
                    style = WqTheme.typography.headingCard,
                )
                Text(
                    text = when {
                        uiState.nickname == null -> "задаётся при первом открытии Рейтинга"
                        uiState.leaderboardVisible -> "в рейтинге · участие вкл"
                        else -> "в рейтинге · участие выкл"
                    },
                    color = WqTheme.colors.inkDim,
                    style = WqTheme.typography.meta,
                )
                uiState.goalText?.let {
                    Text(
                        text = "Моя цель: $it",
                        color = WqTheme.colors.inkSecondary,
                        style = WqTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // Мои регионы (1.10; EXPERIENCE: Регионы живут здесь, а не во вкладках).
        WqCard(
            onClick = onOpenRegions,
            tonalElevation = 1.dp,
            modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.md),
            ) {
                Text(
                    text = "Мои регионы",
                    color = WqTheme.colors.inkSecondary,
                    style = WqTheme.typography.body,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "→", color = WqTheme.colors.accentText)
            }
        }

        // Предупреждение о потере — постоянная карточка (Done when 2).
        WqCard(
            tonalElevation = 1.dp,
            modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
        ) {
            Text(
                text = "Данные живут только на этом устройстве: удаление приложения " +
                    "без экспорта безвозвратно удалит весь прогресс.",
                color = WqTheme.colors.inkSecondary,
                style = WqTheme.typography.body,
                modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.md),
            )
        }

        // Перенос прогресса.
        WqCard(
            tonalElevation = 1.dp,
            modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.sm)) {
                Text(
                    text = "Перенос прогресса",
                    color = WqTheme.colors.inkHeadline,
                    style = WqTheme.typography.headingCard,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Экспорт / импорт копии",
                        color = WqTheme.colors.inkDim,
                        style = WqTheme.typography.meta,
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
                            color = WqTheme.colors.accentText,
                        )
                    }
                    TextButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = uiState.importState == ImportState.Idle,
                    ) {
                        Text(text = "Импорт", color = WqTheme.colors.accentText)
                    }
                }
            }
        }

        // Недостающие регионы копии: пакеты не входят в архив — докачка (Decision 6.1).
        if (uiState.missingRegions.isNotEmpty()) {
            WqCard(
                tonalElevation = 1.dp,
                modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.sm)) {
                    Text(
                        text = "Недостающие регионы копии",
                        color = WqTheme.colors.inkHeadline,
                        style = WqTheme.typography.headingCard,
                    )
                    uiState.missingRegions.forEach { regionId ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = regionId,
                                color = WqTheme.colors.inkDim,
                                style = WqTheme.typography.meta,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { viewModel.downloadMissing(regionId) },
                                enabled = uiState.downloadingRegionId == null,
                            ) {
                                Text(
                                    text = if (uiState.downloadingRegionId == regionId) "Скачивание…" else "Скачать",
                                    color = WqTheme.colors.accentText,
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
                color = WqTheme.colors.accentText,
                style = WqTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
            )
        }
        uiState.lastError?.let { error ->
            Text(
                text = error,
                color = WqTheme.colors.error,
                style = WqTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
            )
        }

        // О приложении.
        Text(
            text = "О приложении",
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.meta,
            modifier = Modifier.padding(start = WqTheme.spacing.lg, end = WqTheme.spacing.lg, top = WqTheme.spacing.md, bottom = 4.dp),
        )
        WqCard(
            tonalElevation = 1.dp,
            modifier = Modifier.padding(horizontal = WqTheme.spacing.lg, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Версия",
                        color = WqTheme.colors.inkSecondary,
                        style = WqTheme.typography.body,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = uiState.appVersion,
                        color = WqTheme.colors.inkDim,
                        style = WqTheme.typography.meta,
                    )
                }
                WqCard(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uiState.privacyUrl)))
                }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            text = "Политика конфиденциальности",
                            color = WqTheme.colors.inkSecondary,
                            style = WqTheme.typography.body,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = "→", color = WqTheme.colors.accentText)
                    }
                }
            }
        }
    }
}

/** Имя файла копии: walkquest-export-2026-10-11.zip. */
private fun todayStamp(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
