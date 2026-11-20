import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mediocre Toons"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://mediocrescan.com"
    }
}

android {
    sourceSets {
        named("test") {
            kotlin.directories.add("tests/kotlin")
            resources.directories.add("tests/resources")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// KSP requires @Source and must not run on the test sources.
tasks.configureEach {
    if (name.startsWith("ksp") && name.contains("UnitTest")) enabled = false
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}
