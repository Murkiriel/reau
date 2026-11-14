plugins {
    alias(libs.plugins.android.test)
}

// The macrobenchmark module: it measures the app, it is not in the app. It starts the "benchmark" build type of :app (the release code, profileable, signed with the
// debug key) on a device and measures how long the app takes to open (StartupBenchmark) or writes down what runs while it opens (BaselineProfileGenerator).
// It needs a device or an emulator to RUN; compiling it needs none (`./gradlew :macrobenchmark:assemble`). How to run it: CONTRIBUTING.
// Same rule as the app: a warning of the Kotlin compiler fails the build.
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "com.reau.app.macrobenchmark"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"
    // The test APK runs in its own process and starts the app's; the measuring needs no help of the app.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Debuggable here (it is the tests' APK, not the app's) and signed with the debug key; the app's build type of the same name is the one measured.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // A device that Gradle creates, starts and stops by itself (`./gradlew pixel6Api34BenchmarkAndroidTest`; see CONTRIBUTING). It is only declared here: nothing
    // downloads its system image until that task is run. The "aosp" image (no Google apps) can be rooted, which the baseline profile generator uses on an
    // Android older than 13. It is an emulator, so the numbers of StartupBenchmark are not the ones of a phone (the benchmark refuses to run on one unless
    // told; the command in CONTRIBUTING does).
    @Suppress("UnstableApiUsage")
    testOptions.managedDevices.localDevices.create("pixel6Api34") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "aosp"
    }
}

// Only the benchmark variant makes sense here (it is the one that targets the app's benchmark build type); with it alone, `assembleDebug` and the other tasks of the
// app's chain do not build this module.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    // The runner of the tests (AndroidJUnit4), the version the app's instrumented tests use.
    implementation(libs.androidx.junit)
}
