package com.tranthor.push

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundRefreshTest {
    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = throw UnsupportedOperationException()
    }

    /** Binding for each new SDK instance must not leave the earlier observers (and their pushes) registered. */
    @Test fun bindingAgainReplacesThePreviousObserver() {
        val active = LinkedHashSet<DefaultLifecycleObserver>()
        val refresh = ForegroundRefresh(add = { active.add(it) }, remove = { active.remove(it) })
        val calls = IntArray(3)
        for (index in calls.indices) refresh.install { calls[index]++ }
        assertEquals("Only the latest binding stays observed", 1, active.size)
        active.single().onStart(owner)
        assertEquals(listOf(0, 0, 1), calls.toList())
    }
}
