# Contributing to REau

Português (pt-BR): [CONTRIBUTING.pt-br.md](CONTRIBUTING.pt-br.md) · Español: [CONTRIBUTING.es.md](CONTRIBUTING.es.md)

REau has a single maintainer and is a spare-time project: there is no support contract and no fixed response time. A
short, clear report or a small, tested change helps the most.

## License

REau is under the **GNU Affero General Public License v3.0** ([LICENSE](LICENSE)). Anything you contribute is under the
same license. If a change brings in code, data or facts from another project, say where it comes from, so that the
[NOTICE](NOTICE) and the Credits screen (`app/src/main/java/com/reau/app/settings/Credits.kt`) can name it with its license.

## Running the tests

With JDK 21 and the Android SDK installed, from this folder:

```
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:detekt :app:lintDebug --console=plain
```

The first line runs the unit tests; the second runs the code analysis (detekt) and the lint. Fix what
they report in the code you wrote, and do not add anything to the detekt baseline. A warning of the Kotlin compiler fails the build too (`allWarningsAsErrors`): fix it, or, for a deprecated call that cannot be avoided, put a `@Suppress` next to it that says why. Some tests read files from the
repository (`docs/`, `NOTICE`, the READMEs, the changelogs, `app/src/main/res/`): Gradle knows they read those files, so
they run again by themselves when a text changes. The instrumented tests (`app/src/androidTest`) need an
emulator, as the README says; `scripts/emu/` has the scripts.
The Python scripts have their own tests (`python3 -B -m unittest discover -p "test_*.py"` inside `scripts/`, `scripts/registro/`,
`scripts/dev/`, `scripts/emu/` and `bench/`; the last one needs Pillow, pinned in `scripts/requirements.txt`), and the
continuous integration runs them too.

Every code change comes with a new or adjusted test. The project has three languages: texts that people read go in English
(the default), Portuguese (pt-BR) and Spanish, in the `strings.xml` files of `values`, `values-pt` and `values-es`, and the docs have one file
per language. The text behind an (i) gives the general idea in at most 3 sentences and 100 words, with no abbreviation that ends in a period
(`InfoTextsTest` checks it in the three languages).

## Fuzzing and the mutation test

The parsers that read bytes from outside (the dash's packets, a browser's WebSocket frames and HTTP head, and Android Auto's messages, video units, video size header and navigation messages) have fuzz targets in
`app/src/test/java/com/reau/app/fuzz/ParserFuzzTargets.kt`, written with [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
(Apache-2.0, a test dependency only: it never goes into an APK). In the normal unit-test run each target only replays its seeds, the
files under `app/src/test/resources/com/reau/app/fuzz/ParserFuzzTargetsInputs/<target>/`, as a regression test. To really fuzz, steered by
the code each input reaches, set the environment variable (20 seconds per target, `JAZZER_MAX_DURATION` changes it):

```
JAZZER_FUZZ=1 ./gradlew :app:testDebugUnitTest --tests '*ParserFuzzTargets*' --console=plain
```

With `JAZZER_MAX_DURATION` set (for example `5m`) only the first target of a run fuzzes for that long and the others just replay their seeds: run one target at a time (`--tests '*ParserFuzzTargets.dashPacket'`). A run leaves its working corpus in `app/.cifuzz-corpus/` and, if an input broke a parser, a `crash-<hash>` file in `app/` (both ignored by
git). After fixing the bug, copy that file into the target's seed folder, so the same input is tried in every run from now on. A new
target is a method with `@FuzzTest` that takes a `ByteArray`, with a few small seeds of its own (never real captures: see "Personal data").
The unit tests run on the JUnit Platform for this (the vintage engine runs the JUnit 4 ones).

`python3 scripts/dev/mutacoes.py` is the mutation test: it changes one decision of the code at a time and checks that a unit test fails.
`--lista` counts the mutations and `--fatia I/N` runs one slice of them (the weekly workflow runs ten, and `mutacao-mensal.yml` runs one of twelve each month, the month's number); a mutation that survives means a test that
does not hold that decision.

## Coverage, benchmark, managed devices and the dependency check

**Coverage of the unit tests (Kover).** `./gradlew :app:koverHtmlReportDebug` runs the JVM unit tests of the debug variant and writes the HTML report to
`app/build/reports/kover/htmlDebug/` (`:app:koverXmlReportDebug` writes the XML one). It is not part of `check`, `assemble` or the continuous integration, and the APK
is not touched. The generated `R` and `BuildConfig` classes, the Compose compiler's `ComposableSingletons` and the Android Auto messages that protoc generates (`com.reau.app.aa.proto`) are left out of the count.

**Startup benchmark and baseline profile (`macrobenchmark/`).** This module builds without a device (`./gradlew :macrobenchmark:assemble`) but only RUNS on a phone with USB debugging
or on an emulator, and it measures the `benchmark` build type of the app (the release code, signed with the debug key, so it needs no release key). With a device connected:

```
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.reau.app.macrobenchmark.StartupBenchmark
```

`StartupBenchmark` measures the cold start twice, without a baseline profile and with the one of the app; the numbers are `timeToInitialDisplayMs` in the output. Accept the terms
of use on the device first, or it measures the terms screen. On an emulator add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`
(the numbers of an emulator say nothing about a phone). `BaselineProfileGenerator` (same command, with `class=com.reau.app.macrobenchmark.BaselineProfileGenerator`) records what runs while the app opens and
prints where it left the file on the device. The build does not use that file: the app's profile, `app/src/main/baseline-prof.txt`, is written by hand, and you compare the two and add by hand
what is worth adding. The `androidx.baselineprofile` Gradle plugin is not used: it would add build types and generated profile folders that overlap the hand-written one.

**Devices that Gradle manages.** `pixel6Api34` is declared in `app/build.gradle.kts` (the light `aosp-atd` image, for the instrumented tests) and in `macrobenchmark/build.gradle.kts` (the `aosp` image, which can be rooted).
Declaring them downloads nothing. The first run of one of these downloads a system image of some hundreds of MB and creates the virtual device by itself:

```
./gradlew :app:pixel6Api34DebugAndroidTest
./gradlew :macrobenchmark:pixel6Api34BenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

They need hardware virtualization (KVM). They do not use, change or delete the virtual devices you made yourself.

**A new dependency and the PGP check.** `gradle/verification-metadata.xml` checks every artifact the build downloads: by the PGP signature, against the keys in `gradle/verification-keyring.keys`,
when the publisher signs it, and by the sha256 otherwise (many artifacts of Google's Maven are not signed). A build with a dependency that is not in the file fails with
"Dependency verification failed". To add it, run, with the network and from the repository's folder:

```
./gradlew --write-verification-metadata pgp,sha256 --export-keys help testDebugUnitTest detekt lintDebug assembleDebug assembleAndroidTest assembleRelease -PreauDebugSigned=true :macrobenchmark:assemble
```

Gradle keeps the sha256 entries already in the file, adds the new ones, and adds a `trusted-key` for each key that signs the new artifacts (and exports it to the keyring). Then READ the diff: who the key belongs to
and for which group it is trusted; that no sha256 entry was removed; and that no `ignored-key` (a key that could not be downloaded) hides an artifact that had a sha256 before, which would leave it
with no check at all. Commit the metadata and the keyring together (`gradle/verification-keyring.gpg`, the binary copy that Gradle also writes, is not kept). Then run the build again with `--offline`.

## What not to change without a test on the bike

The Android Auto protocol and the dash protocol (`aa/` and `dash/protocol/`), the dash handshake and the video path (the
encoder, the compositor, the pacing) only prove themselves on a real dash. The unit tests do not replace that: do not
change them unless you can say what you tried on the dash, with its firmware. The protocol was observed on firmware 11.63
and verified on 13.10; on another firmware nothing is guaranteed.

## Personal data

Logs, recordings and photos may contain places you went, Wi-Fi names, phone numbers or contact names. Read through them
and cut out anything private before attaching them anywhere, and never put personal data in a test, a log sample or a
screenshot you commit. When in doubt, describe it in words.

A security problem is not reported in a public issue: follow [SECURITY.md](SECURITY.md).

## Reporting a bug

Use the bug report template: it asks for the REau version, the Android Auto version, the dash's firmware and
the phone, and tells where the app shares its logs.
