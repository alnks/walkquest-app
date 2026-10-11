# Правила R8/ProGuard для релизной сборки (AD-8, release arm64 APK ≤ 30 МБ).

# ---------------------------------------------------------------------------
# kotlinx.serialization (plugin генерирует $serializer и Companion.serializer()
# статически, но R8 нужно сказать, что они используются — иначе отрежет).
# Базовый набор из официальной документации kotlinx.serialization.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Сериализуемые классы приложения (app.walkquest.**):
# сгенерированные $$serializer, Companion.serializer() и INSTANCE.
-keep,includedescriptorclasses class app.walkquest.**$$serializer { *; }
-keepclassmembers class app.walkquest.** {
    *** Companion;
}
-keepclasseswithmembers class app.walkquest.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer {
    *** INSTANCE;
}

# Пользовательский сериализатор (OsmIdSerializer, @Serializable(with = ...)).
-keep class app.walkquest.**Serializer { *; }

# ---------------------------------------------------------------------------
# MapLibre Native (org.maplibre.gl): нативная привязка JNI ищет классы/методы
# по имени; AAR несёт consumer-rules, но оставляем страховочный keep.
# ---------------------------------------------------------------------------
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# JNI: имена native-методов и их классов сохранять.
-keepclasseswithmembernames class * {
    native <methods>;
}

# kotlin.Metadata нужна reflection-слою Kotlin (на всякий случай).
-keep class kotlin.Metadata { *; }
