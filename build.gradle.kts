import org.jetbrains.changelog.Changelog
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

changelog {
    // The plugin's own headings ("Added", "Fixed") are the grouping; an empty set stops
    // `patchChangelog` from adding a second, empty layer of them.
    groups.empty()
    repositoryUrl = "https://github.com/no1sebomb/Cloudflared-Runner-IDEA"
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

        // The Marketplace "What's New" tab. Falls back to [Unreleased] so a build made between
        // releases still shows the notes that are about to ship.
        //
        // Goes through `changelog.instance` rather than the extension's own `getOrNull`/`renderItem`
        // helpers: those resolve against the extension, which drags a `Project` reference into the
        // provider, and the configuration cache refuses to serialize one.
        changeNotes = changelog.instance.zip(providers.gradleProperty("version")) { log, pluginVersion ->
            val item = log.items[pluginVersion] ?: log.unreleasedItem
            // Null when the changelog has neither this version nor an [Unreleased] section, which
            // is a changelog worth nothing to the Marketplace anyway.
            item?.let {
                log.renderItem(
                    it.withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }.orEmpty()
        }

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
