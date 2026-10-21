package app.walkquest.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.walkquest.data.leaderboard.LeaderboardApi
import app.walkquest.data.leaderboard.LeaderboardOutbox
import app.walkquest.data.leaderboard.regionAggregates
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.domain.ComputeRegionProgress
import app.walkquest.domain.ProgressResult
import app.walkquest.domain.WalkHistory
import app.walkquest.domain.leaderboard.LeaderboardSnapshot
import app.walkquest.domain.leaderboard.NicknamePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние экрана рейтинга (эпик 5, FR-19). */
data class RatingUiState(
    /** Ник задан — показываем рейтинг; null — показываем запрос ника. */
    val nickname: String? = null,
    /**
     * Первый вход закрыт выбором «Остаться невидимым» (EXPERIENCE: участие
     * опционально, «можно закрыть и не участвовать»): ника нет, но форму не
     * показываем — видим пустой лидерборд. Персистентность — косвенная:
     * ник не задан + участие выкл (settings).
     */
    val declined: Boolean = false,
    /** Участие (невидимый режим отключает отправку). */
    val visible: Boolean = true,
    /** Запрос ника: ошибка валидации или сети. */
    val nicknameError: String? = null,
    /** Вкладка: true — «Город», false — «Друзья». */
    val cityTab: Boolean = true,
    val top: List<RatingEntryUi> = emptyList(),
    val loading: Boolean = false,
    val inviteCode: String? = null,
    val notice: String? = null,
)

/** Строка топа для экрана: агрегаты (AD-3) + флаг «текущий пользователь»
 *  (подсветка строки WqCardActive, DESIGN leaderboard-row). */
data class RatingEntryUi(
    val nickname: String,
    val distanceMeters: Double,
    val fraction: Double,
    val isCurrentUser: Boolean,
)

/**
 * Лидерборд (эпик 5): ник при первом открытии (FR-19), вкладки «Город»/
 * «Друзья», публикация агрегатов (AD-3: только ник + суммы), outbox.
 * D4 выдаётся при первой успешной публикации профиля (через onJoined).
 */
class RatingViewModel(
    private val settings: SettingsRepository,
    private val api: LeaderboardApi?,
    private val outbox: LeaderboardOutbox,
    private val walkHistory: WalkHistory,
    private val computeRegionProgress: ComputeRegionProgress,
    private val installedRegionIds: () -> List<String>,
    private val onJoined: () -> Unit = {},
    private val onFriendAccepted: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RatingUiState(
            nickname = settings.leaderboardNickname(),
            visible = settings.leaderboardVisible(),
            declined = settings.leaderboardNickname() == null && !settings.leaderboardVisible(),
        ),
    )
    val uiState: StateFlow<RatingUiState> = _uiState.asStateFlow()

    /** Ник подтверждён: валидация (domain) → профиль на сервер → состояние. */
    fun onNicknameSubmitted(nickname: String) {
        val invalid = NicknamePolicy.validate(nickname)
        if (invalid != null) {
            _uiState.update { it.copy(nicknameError = invalid) }
            return
        }
        val client = api
        if (client == null) {
            _uiState.update { it.copy(nicknameError = "Лидерборд не настроен (нет подключения)") }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { client.upsertProfile(nickname) }
            if (ok) {
                settings.setLeaderboardNickname(nickname)
                settings.setLeaderboardVisible(true)
                _uiState.update { it.copy(nickname = nickname, visible = true, declined = false, nicknameError = null) }
                onJoined() // D4
                refresh()
            } else {
                _uiState.update { it.copy(nicknameError = "Не удалось сохранить ник — проверьте сеть") }
            }
        }
    }

    /** Отказ от участия: невидимый режим (FR-19: участие опционально). */
    fun onInvisibleMode() {
        settings.setLeaderboardVisible(false)
        _uiState.update { it.copy(visible = false, declined = true, nicknameError = null) }
        refresh()
    }

    fun onTabSelected(city: Boolean) {
        _uiState.update { it.copy(cityTab = city) }
        refresh()
    }

    /** Ручное обновление (FR-19): outbox-flush + перечитать топ. */
    fun refresh() {
        val client = api ?: return
        val state = _uiState.value
        // Топ читаем и без ника (пользователь «невидим»/не вступал), публикация
        // — только при заданном нике и включённом участии.
        val nickname = state.nickname ?: if (state.declined) "" else return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            withContext(Dispatchers.IO) {
                if (state.visible && nickname.isNotBlank()) {
                    for (snapshot in outbox.pending()) {
                        if (client.publishAggregates(snapshot)) outbox.clear()
                    }
                    client.publishAggregates(buildSnapshot(nickname))
                }
            }
            val home = installedRegionIds().firstOrNull()
            val result = home?.let {
                withContext(Dispatchers.IO) { client.cityTop(it, "distance") }
            }
            when (result) {
                is LeaderboardApi.Result.Ok -> _uiState.update {
                    it.copy(
                        top = result.top.map { entry ->
                            RatingEntryUi(
                                entry.nickname,
                                entry.distanceMeters,
                                entry.fraction,
                                isCurrentUser = entry.nickname == state.nickname,
                            )
                        },
                        // 7.13: успешное обновление снимает прошлый notice —
                        // иначе «Рейтинг недоступен» висел после восстановления сети,
                        // пока пользователь не нажмёт «Понятно».
                        notice = null,
                        loading = false,
                    )
                }
                else -> _uiState.update { it.copy(loading = false, notice = "Рейтинг недоступен (сеть/проект)") }
            }
        }
    }

    /** Отправка после прогулки (сервис/viewmodel): outbox + попытка отправки. */
    fun publishAfterWalk() {
        val nickname = settings.leaderboardNickname()
        val client = api
        if (nickname == null || !settings.leaderboardVisible() || client == null) return
        val snapshot = buildSnapshot(nickname)
        outbox.enqueue(snapshot)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (client.publishAggregates(snapshot)) outbox.clear()
            }
        }
    }

    fun onCreateInvite() {
        val client = api ?: return
        viewModelScope.launch {
            val code = withContext(Dispatchers.IO) { client.createInvite() }
            _uiState.update { it.copy(inviteCode = code, notice = if (code == null) "Не удалось создать приглашение" else null) }
        }
    }

    fun onAcceptInvite(code: String) {
        val client = api ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { client.acceptInvite(code) }
            if (ok) {
                _uiState.update { it.copy(notice = "Приглашение принято!") }
                onFriendAccepted() // D5
            } else {
                _uiState.update { it.copy(notice = "Код не подошёл") }
            }
        }
    }

    fun onNoticeDismissed() {
        _uiState.update { it.copy(notice = null, inviteCode = null) }
    }

    private fun buildSnapshot(nickname: String): LeaderboardSnapshot {
        val fractions = installedRegionIds().mapNotNull { id ->
            (computeRegionProgress.compute(id) as? ProgressResult.Ok)?.let { id to it.progress.fraction }
        }.toMap()
        return LeaderboardSnapshot(nickname, regionAggregates(walkHistory, fractions))
    }
}
