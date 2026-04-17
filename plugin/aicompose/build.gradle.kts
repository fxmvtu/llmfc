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
            // NOTE: ABI filtering is handled by splits.abi below, not ndk.abiFilters
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
            // Only arm64-v8a — intentional for this build. 32-bit ARM (armeabi-v7a)
            // is excluded because llama.cpp inference is slow on 32-bit and most
            // devices running this plugin are 64-bit capable.
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
    // llama.cpp: git submodule at ../../../llama.cpp
    // Built as a static library (libllama.a) via CMake + externalNativeBuild
    // (see src/main/cpp/CMakeLists.txt for build configuration)
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:plugin-base"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Unit tests — Kotlin stdlib + JUnit 4
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
