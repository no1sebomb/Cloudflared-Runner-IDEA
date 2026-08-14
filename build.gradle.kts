import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("org.jlleitschuh.gradle.ktlint")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

kotlin {
    jvmToolchain(21)
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)
    }
}

// Rules are configured in .editorconfig.
ktlint {
    version = "1.8.0"
    // Plain output for the build log, Checkstyle XML for CI report artifacts.
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Cloudflared Runner"

        ideaVersion {
            sinceBuild = "253"
            // Open-ended: the plugin only uses long-stable platform APIs.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        // Compilation already pins the oldest supported build (sinceBuild 253), so the
        // verifier's job is catching APIs removed since then — the newest IDE is enough.
        // `recommended()` adds every release in the open-ended range: several GB more per run.
        ides {
            latest()
        }
    }
}
