plugins { alias(libs.plugins.android.application) }
android {
    namespace = "studio.otherstuff.ambulando.garminfixture"
    compileSdk = 36
    defaultConfig {
        // Deliberately matches Garmin's service package, on disposable emulators ONLY.
        applicationId = "com.garmin.android.apps.connectmobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 10000
        versionName = "ipc-fixture"
    }
}
dependencies { implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.2.0@aar") }
