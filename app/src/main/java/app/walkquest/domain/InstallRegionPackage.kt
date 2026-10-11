package app.walkquest.domain

import app.walkquest.data.packages.PackageInstallResult
import app.walkquest.data.packages.RegionPackageRepository

/**
 * Use-case установки пакета региона (история 1.5, AD-6).
 *
 * Скачивание, верификация (formatVersion + контрольные суммы) и атомарная
 * распаковка делегированы data/packages; здесь только точка входа для
 * viewmodel по связке ui → viewmodel → domain → data (AD-10).
 * Ошибки приходят типизированными в [PackageInstallResult.Failure] —
 * UI показывает `error.message` как есть.
 */
class InstallRegionPackage(private val repository: RegionPackageRepository) {

    suspend operator fun invoke(regionId: String): PackageInstallResult =
        repository.installRegion(regionId)
}
