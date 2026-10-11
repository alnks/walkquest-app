package app.walkquest.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.walkquest.domain.GoalType
import app.walkquest.domain.WalkGoal
import app.walkquest.ui.theme.WalkQuestPrimaryButton

/**
 * Вступительное обучение (история 3.3, FR-12) + постановка цели (FR-11).
 * ≤4 экранов, пропускаемое; «Пропустить» НЕ освобождает от цели — после пропуска
 * показывается только экран выбора цели (она обязательна, FR-11). Тексты —
 * спокойный голос продукта (EXPERIENCE, прототип ob1–ob4); финальная полировка
 * — UX-проход (deferred-work).
 *
 * [onComplete] — цель выбрана, онбординг завершён (viewmodel персистит и скрывает).
 */

private data class OnboardingPage(val title: String, val body: String)

private val PAGES = listOf(
    OnboardingPage(
        // Прототип ob1 (ux-walkquest/prototype, дизайн-сессия утвердила эту пару).
        title = "Город под туманом",
        body = "Карта скрыта завесой. Она расступается там, где вы прошли пешком или " +
            "проехали на велосипеде или самокате. На другом транспорте прогресс " +
            "не засчитывается.",
    ),
    OnboardingPage(
        title = "Туман расступается от прогулок",
        body = "Выйдите и пройдитесь: туман расступается только от реального " +
            "перемещения. Чем дальше гуляете — тем больше города видите.",
    ),
    OnboardingPage(
        title = "Честный подсчёт",
        body = "Пешком можно идти до 12 км/ч, на велосипеде и самокате — быстрее. " +
            "Если едете на самокате в пешем режиме, прогресс не засчитается — " +
            "туман умеет отличать прогулку от транспорта.",
    ),
)

private val GOAL_PAGE_INDEX: Int = PAGES.size

@Composable
fun OnboardingOverlay(
    onComplete: (WalkGoal) -> Unit,
) {
    // Старт с экрана цели после пропуска: индекс храним как состояние страницы.
    var page by rememberSaveable { mutableIntStateOf(0) }
    var goalType by rememberSaveable { mutableIntStateOf(GoalType.WALKS_PER_WEEK.ordinal) }
    var goalValue by rememberSaveable { mutableIntStateOf(3) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Верхний ряд: пропуск (не на экране цели).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (page < GOAL_PAGE_INDEX) {
                    TextButton(onClick = { page = GOAL_PAGE_INDEX }) {
                        Text(text = "Пропустить")
                    }
                }
            }
            // Контент страницы.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (page < GOAL_PAGE_INDEX) {
                    val current = PAGES[page]
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = current.body,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    GoalPicker(
                        selectedType = GoalType.entries[goalType],
                        value = goalValue,
                        onTypeChange = { type ->
                            goalType = type.ordinal
                            goalValue = defaultValue(type)
                        },
                        onValueChange = { goalValue = it },
                    )
                }
            }
            // Нижний ряд: листание/завершение.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (page < GOAL_PAGE_INDEX) {
                    WalkQuestPrimaryButton(onClick = { page += 1 }) {
                        Text(text = if (page == GOAL_PAGE_INDEX - 1) "Дальше: цель" else "Дальше")
                    }
                } else {
                    WalkQuestPrimaryButton(
                        onClick = {
                            val type = GoalType.entries[goalType]
                            if (WalkGoal.isValid(type, goalValue)) {
                                onComplete(WalkGoal(type, goalValue))
                            }
                        },
                    ) {
                        Text(text = "Начнём")
                    }
                }
            }
        }
    }
}

/** Шаг значения для типа цели (прогулки 1, минуты 10, км 1 = 0,5 км). */
private fun step(type: GoalType): Int = when (type) {
    GoalType.WALKS_PER_WEEK -> 1
    GoalType.MINUTES_PER_DAY -> 10
    GoalType.KM_PER_DAY -> 1
}

private fun defaultValue(type: GoalType): Int = when (type) {
    GoalType.WALKS_PER_WEEK -> 3
    GoalType.MINUTES_PER_DAY -> 30
    GoalType.KM_PER_DAY -> 4 // 2 км
}

@Composable
private fun GoalPicker(
    selectedType: GoalType,
    value: Int,
    onTypeChange: (GoalType) -> Unit,
    onValueChange: (Int) -> Unit,
) {
    Text(
        text = "Ваша цель",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GoalType.entries.forEach { type ->
            val label = when (type) {
                GoalType.WALKS_PER_WEEK -> "Прогулки в неделю"
                GoalType.MINUTES_PER_DAY -> "Минут в день"
                GoalType.KM_PER_DAY -> "Км в день"
            }
            if (selectedType == type) {
                WalkQuestPrimaryButton(onClick = { onTypeChange(type) }) { Text(text = label) }
            } else {
                TextButton(onClick = { onTypeChange(type) }) { Text(text = label) }
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TextButton(
            onClick = {
                val min = WalkGoal.range(selectedType).first
                if (value - step(selectedType) >= min) onValueChange(value - step(selectedType))
            },
        ) { Text(text = "−", style = MaterialTheme.typography.headlineMedium) }
        Text(
            text = WalkGoal(selectedType, value).describe(),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(
            onClick = {
                val max = WalkGoal.range(selectedType).last
                if (value + step(selectedType) <= max) onValueChange(value + step(selectedType))
            },
        ) { Text(text = "+", style = MaterialTheme.typography.headlineMedium) }
    }
}
