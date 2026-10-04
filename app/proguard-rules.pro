# Hidden AudioPolicy APIs are reached by reflection.
-keep class android.media.audiopolicy.** { *; }
-keepclassmembers class com.questsoundboard.audio.MicInjector { *; }
