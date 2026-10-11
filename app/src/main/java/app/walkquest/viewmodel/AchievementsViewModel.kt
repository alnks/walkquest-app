package app.walkquest.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.walkquest.domain.achievements.AchievementCatalog
import app.walkquest.domain.achievements.AchievementDef
import app.walkquest.domain.achievements.AchievementEngine
import app.walkquest.domain.achievements.AchievementStateFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Одна строка экрана наград (4.4). */
data class AchievementRowUi(
    val id: String,
    val icon: String,
    val name: String,
    val conditionText: String,
    val category: String,
    val rare: Boolean,
    /** Получено (epoch millis) или null. */
    val earnedAt: Long?,
    /** Прогресс-бар (текущее, цель) для неполученных с hasProgress. */
    val progress: Pair<Double, Double>?,
)

/** Состояние экрана наград: 38 достижений, перепроверка при входе (FR-15). */
class AchievementsViewModel(
    private val engine: AchievementEngine,
    private val stateFactory: AchievementStateFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow<List<AchievementRowUi>>(emptyList())
    val uiState: StateFlow<List<AchievementRowUi>> = _uiState.asStateFlow()

    init {
        reload()
    }

    /** Полная перепроверка по истории (задним числом, FR-15) + обновление списка. */
    fun reload() {
        viewModelScope.launch {
            val earnedAtById = withContext(Dispatchers.IO) {
                engine.checkAndRecord(stateFactory.build()).let {
                    engine.earned().associate { it.first.id to it.second }
                }
            }
            _uiState.value = AchievementCatalog.ALL.map { def ->
                AchievementRowUi(
                    id = def.id,
                    icon = def.icon,
                    name = def.name,
                    conditionText = def.conditionText,
                    category = def.category.title,
                    rare = def.rare,
                    earnedAt = earnedAtById[def.id],
                    progress = if (def.hasProgress && earnedAtById[def.id] == null) {
                        // Прогресс пересобираем из того же состояния — reload уже
                        // перепроверил; для бара берём свежую сборку лениво.
                        def.progress?.invoke(stateFactory.build())
                    } else {
                        null
                    },
                )
            }
        }
    }
}
