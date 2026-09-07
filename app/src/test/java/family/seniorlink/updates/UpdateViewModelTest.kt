package family.seniorlink.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val update = AppUpdate("0.1.10", "https://github.com/Horkyze/SeniorLink/releases/download/v0.1.10/app.apk")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `startup does not wait for network and retained activity state checks only once`() = runTest(dispatcher) {
        val response = CompletableDeferred<AppUpdate?>()
        var calls = 0
        val factory = factory { calls++; response.await() }
        val model = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
        assertNull(model.availableUpdate.value)
        runCurrent()
        assertEquals(1, calls)
        assertNull(model.availableUpdate.value)
        response.complete(update)
        runCurrent()
        assertEquals(update, model.availableUpdate.value)

        // A recreated activity gets the same ViewModel and pending prompt.
        val recreated = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
        assertSame(model, recreated)
        model.dismiss()
        runCurrent()
        assertNull(recreated.availableUpdate.value)
        assertEquals(1, calls)

        store.clear()
        val reopened = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
        runCurrent()
        assertEquals(2, calls)
        assertEquals(update, reopened.availableUpdate.value)
    }

    @Test fun `no update leaves the prompt hidden`() = runTest(dispatcher) {
        val model = ViewModelProvider(store, factory { null })[UpdateViewModel::class.java]
        runCurrent()
        assertNull(model.availableUpdate.value)
    }

    @Test fun `closing the activity cancels its pending check`() = runTest(dispatcher) {
        var cancelled = false
        ViewModelProvider(store, factory {
            try { awaitCancellation() } finally { cancelled = true }
        })[UpdateViewModel::class.java]
        runCurrent()
        store.clear()
        runCurrent()
        assertTrue(cancelled)
    }

    private fun factory(check: suspend () -> AppUpdate?) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(check) as T
    }
}
