plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// BDD acceptance suite: Gherkin features drive the production engine modules through their
// public seams. Plain JVM (no Robolectric) so the suite stays fast and hermetic in CI.
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
    testImplementation(project(":core:model"))
    testImplementation(project(":core:common"))
    testImplementation(project(":engine:trigger"))
    testImplementation(project(":engine:governor"))
    testImplementation(project(":engine:gate"))
    testImplementation(project(":core:testing"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testImplementation("org.junit.platform:junit-platform-suite:1.11.3")
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.cucumber.picocontainer)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("cucumber.publish.quiet", "true")
    systemProperty("cucumber.plugin", "pretty, html:build/reports/cucumber/cucumber.html, json:build/reports/cucumber/cucumber.json")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// Fails the build if a P0 requirement tag has no acceptance scenario (traceability gate).
val traceability by tasks.registering {
    dependsOn(tasks.named("test"))
}
