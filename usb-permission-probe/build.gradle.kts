plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.shilapi.xcertplay.usbpermission"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.gtmc.app.fengyunyx.b819" // Same installation identity as :mobile.
        minSdk = 23
        targetSdk = 37
        versionCode = 1000
        versionName = "usb-permission-probe-1"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(project(":shared"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
