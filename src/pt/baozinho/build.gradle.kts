import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Baozinho"
    versionCode = 6
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://baozinho.com"
        lang = "pt-BR"
    }

    deeplink {
        path("/..*")
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

// O KSP exige @Source e não deve rodar sobre o código de teste.
tasks.configureEach {
    if (name.startsWith("ksp") && name.contains("UnitTest")) enabled = false
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}
