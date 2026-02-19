# ---------------------------------------------------------
# REGLAS DEL ESCUDO PARA SIGNALFUSION PRO
# ---------------------------------------------------------

# Mantener intacta la conexión a internet (OkHttp y Retrofit)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }

# Mantener intactas las Corrutinas (Trabajos en segundo plano)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Mantener intactos los gráficos del Historial
-keep class com.github.mikephil.charting.** { *; }