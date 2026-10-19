import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

// Semantic version 0.y.z: versionName is "0.1.0" on a release build and "0.1.0-dev+yy.MM.dd-HH.mm" on a debug build, whose
// timestamp tells one debug build from another. versionCode is the minutes since the Unix epoch (it never goes down). Both
// come from the same instant, so they describe one build, and the ride log can say exactly which APK wrote it.
val appVersionName = "0.1.0"
val buildTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd-HH.mm"))
val buildVersionCode = (System.currentTimeMillis() / 60_000L).toInt()
// The commit the APK was built from, for the ride log header; "?" outside a git checkout.
val buildGitCommit: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "?" } }.getOrElse("?")

android {
    namespace = "com.reau.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.reau.app"
        // API 29 is the real floor: the dash link is built on WifiNetworkSpecifier and
        // ConnectivityManager.requestNetwork(specifier), both added in API 29.
        minSdk = 29
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_COMMIT", "\"$buildGitCommit\"")

        testInstrumentationRunner = "com.reau.app.KeepAppStateRunner"
    }

    // Two editions of the app from the same sources, installable side by side (their ids differ):
    //  - completa: id com.reau.app; any Android Auto; has the accessibility service that switches the Android Auto
    //    head unit server on (Android Auto 17.4 or newer).
    //  - lite: id com.reau.app.lite; no accessibility service (its source is in src/completa only, and the
    //    manifest in src/lite takes it out); the rider switches the server on by hand, and Android Auto 17.3 or
    //    older is asked to dial in instead.
    // The code tells them apart by Edition (src/main), never by testing the flavor by name.
    flavorDimensions += "edition"
    productFlavors {
        create("completa") {
            dimension = "edition"
            buildConfigField("boolean", "LITE", "false")
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            buildConfigField("boolean", "LITE", "true")
        }
    }

    // Release signing. The official Releases are signed with the project's own key, which is never in this repository: its
    // path and passwords come from four environment variables. Without them (or with a path that is not a file) the release
    // build is signed with the debug key, with no error, so anyone can still build from the source.
    val releaseKeystore: String? = System.getenv("REAU_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).isFile }
    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                keyAlias = System.getenv("REAU_KEY_ALIAS")
                storePassword = System.getenv("REAU_KEYSTORE_PASSWORD")
                keyPassword = System.getenv("REAU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev+$buildTimestamp"
            // Only makes the JVM unit tests write their coverage data (see jacocoTestReport); the APK is not instrumented.
            enableUnitTestCoverage = true
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // No baseline: a warning fails `lintDebug`. lint.xml switches off only what does not apply, each with its reason.
    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = true
    }

    testOptions {
        unitTests {
            // Plain JUnit tests run against the android.jar stub, which throws on any framework call. The copied
            // pure-logic classes call android.util.Log here and there; this makes those calls return a default.
            isReturnDefaultValues = true
        }
    }

    // Test doubles that both the JVM tests and the instrumented ones use (the fake Android Auto peer) live in one
    // place and are compiled into both. They are plain JVM code: no Android classes.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// `lintDebug` and `testDebugUnitTest` cover both editions; each edition's own tasks (lintLiteDebug, ...) exist too.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "JVM unit tests of both editions' debug variants."
    dependsOn("testCompletaDebugUnitTest", "testLiteDebugUnitTest")
}
tasks.register("lintDebug") {
    group = "verification"
    description = "Lint of both editions' debug variants; any warning fails."
    dependsOn("lintCompletaDebug", "lintLiteDebug")
}

// Coverage of the JVM unit tests of the full edition: `./gradlew :app:jacocoTestReport` runs them and writes the HTML and XML
// reports to app/build/reports/jacoco/. It is not part of `check`, `assemble` or CI, and it does not touch the APK.
jacoco {
    toolVersion = "0.8.13"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Coverage report (HTML and XML) of the full edition's JVM unit tests, in build/reports/jacoco/."
    dependsOn("testCompletaDebugUnitTest")
    val classes = fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/completaDebug/compileCompletaDebugKotlin/classes")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.class")
    }
    classDirectories.setFrom(classes)
    sourceDirectories.setFrom(files("src/main/java", "src/completa/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage/completaDebugUnitTest")) { include("*.exec") },
    )
    reports {
        html.required.set(true)
        xml.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacoco.xml"))
    }
}

// The instrumented tests drive the accessibility service and the full edition's screens; they run on "completa" only.
androidComponents {
    beforeVariants(selector().withFlavor("edition" to "lite")) { variant ->
        variant.androidTest.enable = false
    }
}

// detekt runs as the stand-alone CLI: the Gradle plugin does not follow Kotlin 2.2 / AGP 9.
// It fails on any finding that is not in config/detekt/baseline.xml, which holds only the accepted debt (long protocol and decoder routines kept as they are on purpose).
val detektCli by configurations.creating
dependencies { detektCli(libs.detekt.cli) }

fun detektArgs(extra: List<String>) = listOf(
    "--input", file("src/main/java").path,
    "--config", rootProject.file("config/detekt/detekt.yml").path,
    "--build-upon-default-config",
    "--baseline", rootProject.file("config/detekt/baseline.xml").path,
) + extra

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Static analysis with detekt; fails on findings that are not in config/detekt/baseline.xml."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    val out = layout.buildDirectory.dir("reports/detekt").get().asFile
    doFirst { out.mkdirs() }
    args(detektArgs(listOf("--report", "txt:${out.path}/detekt.txt", "--report", "html:${out.path}/detekt.html")))
}

tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Rewrites config/detekt/baseline.xml with the current findings (accept the existing debt)."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args(detektArgs(listOf("--create-baseline")))
}

// Unit test failures print their message and stack in the build log, so a failure on a CI runner can be read
// without the HTML report.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
