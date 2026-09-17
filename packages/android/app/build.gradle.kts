plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.trindade.app"
    // The pinned androidx/OkHttp versions (Compose 1.12.x, OkHttp 5.5.0) require API 37;
    // compileSdk is build-time only and stays independent of the targetSdk product floor.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.trindade.app"
        // Android 8.0 is a deliberate floor: field devices still on Oreo remain common.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The base URL is configuration, never a constant: the same build must be able to point at a
        // deployment that does not exist yet. The default targets the emulator host loopback, which
        // is the one cleartext host the debug network security config exempts.
        //
        // The value is validated rather than accepted, because every failure mode here is silent:
        // a URL without a scheme or without its trailing slash compiles, installs, and then fails
        // at runtime inside Retrofit with an error that does not name the cause. Checking it here
        // turns that into a configuration failure.
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:3000/"
        require(apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://")) {
            "apiBaseUrl must be an absolute http(s) URL, but was '$apiBaseUrl'."
        }
        require(apiBaseUrl.endsWith("/")) {
            "apiBaseUrl must end with '/', because Retrofit resolves every endpoint relative to it. " +
                "Got '$apiBaseUrl'."
        }
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// A release build must carry an https base URL, and there is deliberately no default: shipping
// against the emulator loopback is not a configuration anyone chooses on purpose, so it fails here
// rather than on a user's device, where the release manifest grants no cleartext exception and the
// request could not succeed anyway.
//
// This is a task action rather than a `require` in the android block, and the difference is not
// cosmetic. AGP configures every build type before running any task, so a configuration-time check
// here failed `assembleDebug` as well -- breaking the debug build while trying to guard the release
// one. Checking at execution time scopes the rule to the two tasks it is actually about.
val declaredApiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: ""
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        require(declaredApiBaseUrl.isNotEmpty()) {
            "A release build requires -PapiBaseUrl=https://<host>/. There is no default, so that a " +
                "release can never silently point at the development loopback."
        }
        require(declaredApiBaseUrl.startsWith("https://")) {
            "A release build requires an https apiBaseUrl, but got '$declaredApiBaseUrl'."
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
