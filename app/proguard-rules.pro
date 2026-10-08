# Kadb builds ADB/TLS state with reflection-free Kotlin, but its SPAKE2 pairing library and
# cert utilities touch java.security providers by name. Keep them whole; the size cost is small.
-keep class com.flyfishxu.kadb.** { *; }
-keep class com.github.Flyfish233.** { *; }
-dontwarn com.flyfishxu.kadb.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep the entity so Room's generated code and our Flow types line up after shrinking.
-keep class dev.tubebeam.data.TvDevice { *; }
