plugins { id("com.android.library") }

check(listOf("diplay.hudShadow", "diplay.hudObserver").none { providers.gradleProperty(it).orNull == "true" }) {
    "The temporary HUD shadow/observer builds have been retired; use the normal build."
}

android {
    buildFeatures { buildConfig = true }
    namespace = "com.shilapi.xcertplay.pioneer"
    compileSdk = 37
    defaultConfig {
        minSdk = 23; consumerProguardFiles("consumer-rules.pro")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.10.2" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // Legacy findings are baselined; only new findings fail the build.
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    compileOnly(project(":pioneer-ceam-stubs"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

abstract class PioneerGeneratedAssets : Exec() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val assetsDirectory: org.gradle.api.file.DirectoryProperty
}
val buildPioneerHudFilterAssets by tasks.registering(PioneerGeneratedAssets::class) {
    inputs.files(fileTree("src/main/hud-filter"), file("scripts/build-hud-filter-assets.py"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/hud-filter-assets"))
    commandLine("python3", file("scripts/build-hud-filter-assets.py").absolutePath)
}
val buildPioneerWirelessAssets by tasks.registering(PioneerGeneratedAssets::class) {
    inputs.files(fileTree("src/main/cpp/wireless"),file("scripts/build-wireless-assets.sh"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/wireless-assets"))
    commandLine("bash",file("scripts/build-wireless-assets.sh").absolutePath)
}
// Declare producers, rather than static folders, so AGP merges the just-built payloads.
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(buildPioneerWirelessAssets) { it.assetsDirectory }
    variant.sources.assets?.addGeneratedSourceDirectory(buildPioneerHudFilterAssets) { it.assetsDirectory }
}

val checkRootServiceArchitecture by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/java"), rootProject.fileTree("shared/src/main/java/com/shilapi/xcertplay/vehicle/adapters/pioneer/wireless"))
    inputs.file(rootProject.file("scripts/check_root_service_architecture.py"))
    commandLine("python3", rootProject.file("scripts/check_root_service_architecture.py").absolutePath)
}
tasks.named("preBuild") { dependsOn(checkRootServiceArchitecture) }
