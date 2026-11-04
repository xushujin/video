# media3 用反射找 FfmpegAudioRenderer（Class.forName），R8 看不见这条引用。
# 不 keep 的话 release 包里类会被裁掉，运行时直接回落到平台解码器。
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# JNI 方法名必须原样保留，否则 native 绑定会找不到实现
-keepclasseswithmembernames class * {
    native <methods>;
}
