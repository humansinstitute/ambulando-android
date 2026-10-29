import javax.inject.Inject
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
        applicationId = "studio.otherstuff.ambulando.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Zapstore distributes arm64-v8a only; the test emulator is arm64 as well.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing comes only from the environment (see scripts/release-build.sh); nothing is stored in the repo.
    val releaseStore = System.getenv("AMBULANDO_RELEASE_STORE")?.takeIf { File(it).isFile }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = File(releaseStore)
                storeType = "PKCS12"
                storePassword = System.getenv("AMBULANDO_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("AMBULANDO_RELEASE_KEY_ALIAS") ?: "ambulando-release"
                keyPassword = System.getenv("AMBULANDO_RELEASE_STORE_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Public export history is separate from private development history.
            vcsInfo { include = false }
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**") }
    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK (Zapstore/F-Droid friendly).
        includeInApk = false
        includeInBundle = false
    }
}

kotlin { jvmToolchain(21) }

dependencies {
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
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}

/**
 * Builds the embedded document and board editor pages (web-editors/, needs bun) from their locked sources and
 * serves them to the app as assets under `editors/`. Release builds therefore never ship a stale or hand-copied bundle.
 */
abstract class BuildWebEditors : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val webDir: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val lockFiles: ConfigurableFileCollection
    @get:Internal abstract val projectDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction fun build() {
        val dir = projectDir.get().asFile
        val bun = listOf(System.getenv("BUN"), "${System.getProperty("user.home")}/.bun/bin/bun", "/opt/homebrew/bin/bun", "/usr/local/bin/bun")
            .firstOrNull { it != null && File(it).canExecute() }
            ?: throw GradleException("bun is required to build the editor pages (https://bun.sh); set BUN=/path/to/bun.")
        exec.exec { workingDir = dir; commandLine(bun, "install", "--frozen-lockfile") }
        exec.exec { workingDir = dir; commandLine(bun, "run", "build") }
        fs.sync { from(File(dir, "dist")); into(outputDir.get().dir("editors")) }
    }
}

val buildWebEditors = tasks.register<BuildWebEditors>("buildWebEditors") {
    val editors = rootProject.layout.projectDirectory.dir("web-editors")
    projectDir.set(editors)
    webDir.set(editors.dir("web"))
    lockFiles.from(editors.file("package.json"), editors.file("bun.lock"), editors.file("vite.config.mjs"))
    outputDir.set(layout.buildDirectory.dir("generated/webEditors"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildWebEditors, BuildWebEditors::outputDir)
    }
}
// JVM (Robolectric) unit tests open a real LocalStore: on the host they need the JVM builds of androidx.sqlite (the
// Android builds alias SQLiteException to the framework class and load the bundled driver as an Android .so).
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") || it.name.endsWith("UnitTestCompileClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite")).using(module("androidx.sqlite:sqlite-jvm:${libs.versions.androidxSqlite.get()}"))
        substitute(module("androidx.sqlite:sqlite-bundled")).using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.androidxSqlite.get()}"))
    }
}
