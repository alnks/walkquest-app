package app.walkquest.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.walkquest.domain.GoalType
import app.walkquest.domain.WalkGoal
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.components.WqCardActive
import app.walkquest.ui.components.WqPrimaryButton
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.RegionOptionUi
import app.walkquest.viewmodel.RegionSuggestionUi

/**
 * Онбординг первого запуска (эпик design-alignment, история 7.1) — 4 шага по утверждённому
 * макету mockups/onboarding-duo.html: приветствие с иллюстрацией-туманом → домашний регион
 * (мини-карта + установка существующим механизмом предложения региона) → цель со степпером →
 * доступ к геолокации. Копии — из макета/EXPERIENCE (менять нельзя). Поведение (EXPERIENCE,
 * screens-spec §4): обучение не завершает онбординг — «Пропустить» на шагах до цели прыгает
 * к шагу цели; цель обязательна (FR-11), шаг геолокации завершает выбор цели и оверлей
 * (отказ от разрешения не блокирует: карта честно спросит снова при старте прогулки).
 *
 * Цвета/типографика — только токены WqTheme; иллюстрации собраны из токенов карты
 * (mapBase/mapBase2/fog) и векторных иконок WalkQuestIcons (эмодзи макета — плейсхолдеры,
 * DESIGN Shapes). Тач-зоны ≥48dp (Accessibility Floor EXPERIENCE).
 *
 * [regionSuggestion] — живое предложение домашнего региона (установка/ошибка из viewmodel);
 * null — предложения ещё нет (сеть) или регион уже установлен. [regionOptions] — список
 * регионов из манифеста для «Выбрать другой». [homeRegionId] — уже установленный домашний
 * (тогда шаг региона только подтверждает его, без загрузки).
 */
@Composable
fun OnboardingOverlay(
    regionSuggestion: RegionSuggestionUi?,
    regionOptions: List<RegionOptionUi>,
    homeRegionId: String?,
    onInstallRegion: () -> Unit,
    onPickRegion: (String) -> Unit,
    onComplete: (WalkGoal) -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var goalType by rememberSaveable { mutableIntStateOf(GoalType.WALKS_PER_WEEK.ordinal) }
    var goalValue by rememberSaveable { mutableIntStateOf(3) }
    var regionPickerOpen by rememberSaveable { mutableStateOf(false) }

    // Установка региона: ждём исчезновения предложения (успех) — и только тогда листаем
    // к цели. Ошибка остаётся на карточке предложения (поле error), шаг не покидаем.
    var regionAdvancePending by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(regionSuggestion) {
        if (regionAdvancePending && regionSuggestion == null) {
            regionAdvancePending = false
            page = PAGE_GOAL
        }
    }

    // Шаг геолокации: запрашиваем FINE/COARSE и в любом случае завершаем онбординг
    // (EXPERIENCE Flow 2, отказ: карта открывается, запись спросит своё при старте).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onComplete(WalkGoal(GoalType.entries[goalType], goalValue))
    }

    Surface(modifier = Modifier.fillMaxSize(), color = WqTheme.colors.surfaceBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(
                    start = WqTheme.spacing.xl,
                    end = WqTheme.spacing.xl,
                    top = WqTheme.spacing.lg,
                    bottom = WqTheme.spacing.lg,
                ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Верхний ряд: «Пропустить» — только до шага цели (прыгает к цели).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (page < PAGE_GOAL) {
                    TextButton(onClick = { page = PAGE_GOAL }) {
                        Text(text = "Пропустить", color = WqTheme.colors.inkSecondary)
                    }
                }
            }
            // Контент шага (скроллится при крупном системном шрифте; прижат к верху,
            // как в макете: блок начинается в верхней трети сразу под «Пропустить»).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                when (page) {
                    PAGE_WELCOME -> WelcomeStep()
                    PAGE_REGION -> RegionStep(
                        suggestion = regionSuggestion,
                        options = regionOptions,
                        homeRegionId = homeRegionId,
                        pickerOpen = regionPickerOpen,
                        onPickerToggle = { regionPickerOpen = !regionPickerOpen },
                        onPickRegion = onPickRegion,
                    )
                    PAGE_GOAL -> GoalStep(
                        selectedType = GoalType.entries[goalType],
                        value = goalValue,
                        onTypeChange = { type ->
                            goalType = type.ordinal
                            goalValue = defaultValue(type)
                        },
                        onValueChange = { goalValue = it },
                    )
                    PAGE_PERMISSION -> PermissionStep()
                }
            }
            // Нижний ряд: точки-индикаторы шагов + CTA (+ текст-ссылка шага региона).
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StepDots(active = page)
                Spacer(modifier = Modifier.height(WqTheme.spacing.sm))
                when (page) {
                    PAGE_WELCOME -> WqPrimaryButton(onClick = { page = PAGE_REGION }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = "Дальше")
                    }
                    PAGE_REGION -> {
                        val suggestion = regionSuggestion
                        val ready = suggestion != null && !suggestion.installing
                        WqPrimaryButton(
                            onClick = {
                                if (suggestion != null) {
                                    regionAdvancePending = true
                                    onInstallRegion()
                                } else {
                                    // Регион уже установлен (home) — только подтверждаем.
                                    page = PAGE_GOAL
                                }
                            },
                            enabled = ready || (suggestion == null && homeRegionId != null),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = when {
                                    suggestion == null -> "Это мой регион"
                                    suggestion.installing -> "Загружается…"
                                    else -> "Это мой регион"
                                },
                            )
                        }
                    }
                    PAGE_GOAL -> WqPrimaryButton(
                        onClick = {
                            val type = GoalType.entries[goalType]
                            if (WalkGoal.isValid(type, goalValue)) page = PAGE_PERMISSION
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Выбрать")
                    }
                    PAGE_PERMISSION -> WqPrimaryButton(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Разрешить и открыть карту")
                    }
                }
                // «Выбрать другой» — по макету всегда под CTA шага региона; при одном
                // регионе в манифесте пикер открывается с этим единственным вариантом.
                if (page == PAGE_REGION) {
                    TextButton(
                        onClick = { regionPickerOpen = !regionPickerOpen },
                        enabled = regionSuggestion?.installing != true,
                    ) {
                        Text(text = "Выбрать другой", color = WqTheme.colors.inkSecondary)
                    }
                }
            }
        }
    }
}

private const val PAGE_WELCOME = 0
private const val PAGE_REGION = 1
private const val PAGE_GOAL = 2
private const val PAGE_PERMISSION = 3
private const val PAGE_COUNT = 4

/** Шаг 1 — приветствие: иконка-туман, заголовок, иллюстрация завесы с просветом, правило. */
@Composable
private fun WelcomeStep() {
    Icon(
        imageVector = WalkQuestIcons.Fog,
        contentDescription = null,
        tint = WqTheme.colors.fog,
        modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.md))
    Text(
        text = "Город под туманом",
        style = WqTheme.typography.headingOnboarding,
        color = WqTheme.colors.inkHeadline,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.lg))
    FogIllustration()
    Spacer(modifier = Modifier.height(WqTheme.spacing.lg))
    Text(
        text = "Карта скрыта завесой. Она расступается там, где вы прошли пешком или " +
            "проехали на велосипеде или самокате. На другом транспорте прогресс " +
            "не засчитывается.",
        style = WqTheme.typography.body,
        color = WqTheme.colors.inkSecondary,
        textAlign = TextAlign.Center,
    )
}

/**
 * Иллюстрация шага 1: панель с лавандовой завесой (токен fog, прозрачность fog-layer
 * DESIGN: 30% тёмная / 40% светлая) и «прожаренным» просветом.
 * Тёмная: панель surface-raised, просвет до map-base (DESIGN fog-layer) — как в макете
 * (карточка ≈ #4E4363, круг #241C36).
 * Светлая: панель surface-sunken под завесой и просвет той же ступени — замер макета
 * onboarding-duo.html: карточка #D4C8E7, круг #EDE7F7 (круг светлее и читается; поверх
 * белой панели surface-raised просвет неотличим — сверка итерации 3).
 */
@Composable
private fun FogIllustration() {
    val dark = isSystemInDarkTheme()
    val panel = if (dark) WqTheme.colors.surfaceRaised else WqTheme.colors.surfaceSunken
    val clear = if (dark) WqTheme.colors.mapBase else WqTheme.colors.surfaceSunken
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(WqTheme.shapes.large)
            .background(panel),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WqTheme.colors.fog.copy(alpha = if (dark) 0.30f else 0.40f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 28.dp, y = 4.dp)
                .size(60.dp)
                .clip(CircleShape)
                .background(clear),
        )
    }
}

/** Шаг 2 — домашний регион: мини-карта с пином, карточка региона, список «Выбрать другой». */
@Composable
private fun RegionStep(
    suggestion: RegionSuggestionUi?,
    options: List<RegionOptionUi>,
    homeRegionId: String?,
    pickerOpen: Boolean,
    onPickerToggle: () -> Unit,
    onPickRegion: (String) -> Unit,
) {
    Text(
        text = "Где вы живёте?",
        style = WqTheme.typography.headingOnboarding,
        color = WqTheme.colors.inkHeadline,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.sm))
    Text(
        text = "Это ваш домашний регион. При смене региона подгрузится нужная карта, " +
            "и прогулки можно будет продолжить там.",
        style = WqTheme.typography.body,
        color = WqTheme.colors.inkSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.md))
    RegionMiniMap()
    Spacer(modifier = Modifier.height(WqTheme.spacing.md))
    val homeName = homeRegionId?.let { home -> options.firstOrNull { it.regionId == home }?.name }
    when {
        suggestion != null -> WqCardActive {
            Column(modifier = Modifier.padding(WqTheme.spacing.md)) {
                Text(
                    text = suggestion.name,
                    style = WqTheme.typography.headingCard,
                    color = WqTheme.colors.inkHeadline,
                )
                Text(
                    text = "определено по геолокации",
                    style = WqTheme.typography.meta,
                    color = WqTheme.colors.inkDim,
                )
            }
        }
        homeName != null -> WqCardActive {
            Column(modifier = Modifier.padding(WqTheme.spacing.md)) {
                Text(
                    text = homeName,
                    style = WqTheme.typography.headingCard,
                    color = WqTheme.colors.inkHeadline,
                )
                Text(
                    text = "домашний регион",
                    style = WqTheme.typography.meta,
                    color = WqTheme.colors.inkDim,
                )
            }
        }
        else -> WqCard {
            Column(modifier = Modifier.padding(WqTheme.spacing.md)) {
                Text(
                    text = "Определяем регион по геолокации…",
                    style = WqTheme.typography.bodySmall,
                    color = WqTheme.colors.inkDim,
                )
            }
        }
    }
    suggestion?.error?.let { error ->
        Spacer(modifier = Modifier.height(WqTheme.spacing.sm))
        Text(
            text = error,
            style = WqTheme.typography.bodySmall,
            color = WqTheme.colors.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // Пикер: варианты манифеста; если регион один — пикер из него (макет показывает
    // ссылку всегда; открытие с единственным вариантом — следование макету).
    val pickerOptions = if (options.isNotEmpty()) {
        options
    } else {
        suggestion?.let { listOf(RegionOptionUi(it.regionId, it.name)) }.orEmpty()
    }
    if (pickerOpen && pickerOptions.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm)) {
            pickerOptions.forEach { option ->
                val selected = option.regionId == (suggestion?.regionId ?: homeRegionId)
                WqCard(onClick = { onPickerToggle(); onPickRegion(option.regionId) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(horizontal = WqTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.name,
                                style = WqTheme.typography.bodySmall,
                                color = if (selected) WqTheme.colors.inkHeadline else WqTheme.colors.inkSecondary,
                            )
                        }
                    }
            }
        }
    }
}

/** Мини-карта шага региона: градиент базы карты (mapBase → mapBase2) под полупрозрачным
 * туманом (fog @ 50%, как в макете), пин — крупный розовый маркер из макета (токен pin). */
@Composable
private fun RegionMiniMap() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .clip(WqTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    colors = listOf(WqTheme.colors.mapBase, WqTheme.colors.mapBase2),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WqTheme.colors.fog.copy(alpha = 0.5f)),
        )
        Icon(
            imageVector = WalkQuestIcons.Pin,
            contentDescription = null,
            tint = WqTheme.colors.pin,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-8).dp, y = (-6).dp)
                .size(40.dp),
        )
    }
}

/** Шаг 3 — цель: три карточки типа; у выбранной — степпер значения. */
@Composable
private fun GoalStep(
    selectedType: GoalType,
    value: Int,
    onTypeChange: (GoalType) -> Unit,
    onValueChange: (Int) -> Unit,
) {
    Text(
        text = "Ваша цель",
        style = WqTheme.typography.headingOnboarding,
        color = WqTheme.colors.inkHeadline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.sm))
    Text(
        text = "Выберите, от чего отталкиваться — число настраивается и всегда меняется " +
            "в настройках.",
        style = WqTheme.typography.body,
        color = WqTheme.colors.inkSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.md))
    Column(verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm)) {
        GoalType.entries.forEach { type ->
            val selected = selectedType == type
            val content: @Composable () -> Unit = {
                Column(modifier = Modifier.padding(WqTheme.spacing.md)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
                    ) {
                        Icon(
                            imageVector = when (type) {
                                GoalType.WALKS_PER_WEEK -> WalkQuestIcons.Walk
                                GoalType.MINUTES_PER_DAY -> WalkQuestIcons.Clock
                                GoalType.KM_PER_DAY -> WalkQuestIcons.Ruler
                            },
                            contentDescription = null,
                            tint = if (selected) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = when (type) {
                                GoalType.WALKS_PER_WEEK -> "Прогулок в неделю"
                                GoalType.MINUTES_PER_DAY -> "Минут в день"
                                GoalType.KM_PER_DAY -> "Километров в день"
                            },
                            style = WqTheme.typography.headingCard,
                            color = if (selected) WqTheme.colors.inkHeadline else WqTheme.colors.inkSecondary,
                        )
                    }
                    if (selected) {
                        Spacer(modifier = Modifier.height(WqTheme.spacing.sm))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            StepperButton(glyph = "−", onClick = {
                                val min = WalkGoal.range(selectedType).first
                                if (value - step(selectedType) >= min) {
                                    onValueChange(value - step(selectedType))
                                }
                            })
                            Text(
                                text = value.toString(),
                                style = WqTheme.typography.displayCompact,
                                color = WqTheme.colors.inkPrimary,
                            )
                            StepperButton(glyph = "+", onClick = {
                                val max = WalkGoal.range(selectedType).last
                                if (value + step(selectedType) <= max) {
                                    onValueChange(value + step(selectedType))
                                }
                            })
                        }
                    }
                }
            }
            if (selected) {
                WqCardActive(content = { content() })
            } else {
                WqCard(onClick = { onTypeChange(type) }, content = { content() })
            }
        }
    }
}

/** Кнопка степпера (− / +): визуально компактная, тач-зона ≥48dp. */
@Composable
private fun StepperButton(glyph: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = WqTheme.colors.surfaceBase,
        border = androidx.compose.foundation.BorderStroke(1.dp, WqTheme.colors.borderStrong),
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = glyph,
                style = WqTheme.typography.headingCard,
                color = WqTheme.colors.accentText,
            )
        }
    }
}

/** Шаг 4 — доступ к геолокации: пин, объяснение и гарантия приватности. */
@Composable
private fun PermissionStep() {
    Icon(
        imageVector = WalkQuestIcons.Pin,
        contentDescription = null,
        tint = WqTheme.colors.pin,
        modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.md))
    Text(
        text = "Доступ к геолокации",
        style = WqTheme.typography.headingOnboarding,
        color = WqTheme.colors.inkHeadline,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.lg))
    Text(
        text = "Чтобы туман расступался, пока телефон в кармане, нужен доступ " +
            "к геолокации «всегда».",
        style = WqTheme.typography.body,
        color = WqTheme.colors.inkSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.lg))
    Text(
        text = "Информация о ваших передвижениях не покидает телефон.",
        style = WqTheme.typography.body.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = WqTheme.colors.accentText,
            fontSynthesis = androidx.compose.ui.text.font.FontSynthesis.Weight,
        ),
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(WqTheme.spacing.sm))
    Text(
        text = "На сервер уходят только ник и статистика для рейтинга, если вы захотите " +
            "в нём участвовать.",
        style = WqTheme.typography.body,
        color = WqTheme.colors.inkSecondary,
        textAlign = TextAlign.Center,
    )
}

/** Точки-индикаторы шагов: неактивные — border-hairline, активная — акцент-пилюля. */
@Composable
private fun StepDots(active: Int) {
    Row(
        modifier = Modifier.padding(vertical = WqTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PAGE_COUNT) { index ->
            Box(
                modifier = Modifier
                    .size(width = if (index == active) 16.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(
                        if (index == active) WqTheme.colors.accent else WqTheme.colors.borderHairline,
                    ),
            )
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
