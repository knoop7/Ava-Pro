plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.protobuf)
}

android {
    namespace = "com.example.ava"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ava"
        minSdk = 21
        targetSdk = 36

        val geckoAbi = project.findProperty("geckoAbi")?.toString()
        ndk {
            if (geckoAbi != null) {
                abiFilters.add(geckoAbi)
            } else {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
            }
        }
        versionCode = if (project.ext.has("versionCode"))
            project.ext.get("versionCode").toString().toInt() else 78
        versionName = if (project.ext.has("versionName"))
            project.ext.get("versionName").toString() else "0.7.8"
        // Gecko engine APK uses fixed filename so the download URL never changes across releases.
        base.archivesName = if (geckoAbi != null) {
            when (geckoAbi) {
                "arm64-v8a" -> "gecko-arm64"
                "armeabi-v7a" -> "gecko-armv7"
                else -> "gecko-${geckoAbi.replace("-", "")}"
            }
        } else {
            "Ava-$versionName"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two render engines from one codebase:
    //  - lite : system WebView only. This is the main published APK; its size is unchanged.
    //  - gecko: bundles GeckoView for old devices. Shipped as a SEPARATE downloadable APK.
    // Different applicationId so both can be installed side-by-side. The lite app detects
    // and launches the gecko app when the user selects GeckoView as the render engine.
    flavorDimensions += "engine"
    productFlavors {
        create("lite") {
            dimension = "engine"
            isDefault = true
        }
        create("gecko") {
            dimension = "engine"
            applicationIdSuffix = ".gecko"
            versionNameSuffix = "-gecko"
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = java.util.Properties()
    val hasReleaseKeystore = keystorePropertiesFile.exists()
    if (hasReleaseKeystore) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                val storePath = keystoreProperties.getProperty("storeFile")
                storeFile = when {
                    storePath.isNullOrBlank() -> null
                    File(storePath).isAbsolute -> File(storePath)
                    else -> rootProject.file(storePath)
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // GeckoView requires Java 17. Java 17 source/target is backward compatible for the lite flavor.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    lint {
        // 默认 true 会让每次 assembleRelease 都捎带跑 lintVital，本项目 Compose 量大，
        // 这一步能占掉编译的一大截时间。要查 lint 时单独跑 ./gradlew :app:lintLiteRelease。
        checkReleaseBuilds = false
    }

    // Keep bundled adb binary byte-identical in the APK (adbhelper 32-bit static).
    androidResources {
        noCompress += "adb"
    }

    packaging {
        jniLibs {
            // zlib-compress jniLibs in APK (~7.7MB ort → ~2.9MB); extractNativeLibs=true unpacks on install
            useLegacyPackaging = true
            pickFirsts.add("**/libc++_shared.so")
            pickFirsts.add("**/libjsc.so")
        }
    }

    applicationVariants.configureEach {
        outputs.configureEach {
            if (flavorName == "lite" && this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "Ava-${versionName}-release.apk"
            }
        }
    }
}

dependencies {

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(project(":esphomeproto"))
    implementation(project(":microfeatures"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.litert)
    implementation(libs.protobuf.kotlin)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.webkit:webkit:1.14.0")
    
    implementation(libs.jmdns)
    
    implementation("com.microsoft.clarity:clarity-compose:3.4.3")
    
    // AirPlay mod (DexClassLoader) resolves Media3 + MediaSessionCompat against the host.
    // Keep these on the host classpath; R8 keep rules below preserve original class names.
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation(libs.material3)
    implementation(libs.colorpicker.compose)
    
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Real-time blur overlay (frosted glass) for DreamClock picker / overlay chrome.
    // 144KB source, ~50KB AAR. API 31+ uses RenderEffect; older falls back to RenderScript.
    implementation("com.github.Dimezis:BlurView:version-3.2.0")
    // Physics-based settle animation for the browser edge sidebar (matches the spring feel used
    // by the Compose home-screen sidebar drawer).
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    // Settings LazyColumn fling: vendored Flinger 1.3 core under ui/scroll/fling (no Maven dep).

    // GeckoView is only compiled into the `gecko` flavor, so the lite APK stays the same size.
    // 稳定版（非 nightly），钉死版本保证可复现/不漂移。选 134 线的原因：
    //   - 145 起 GeckoView 把最低要求提到 Android 8 (API 26)，会让旧设备(minSdk 21)装不上；
    //     所以最高只能用 144。
    //   - 版本越新体积越大（134≈76MB / 144≈81MB / 152≈85MB，arm64 aar），134 最小；
    //   - 134 仍是较新的引擎，网页兼容性足够，且远新于旧设备自带的系统 WebView。
    // 如需更新的引擎且仍兼容旧设备，可换成最后一个支持 API 21 的 144.0.20251027123126。
    "geckoImplementation"("org.mozilla.geckoview:geckoview:134.0.20250120135430")

    // Pure-Java xz decoder: the running app downloads the gecko engine as a single .apk.xz
    // (kept <=80MB) and decompresses it on device before install. ~115KB, all Android API levels.
    implementation("org.tukaani:xz:1.10")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("org.jflac:jflac-codec:1.5.2")
    
    
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

apply(plugin = "com.google.protobuf")
