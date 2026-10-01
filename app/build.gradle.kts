plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ruxi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ruxi.app"
        minSdk = 26
        targetSdk = 35
        // CI 每次构建递增，否则覆盖安装会被 Android 拒（"已存在更高版本"）
        versionCode = (System.getenv("RUXI_VERSION_CODE") ?: "1").toIntOrNull() ?: 1
        versionName = System.getenv("RUXI_VERSION_NAME") ?: "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Material3 的 TopAppBar / TextField 等仍是 @ExperimentalMaterial3Api，
        // 不 opt-in 就是编译错误（不是警告）。全局开比在每个文件写
        // @OptIn(...) 干净得多 —— 本项目有 5+ 处用到。
        // ExperimentalFoundationApi 是 combinedClickable（长按）需要的。
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    // 固定签名：CI runner 每次都是全新环境，若不指定就各签各的，
    // 用户每次装新版都会被「签名不一致」拦下。
    signingConfigs {
        create("ruxi") {
            storeFile = file("../keystore/ruxi-release.jks")
            storePassword = "ruxi2026signing"
            keyAlias = "ruxi"
            keyPassword = "ruxi2026signing"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ruxi")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ruxi")
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // 网络：SSE 逐行读用 okio 的 BufferedSource，比手写缓冲区稳。
    // 流式请求单独一个 OkHttpClient 实例（独立连接池），避免坏连接污染后续请求。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON：编译期生成序列化器，配合 ignoreUnknownKeys + 默认值，
    // 是「向后兼容旧配置文件」最稳的方案（Gson 会绕过 Kotlin 非空/默认值）。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 图片加载（角色头像、聊天内图片）
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
