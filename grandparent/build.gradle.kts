plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "studio.otherstuff.ambulando.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "studio.otherstuff.grandparentchat.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "GRANDPARENT_CHAT", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Uses the same native core as Ambulando.
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets.getByName("main") {
        kotlin.directories.add("../app/src/main/kotlin")
        res.directories.add("../app/src/main/res")
        assets.directories.add("../app/src/main/assets")
        manifest.srcFile("src/main/AndroidManifest.xml")
    }
    buildTypes { release { vcsInfo { include = false } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("test").kotlin.directories.add("../app/src/testFamily/kotlin")
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**") }
    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK (Zapstore/F-Droid friendly).
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Shared source includes Ambulando's optional push adapter; the family app does not enroll it.
    implementation("com.google.firebase:firebase-messaging:26.0.0")
    implementation(project(":core"))
    implementation(libs.secp256k1.jni.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work)
    implementation(libs.androidx.exifinterface)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // The JVM secp256k1 binding, so JVM tests can sign real NIP-98 events (the Android binding only loads on a device).
    testImplementation(libs.secp256k1.jni.jvm)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}


configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") || it.name.endsWith("UnitTestCompileClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite")).using(module("androidx.sqlite:sqlite-jvm:${libs.versions.androidxSqlite.get()}"))
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.androidxSqlite.get()}"))
    }
}
