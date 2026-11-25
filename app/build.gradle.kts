import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val heimdallVersionName: String = providers.gradleProperty("heimdall.versionName").get()
val heimdallVersionCode: Int = providers.gradleProperty("heimdall.versionCode").get().toInt()
val llamaDir: String = providers.gradleProperty("llamaDir")
    .getOrElse(rootProject.file("native/third_party/llama.cpp").absolutePath)
val buildVulkan: String = providers.gradleProperty("heimdall.vulkan").getOrElse("OFF")

/**
 * Release signing. CI injects a keystore via keystore.properties (never committed for real
 * deployments). When absent, the release build falls back to the debug key so that
 * `assembleRelease` still produces an installable artifact for sideload testing.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { stream -> load(stream) }
    }
}

android {
    namespace = "com.heimdall"
    compileSdk = 35
    ndkVersion = providers.gradleProperty("heimdall.ndkVersion").get()

    defaultConfig {
        applicationId = "com.heimdall"
        minSdk = 31
        targetSdk = 35
        versionCode = heimdallVersionCode
        versionName = heimdallVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en")

        externalNativeBuild {
            cmake {
                // NOTE: neither -fno-exceptions nor -fno-rtti may be passed: llama.cpp's gguf
                // reader uses try/catch and llama.cpp uses dynamic_cast. Both stay at the
                // NDK defaults (enabled) for every native target.
                cppFlags += listOf("-std=c++17")
                arguments += listOf(
                    "-DHEIMDALL_BUILD_VULKAN=$buildVulkan",
                    "-DLLAMA_DIR=$llamaDir",
                    "-DANDROID_STL=c++_shared",
                )
                targets += "heimdall"
            }
        }
        val abis = providers.gradleProperty("heimdall.abis").orNull
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            ?: listOf("arm64-v8a", "x86_64")
        ndk {
            // arm64-v8a is the shipping target; x86_64 keeps emulator/CI instrumented runs possible.
            abiFilters += abis
        }
    }

    externalNativeBuild {
        cmake {
            path = rootProject.file("native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
        xmlReport = true
        htmlReport = true
        sarifReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":engine:trigger"))
    implementation(project(":engine:vlm"))
    implementation(project(":engine:governor"))
    implementation(project(":ui:overlay"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
}
