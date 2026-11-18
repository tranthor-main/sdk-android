package com.tranthor.sdk

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunOnMainTest {
    /** Stands in for the main looper: runs each posted task on its own thread and records what escapes it. */
    private class FakeLooper {
        val uncaught = AtomicReference<Throwable?>()
        fun post(task: Runnable) {
            val thread = Thread(task, "fake-main")
            thread.setUncaughtExceptionHandler { _, error -> uncaught.set(error) }
            thread.start()
            thread.join()
        }
    }

    @Test fun runsTheBlockOnTheLooperAndReturnsAfterIt() = runBlocking {
        val looper = FakeLooper()
        var ranOn: String? = null
        runOnMain(onMainThread = false, post = looper::post) { ranOn = Thread.currentThread().name }
        assertEquals("fake-main", ranOn)
        assertNull(looper.uncaught.get())
    }

    /** A throwing block must fail the awaiting caller, not crash the main looper. */
    @Test fun aThrowingBlockFailsTheCallerInsteadOfCrashingTheLooper() = runBlocking {
        val looper = FakeLooper()
        try {
            runOnMain(onMainThread = false, post = looper::post) { throw IllegalStateException("observer rejected") }
            fail("The caller must see the exception")
        } catch (error: IllegalStateException) {
            assertEquals("observer rejected", error.message)
        }
        assertNull("The exception must not escape on the looper thread", looper.uncaught.get())
    }

    @Test fun onTheMainThreadTheBlockRunsInline() = runBlocking {
        var ran = false
        runOnMain(onMainThread = true, post = { fail("Must not post") }) { ran = true }
        assertTrue(ran)
    }

    /** A caller that gave up must not leave work behind: the block would act for an SDK nobody holds. */
    @Test fun aCallerCancelledBeforeTheLooperRunsTheTaskNeverRunsTheBlock() = runBlocking {
        var posted: Runnable? = null
        var ran = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            runOnMain(onMainThread = false, post = { posted = it }) { ran = true }
        }
        caller.cancel()
        caller.join()
        posted!!.run()
        assertEquals("The block must not run for a cancelled caller", false, ran)
    }
}
