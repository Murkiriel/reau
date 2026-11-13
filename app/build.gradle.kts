import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    jacoco
}

// Semantic version 0.y.z: versionName is "0.3.0" on a release build and "0.3.0-dev+yy.MM.dd-HH.mm" on a debug build, whose
// timestamp tells one debug build from another.
//
// versionCode of a RELEASE is fixed in this file, so that two builds of one commit give the same APK (it used to be the minutes of the
// clock). The scheme: 30_000_000 + major * 1_000_000 + minor * 10_000 + patch * 100, which leaves 99 numbers for a rebuild of the same
// version, and 99 as the limit of minor and patch. The base 30_000_000 is above 29_887_215, the highest versionCode ever published (0.2.0,
// when it was the minutes), because Android only installs over an app whose versionCode is lower; so 0.3.0 is 30_030_000, 0.3.1 is
// 30_030_100, 0.4.0 is 30_040_000, 1.0.0 is 31_000_000. Changing the version means changing the two lines below together:
// VersionCodeSchemeTest checks that they agree, that the number grows and that it stays above the published ones.
// versionCode of a DEBUG build is still the minutes since the Unix epoch, so each debug build installs over the previous one.
val appVersionName = "0.3.0"
val releaseVersionCode = 30_030_000
val buildTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd-HH.mm"))
val debugVersionCode = (System.currentTimeMillis() / 60_000L).toInt()
// The commit the APK was built from, for the ride log header; "?" outside a git checkout.
val buildGitCommit: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "?" } }.getOrElse("?")

// A warning of the Kotlin compiler fails the build (main code, JVM tests and instrumented tests alike), as one of lint does ("lint" below).
// A deprecated call that cannot be avoided says why in a @Suppress next to it. Only the mutation test (scripts/dev/run_unit.py) turns this
// off, with -PreauAllowWarnings: a mutation breaks a decision on purpose and may leave a variable unused, which is not what it is testing.
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(!providers.gradleProperty("reauAllowWarnings").isPresent)
    }
}

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
        versionCode = releaseVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_COMMIT", "\"$buildGitCommit\"")

        testInstrumentationRunner = "com.reau.app.KeepAppStateRunner"
    }

    // One app, com.reau.app, no product flavors (until 0.2.0 there were two editions, "completa" and "lite"; their sources are not in this repository
    // any more, only in the git history up to the commit of 20/10/2026). The app has no accessibility service (the one it had until 0.3.0 is
    // not in this repository either, only in that history).

    // Release signing. The official Releases are signed with the project's own key, which is never in this repository: its
    // path and passwords come from four environment variables. Without them (or with a path that is not a file) packaging a release
    // stops with an error, so a build meant for the rider never comes out signed with the debug key by accident (it did on 28/10/2026:
    // a relative path in the variable was not found). Anyone building from the source can ask for the debug key on purpose, with
    // `-PreauDebugSigned=true`.
    val releaseKeystore: String? = System.getenv("REAU_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).isFile }
    val debugSignedRelease = providers.gradleProperty("reauDebugSigned").orNull == "true"
    gradle.taskGraph.whenReady {
        val packagesRelease = allTasks.any { it.project == project && it.name.startsWith("package") && it.name.endsWith("Release") }
        if (packagesRelease && releaseKeystore == null && !debugSignedRelease) {
            throw GradleException(
                "No release key: REAU_KEYSTORE is not set or is not a file (give its absolute path), with REAU_KEY_ALIAS, " +
                    "REAU_KEYSTORE_PASSWORD and REAU_KEY_PASSWORD. To build a release signed with the debug key on purpose, add -PreauDebugSigned=true.",
            )
        }
    }
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
        // The build the macrobenchmark module measures (`:macrobenchmark`, see CONTRIBUTING): the release code (profileable by the shell, as the main manifest says),
        // not debuggable, always signed with the debug key, so it needs no release key and the check above does not stop it. It is never a build for the rider.
        // The release stubs of src/release/ stand in for the debug tools, as in the release.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
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
            // The JUnit Platform runs the fuzz tests (Jazzer) and, through the vintage engine, the JUnit 4 ones. JAZZER_FUZZ=1 makes the fuzz tests
            // really fuzz (see CONTRIBUTING); without it they only replay their seeds, as a regression test.
            all { test ->
                test.useJUnitPlatform()
                test.outputs.upToDateWhen { System.getenv("JAZZER_FUZZ") != "1" }
            }
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
        getByName("benchmark").kotlin.srcDir("src/release/java")
    }

    // A device that Gradle creates, starts and stops by itself, for the instrumented tests (`./gradlew pixel6Api34DebugAndroidTest`; see CONTRIBUTING). It is only
    // declared here: nothing downloads its system image until that task is run. ATD ("automated test device") is the light image for tests; REau does not
    // use Google Play services. The macrobenchmark module has its own device (a rooted image).
    @Suppress("UnstableApiUsage")
    testOptions.managedDevices.localDevices.create("pixel6Api34") {
        device = "Pixel 6"
        apiLevel = 34
        systemImageSource = "aosp-atd"
    }
}

// The debug build takes its versionCode (and the BuildConfig.VERSION_CODE the ride log prints) from the clock; the release keeps the fixed one.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { it.versionCode.set(debugVersionCode) }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    // Only the @Preview annotations, read when the sources compile: a release APK does not carry them (the debug one gets them
    // through ui-tooling below).
    compileOnly(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    testImplementation(libs.junit)
    // Jazzer (guided fuzzing of the parsers, app/src/test/java/com/reau/app/fuzz): JUnit 5 for its tests, the vintage engine for the JUnit 4 ones.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Coverage of the JVM unit tests: `./gradlew :app:jacocoTestReport` runs them and writes the HTML and XML
// reports to app/build/reports/jacoco/. It is not part of `check`, `assemble` or CI, and it does not touch the APK.
jacoco {
    toolVersion = "0.8.13"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Coverage report (HTML and XML) of the JVM unit tests, in build/reports/jacoco/."
    dependsOn("testDebugUnitTest")
    val classes = fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.class")
    }
    classDirectories.setFrom(classes)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage/debugUnitTest")) { include("*.exec") },
    )
    reports {
        html.required.set(true)
        xml.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacoco.xml"))
    }
}

// Coverage of the JVM unit tests with Kover, in the debug variant: `./gradlew :app:koverHtmlReportDebug` (or `koverXmlReportDebug`) runs them and writes the
// report to build/reports/kover/. It is not part of `check`, `assemble` or CI, and it does not touch the APK. What is left out has no logic of the app's:
// the generated R and BuildConfig classes, the classes the Compose compiler makes for the lambdas of @Composable functions and the Android Auto
// messages that protoc generates (com.reau.app.aa.proto: about two thirds of the lines of the app, which no test is meant to cover one by one).
kover {
    reports {
        filters {
            excludes {
                classes("*.R", "*.R\$*", "*.BuildConfig", "*ComposableSingletons*", "com.reau.app.aa.proto.*")
            }
        }
    }
}

// detekt runs as the stand-alone CLI: the Gradle plugin does not follow Kotlin 2.2 / AGP 9.
// It fails on any finding that is not in config/detekt/baseline.xml, which holds only the accepted debt (long protocol and decoder routines kept as they are on purpose).
val detektCli by configurations.creating
dependencies { detektCli(libs.detekt.cli) }

// What is analysed: the main code and what only the debug build has.
val detektInputs = listOf("main", "debug").map { file("src/$it/java") }.filter { it.isDirectory }

fun detektArgs(extra: List<String>) = listOf(
    "--input", detektInputs.joinToString(",") { it.path },
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
    // Tests that read files from the disk (DefaultsDocsTest, CreditsTest, NumbersAsciiTest, FixedProfilesTest, TermsTest,
    // PrivacyGuardTest, SpellingTest, the strings and the manifest ones): Gradle does not know they do, so a changed sentence of
    // docs/, NOTICE, a README or a comment in a .kt left the test "up to date" without running it. These are what they read.
    inputs.files(fileTree("src") { exclude("**/build/**") }).withPropertyName("sourcesTheTestsRead").withPathSensitivity(PathSensitivity.RELATIVE)
    // PrivacyGuardTest reads every file the repository tracks, so the text files of the whole project count (the build's and the
    // tools' own outputs do not).
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("**/*.md", "**/*.html", "**/*.kts", "**/*.toml", "**/*.yml", "**/*.yaml", "**/*.xml", "**/*.json", "**/*.py", "**/*.txt",
                "**/*.properties", "**/*.sh", "**/*.kt", "LICENSE", "NOTICE", ".gitleaks.toml")
            exclude("**/build/**", "**/.gradle/**", "**/.git/**", "emu_out/**", "**/__pycache__/**", "local.properties", "**/node_modules/**")
        },
    ).withPropertyName("projectFilesTheTestsRead").withPathSensitivity(PathSensitivity.RELATIVE)
    // WrapperJarTest reads the wrapper's jar, which no pattern above covers.
    inputs.file(rootProject.file("gradle/wrapper/gradle-wrapper.jar")).withPropertyName("wrapperJarTheTestsRead")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
