// Root of the Heimdall build: every module shares one toolchain, one Kotlin compiler
// configuration, and one test-report layout so CI can collect them uniformly.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

allprojects {
    group = "com.heimdall"
    version = providers.gradleProperty("heimdall.versionName").get()
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/llama.cpp/**", "**/third_party/**")
        ktlint("1.3.1").editorConfigOverride(
            mapOf(
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                "ktlint_standard_function-expression-body" to "disabled",
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_class-signature" to "disabled",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.3.1")
    }
    format("misc") {
        target("**/*.md", "**/*.yml", "**/*.yaml", "**/*.json", "**/.gitignore")
        targetExclude("**/build/**", "**/node_modules/**", "**/.gradle/**", "**/third_party/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val detektConfigFile = file("config/detekt/detekt.yml")

subprojects {
    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(detektConfigFile)
            parallel = false
        }
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            jvmTarget = "17"
            ignoreFailures = false
            reports {
                html.required.set(true)
                xml.required.set(true)
                sarif.required.set(true)
                txt.required.set(false)
            }
            exclude("**/build/**", "**/third_party/**", "**/resources/**")
        }
    }
}

tasks.register("cleanAll") {
    dependsOn(subprojects.map { "${it.path}:clean" })
}
