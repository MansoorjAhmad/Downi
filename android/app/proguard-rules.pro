# Chaquopy Python runtime & Java callbacks
-keep class com.chaquo.python.** { *; }
-keep class com.omnidownloader.app.DownloadProgressListener { *; }
-keepclassmembers class * implements com.omnidownloader.app.DownloadProgressListener {
    *;
}

# Room entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Preserve stack trace line numbers
-keepattributes SourceFile,LineNumberTable
