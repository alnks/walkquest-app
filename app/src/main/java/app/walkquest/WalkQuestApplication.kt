package app.walkquest

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.walkquest.di.AppContainer
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
        container = AppContainer(
            filesDir,
            lastKnownLocation = ::lastKnownLocationOrNull,
            debugEnabled = applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
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
