package com.reau.app.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The package the benchmarks start: the app of this repository, in its "benchmark" build type. */
internal const val TARGET_PACKAGE = "com.reau.app"

/** How many times each start is measured. */
private const val START_ITERATIONS = 5

/**
 * How long the app takes to open from a cold start (the process is killed before each start), with the code compiled as the system leaves it right after
 * an install (no baseline profile) and with the baseline profile of the app (app/src/main/baseline-prof.txt) compiled in. The two numbers side by side
 * are what the profile buys. The result is in the test's output (`timeToInitialDisplayMs`) and in the JSON file the run writes.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = START_ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
