plugins {
    alias(libs.plugins.android.library)
}

// media3 的官方 FFmpeg 扩展模块（Apache-2.0），源码取自 androidx/media 1.11.1。
//
// 这里只做一件事：把 `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` 放进 APK，
// media3 的 DefaultRenderersFactory 会用反射找到它（EXTENSION_RENDERER_MODE_ON）。
//
// .so 是预先编好的（tools/build_ffmpeg.sh），CI 不需要 NDK；
// 想复现或改解码器白名单，跑那个脚本即可。
android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.androidx.media3.exoplayer)
    // media3 的源码用了这两套注解；media3 自己只把 annotation 当 compileOnly 传出来
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
}
