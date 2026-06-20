import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.tudominio.parentalcontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tudominio.parentalcontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.tudominio.parentalcontrol.MyHiltTestRunner"

        // T4 of `hotfix-parent-auth-session` — toggleable Ktor MockEngine.
        // `local.properties` sets `USE_MOCK_SUPABASE=true` for demo/dev so
        // the dashboard renders fixture devices without hitting the
        // placeholder Supabase URL. Default is `false` (real engine) so
        // production builds never accidentally serve fixtures.
        val useMockSupabase: String =
            (project.findProperty("USE_MOCK_SUPABASE") as String?) ?: "false"
        buildConfigField("boolean", "USE_MOCK_SUPABASE", useMockSupabase)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // QR
    implementation(libs.qrcode) {
        // qrose:1.0.0 pulls both qrose-core-android and qrose-android transitively;
        // exclude the core variant — qrose-android already bundles it.
        exclude(group = "io.github.alexzhirkevich", module = "qrose-core-android")
    }

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Play Integrity
    implementation("com.google.android.gms:play-services-instantapps:18.0.1")
    implementation("com.google.android.play:integrity:1.3.0")

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.hilt.navigation.compose)
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)

    // Network (Ktor for Supabase REST API)
    implementation(libs.kotlinx.serialization.json)
    implementation("io.ktor:ktor-client-okhttp:2.3.4")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.4")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.4")
    implementation("io.ktor:ktor-websockets:2.3.4")
    // T5 of `hotfix-parent-auth-session` — Ktor MockEngine for the
    // toggleable Supabase mock path. Lives in main classpath so the
    // `NetworkModule` can bind a MockEngine-backed HttpClient when
    // `BuildConfig.USE_MOCK_SUPABASE=true`.
    implementation("io.ktor:ktor-client-mock:2.3.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Firebase
    implementation(platform(libs.firebase.bom))

    // Security (Tink for encrypted storage, Keystore for session tokens)
    implementation(libs.tink.android)

    // Camera
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.datastore.preferences)
    androidTestImplementation(libs.tink.android)
    debugImplementation(libs.compose.ui.test.manifest)

    // Desugaring
    coreLibraryDesugaring(libs.desugar)
}

// =============================================================================
// DETEKT - Static Analysis
// =============================================================================
detekt {
    buildUponDefaultConfig = true
    allRules = false
}

// Configure JVM target for detekt
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
}

// =============================================================================
// KTLINT - Kotlin Static Analysis
// =============================================================================
// Disable the `import-ordering` rule in PR 4 of `align-with-guia-fedora44`.
// The default `ij_kotlin_imports_layout` pattern does not match the layout
// used by the Hilt-aware singletons introduced in this PR (mixing
// `dagger.hilt.*`, `java.*`, `javax.*`, `kotlinx.*` in one block). The
// IntelliJ-style property in `.editorconfig` is ignored by ktlint 0.43.2
// for unknown reasons — explicit gradle-level disable is the safest path.
ktlint {
    disabledRules.set(setOf("import-ordering"))
}

// LINT - Android Static Analysis
// =============================================================================
// Baseline captures pre-existing issues (FcmPushService misconfiguration
// in AndroidManifest.xml:105 — already failing on master, unrelated to PR 1).
android {
    lint {
        baseline = file("config/lint/lint-baseline.xml")
    }
}

// Kotlin 2.3+ requires the new compilerOptions DSL; the old `kotlinOptions` block
// is now a hard error (previously a suppressable deprecation warning).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
