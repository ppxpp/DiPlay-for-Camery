import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":installer-shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.shilapi.xcertplay.installer.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "DiPlayInstaller"
            packageVersion = "0.2.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("../packaging/resources"))
        }
    }
}

val osName = System.getProperty("os.name").orEmpty().lowercase()
val portableTarget = when {
    osName.contains("win") -> "windows-x64"
    osName.contains("mac") -> "macos"
    else -> "linux"
}

tasks.register<Zip>("packagePortableZip") {
    group = "distribution"
    description = "Package the portable app image as a ZIP."
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("DiPlayInstaller-0.2.0-$portableTarget.zip")
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
}
