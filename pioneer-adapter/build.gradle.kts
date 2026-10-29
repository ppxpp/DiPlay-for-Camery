plugins { id("com.android.library") }

android {
    buildFeatures { buildConfig = true }
    namespace = "com.shilapi.xcertplay.pioneer"
    compileSdk = 37
    defaultConfig { minSdk = 23; consumerProguardFiles("consumer-rules.pro") }
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

// Standalone ARM32 helpers load the verified vendor ABI outside the app linker namespace.
androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/wireless-assets").get().asFile.absolutePath)
}
val buildPioneerWirelessAssets by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp/wireless"),file("scripts/build-wireless-assets.sh"))
    outputs.dir(layout.buildDirectory.dir("generated/wireless-assets"))
    commandLine("bash",file("scripts/build-wireless-assets.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn(buildPioneerWirelessAssets) }
