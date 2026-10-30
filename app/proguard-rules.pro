# yt-dlp/ffmpeg/aria2c are invoked as native processes through JNI; nothing to
# reflect on, but the library's own classes must not be renamed or stripped.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# Jackson ships inside the library and reaches for classes by name.
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }

# kotlinx.serialization generates the serializers; keep the annotations and the
# generated companions for our yt-dlp JSON models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.teal.downloader.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.teal.downloader.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room generates implementations at build time; nothing further needed here.
-keep class * extends androidx.room.RoomDatabase
