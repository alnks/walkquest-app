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
    /** Участие (невидимый режим отключает отправку). */
    val visible: Boolean = true,
    /** Запрос ника: ошибка валидации или сети. */
    val nicknameError: String? = null,
    /** Вкладка: true — «Город», false — «Друзья». */
    val cityTab: Boolean = true,
    val top: List<LeaderboardApi.TopEntry> = emptyList(),
    val loading: Boolean = false,
    val inviteCode: String? = null,
    val notice: String? = null,
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
        RatingUiState(nickname = settings.leaderboardNickname(), visible = settings.leaderboardVisible()),
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
                _uiState.update { it.copy(nickname = nickname, visible = true, nicknameError = null) }
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
        _uiState.update { it.copy(visible = false, nicknameError = null) }
    }

    fun onTabSelected(city: Boolean) {
        _uiState.update { it.copy(cityTab = city) }
        refresh()
    }

    /** Ручное обновление (FR-19): outbox-flush + перечитать топ. */
    fun refresh() {
        val client = api ?: return
        val nickname = _uiState.value.nickname ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            withContext(Dispatchers.IO) {
                if (_uiState.value.visible) {
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
                is LeaderboardApi.Result.Ok -> _uiState.update { it.copy(top = result.top, loading = false) }
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
