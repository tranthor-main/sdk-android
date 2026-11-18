package com.tranthor.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushConfigurationTest {
    @Test fun recentEventLimitIsClamped() {
        assertEquals(1, PushConfiguration("a.b", 0).recentEventLimit)
        assertEquals(10_000, PushConfiguration("a.b", 50_000).recentEventLimit)
        assertEquals(1024, PushConfiguration("a.b").recentEventLimit)
    }
}
