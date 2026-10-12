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
import androidx.compose.ui.text.style.TextAlign
import app.walkquest.domain.GoalType
import app.walkquest.domain.WalkGoal
import app.walkquest.ui.components.WqPrimaryButton
import app.walkquest.ui.theme.WqTheme

/**
 * Вступительное обучение (история 3.3, FR-12) + постановка цели (FR-11).
 * ≤4 экранов, пропускаемое; «Пропустить» НЕ освобождает от цели — после пропуска
 * показывается только экран выбора цели (она обязательна, FR-11). Тексты —
 * спокойный голос продукта (EXPERIENCE, прототип ob1–ob4); финальная полировка
 * — UX-проход (deferred-work). Спецификация: screens-spec.md §4.
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

    Surface(modifier = Modifier.fillMaxSize(), color = WqTheme.colors.surfaceRaised) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(WqTheme.spacing.xl),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Верхний ряд: пропуск (не на экране цели).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (page < GOAL_PAGE_INDEX) {
                    TextButton(onClick = { page = GOAL_PAGE_INDEX }) {
                        Text(text = "Пропустить", color = WqTheme.colors.inkSecondary)
                    }
                }
            }
            // Контент страницы.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (page < GOAL_PAGE_INDEX) {
                    val current = PAGES[page]
                    Text(
                        text = current.title,
                        style = WqTheme.typography.headingSection,
                        color = WqTheme.colors.inkHeadline,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(WqTheme.spacing.lg))
                    Text(
                        text = current.body,
                        style = WqTheme.typography.body,
                        color = WqTheme.colors.inkSecondary,
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
                    WqPrimaryButton(onClick = { page += 1 }) {
                        Text(text = if (page == GOAL_PAGE_INDEX - 1) "Дальше: цель" else "Дальше")
                    }
                } else {
                    WqPrimaryButton(
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
        style = WqTheme.typography.headingSection,
        color = WqTheme.colors.inkHeadline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.lg))
    Row(horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm)) {
        GoalType.entries.forEach { type ->
            val label = when (type) {
                GoalType.WALKS_PER_WEEK -> "Прогулки в неделю"
                GoalType.MINUTES_PER_DAY -> "Минут в день"
                GoalType.KM_PER_DAY -> "Км в день"
            }
            val selected = selectedType == type
            TextButton(onClick = { onTypeChange(type) }) {
                Text(
                    text = label,
                    color = if (selected) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
                    style = if (selected) WqTheme.typography.metaLarge else WqTheme.typography.body,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(WqTheme.spacing.xl))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.xl),
    ) {
        TextButton(
            onClick = {
                val min = WalkGoal.range(selectedType).first
                if (value - step(selectedType) >= min) onValueChange(value - step(selectedType))
            },
        ) {
            Text(
                text = "−",
                style = WqTheme.typography.headingSection,
                color = WqTheme.colors.inkSecondary,
            )
        }
        Text(
            text = WalkGoal(selectedType, value).describe(),
            style = WqTheme.typography.headingCard,
            color = WqTheme.colors.inkHeadline,
        )
        TextButton(
            onClick = {
                val max = WalkGoal.range(selectedType).last
                if (value + step(selectedType) <= max) onValueChange(value + step(selectedType))
            },
        ) {
            Text(
                text = "+",
                style = WqTheme.typography.headingSection,
                color = WqTheme.colors.inkSecondary,
            )
        }
    }
}
