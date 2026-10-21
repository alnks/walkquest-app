package app.walkquest

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.walkquest.di.AppContainer
import app.walkquest.logging.WQ_DEBUG
import app.walkquest.logging.WQ_ERROR
import app.walkquest.logging.WQ_INFO
import app.walkquest.logging.WQ_VERBOSE
import app.walkquest.logging.WQ_WARN
import app.walkquest.logging.WqLayer
import app.walkquest.logging.WqLog
import org.maplibre.android.MapLibre

/**
 * Точка входа приложения: инициализация MapLibre (AD-1) и ручной DI-контейнер (AD-10).
 */
class WalkQuestApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Разноуровневое debug-логирование (WQ/*): sink живёт только в
        // debuggable-сборке; release — WqLog отключён, вызовы из data/UI — no-op.
        val debug = applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        WqLog.plant(debug) { priority, tag, message, throwable ->
            when (priority) {
                WQ_VERBOSE -> android.util.Log.v(tag, message, throwable)
                WQ_DEBUG -> android.util.Log.d(tag, message, throwable)
                WQ_INFO -> android.util.Log.i(tag, message, throwable)
                WQ_WARN -> android.util.Log.w(tag, message, throwable)
                WQ_ERROR -> android.util.Log.e(tag, message, throwable)
                else -> android.util.Log.d(tag, message, throwable)
            }
        }
        container = AppContainer(
            filesDir,
            lastKnownLocation = ::lastKnownLocationOrNull,
            debugEnabled = debug,
        )
        WqLog.i(WqLayer.SCREEN) { "Application.onCreate (debug=$debug)" }
    }

    /**
     * Последняя известная локация системы (lat, lon) или null: разрешение не выдано,
     * фикса ещё не было, провайдеры выключены. Только чтение кэша ОС — новых запросов
     * локации и ожидания здесь нет; используется предложением первого региона (D1).
     */
    private fun lastKnownLocationOrNull(): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = getSystemService(LocationManager::class.java) ?: return null
        // SecurityException возможен при отзыве разрешения между проверкой и вызовом.
        val location = runCatching {
            manager.getProviders(true).firstNotNullOfOrNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
        }.getOrNull() ?: return null
        return location.latitude to location.longitude
    }
}
