import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 签名材料有两条通道，优先级从高到低：
//   1) 环境变量 KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//      —— CI（GitHub Actions）用，密钥由仓库 Secrets 注入，不落仓库；
//   2) 仓库根目录的 keystore.properties —— 本机开发用（该文件与 keystore/ 均不入库）。
// 两条都没有时不配置签名：release 出未签名包（F-Droid 这类下游会自己重签），
// 但 assembleDebug 仍能构建 —— 别人 clone 下来什么都不配也能跑起来。
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val ciKeystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = ciKeystorePath != null || keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.azhan.qingying"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.azhan.qingying"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.4.0"
    }

    // 只保留中英两种语言资源，顺带把 AndroidX 里其它语种的翻译裁掉。
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                if (ciKeystorePath != null) {
                    storeFile = file(ciKeystorePath)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                } else {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 设置页要显示版本号，用 BuildConfig 避免手写版本字符串跟构建配置脱节。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// APK 产物统一命名：QingYing-<versionName>-<buildType>.apk
//
// AGP 9 的公共接口 VariantOutput 不暴露 outputFileName，只能用它的实现类。
// 这里做了类型判断 —— 将来 AGP 若调整内部类，文件名会退回默认的 app-<buildType>.apk，
// 但构建不会失败。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl
                ?: return@forEach
            val version = impl.versionName.orNull ?: "1.0.0"
            impl.outputFileName.set("QingYing-$version-${variant.name}.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    // 视频解码与后台播放：ExoPlayer 跑在 MediaSessionService 里，
    // UI 通过 MediaController 遥控（含把 SurfaceView 交给播放器渲染）。
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
