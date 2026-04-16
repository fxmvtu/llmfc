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

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets("aicompose")
                cppFlags += "-std=c++17"
                argument("-DGGML_NATIVE=ON")
                argument("-DGGML_OPENBLAS=OFF")
                argument("-DGGML_ACCELERATE=OFF")
                argument("-DGGML_STATIC=ON")
                argument("-DCMAKE_POSITION_INDEPENDENT_CODE=ON")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        resValues = true
        prefab = true
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
