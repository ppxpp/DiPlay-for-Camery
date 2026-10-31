pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xcertplay"
include(":common")
include(":mobile")
include(":installer")
project(":installer").projectDir = file("installer/android")
include(":installer-shared")
project(":installer-shared").projectDir = file("installer/shared")
include(":installer-desktop")
project(":installer-desktop").projectDir = file("installer/desktop")
include(":automotive")
include(":shared")
include(":maphost")
project(":maphost").projectDir = file("samples/maphost")
include(":home")
project(":home").projectDir = file("samples/home")
include(":usbcheck")
include(":pioneer-adapter")
include(":pioneer-ceam-stubs")
include(":bluetooth-probe")
include(":usb-permission-probe")
