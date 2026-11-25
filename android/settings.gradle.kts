pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

// The CI runner and Android Studio already supply JDK 25. Resolve a downloader only for other JDKs.
buildscript {
    if (JavaVersion.current() != JavaVersion.VERSION_25) {
        repositories { gradlePluginPortal() }
        dependencies { classpath("org.gradle.toolchains:foojay-resolver:1.0.0") }
    }
}
if (JavaVersion.current() != JavaVersion.VERSION_25) {
    apply(plugin = "org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "Castor"
include(":app")
include(":client")
