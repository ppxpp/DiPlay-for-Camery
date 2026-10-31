plugins { alias(libs.plugins.android.application) }
val probeAuthAssets = providers.gradleProperty("probeAuthenticationAssetsDir")
    .orElse(providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR"))
    .getOrElse(rootProject.file(".private/runtime-assets").absolutePath).let { file(it) }
android {
    namespace = "com.shilapi.xcertplay.btprobe"
    compileSdk { version = release(37) }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.10.2" } }
    defaultConfig {
        // 车机的安装白名单只放行少量包名（如 com.tencent.mm）；
        // 其他包名 adb install 会报 INSTALL_FAILED_VERIFICATION_FAILURE: app is not in the whitelist。
        // 探针沿用白名单包名以便在目标车机安装；报告中的 UID 与包名按实测记录。
        applicationId = providers.gradleProperty("probeApplicationId").orElse("com.tencent.wecarflow").get()
        minSdk = 23
        targetSdk = providers.gradleProperty("probeTargetSdk").orElse("23").get().toInt()
        versionCode = 15
        versionName = "0.9.0-pioneer-callback-repair"
        multiDexEnabled = true
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/blueforce-host-assets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(probeAuthAssets)
    sourceSets.getByName("main").assets.srcDir(rootProject.file(".private/probe-network-assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":pioneer-adapter"))
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.core:core:1.13.1")
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
}

val verifyProbeAuthentication by tasks.registering {
    val material = listOf("identity.pk8", "certificate.p7b").map { probeAuthAssets.resolve("offline-mfi/$it") }
    inputs.files(material)
    doLast {
        check(material.all { it.isFile && it.length() in 1L..16384L }) {
            "Probe MFi assets missing: use the existing DiPlay .private/runtime-assets or probeAuthenticationAssetsDir"
        }
        val allowed = material.map { it.canonicalFile }.toSet()
        check(probeAuthAssets.walkTopDown().filter { it.isFile }.all { it.canonicalFile in allowed }) {
            "Probe authentication assets directory must contain only offline-mfi/identity.pk8 and certificate.p7b"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyProbeAuthentication) }

val buildProbeBlueforceHost by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp"), file("scripts/build-blueforce-host.sh"))
    outputs.files(listOf("blueforce-host", "blueforce-callback-repair").map { layout.buildDirectory.file("generated/blueforce-host-assets/$it") })
    commandLine("bash", file("scripts/build-blueforce-host.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn(buildProbeBlueforceHost) }
