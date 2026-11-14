package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The thread of a bounded task can say what it is for (the codec metrics' read is not a release): the default stays the release's name. */
class BoundedReleaseThreadNameTest {
    @Test
    fun `the thread is named by the caller, and by default as the release's`() {
        var named = ""
        var default = ""
        BoundedRelease.run({ named = Thread.currentThread().name }, joinMs = 2_000L, onStuck = {}, threadName = CodecMetrics.THREAD_NAME)
        BoundedRelease.run({ default = Thread.currentThread().name }, joinMs = 2_000L, onStuck = {})
        assertEquals("CodecMetrics", named)
        assertEquals(BoundedRelease.THREAD_NAME, default)
    }
}
