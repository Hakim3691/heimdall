plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Test doubles (fake clock, recording logger, synthetic frames) shared by unit and BDD suites.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
}
