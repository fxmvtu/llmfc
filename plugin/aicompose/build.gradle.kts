plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.fcitx-component")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.aicompose"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.aicompose"

        ndk {
            version = "25c"  // Explicit NDK version — required by llama.cpp CMake
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets("aicompose")
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENBLAS=OFF",
                    "-DGGML_ACCELERATE=OFF",
                    "-DGGML_STATIC=ON",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
                )
            }
        }
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/libc++_shared.so",
                "**/libFcitx5*"
            )
        }
    }
}

fcitxComponent {
    installPrebuiltAssets = true
}

dependencies {
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:plugin-base"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.annotation:annotation:1.7.1")
}
