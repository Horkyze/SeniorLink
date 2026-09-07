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
        val response = CompletableDeferred<UpdateCheckResult>()
        var calls = 0
        val factory = factory { calls++; response.await() }
        val model = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
        assertNull(model.availableUpdate.value)
        runCurrent()
        assertEquals(1, calls)
        assertNull(model.availableUpdate.value)
        response.complete(UpdateCheckResult.Available(update))
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
        val model = ViewModelProvider(store, factory { UpdateCheckResult.UpToDate })[UpdateViewModel::class.java]
        runCurrent()
        assertNull(model.availableUpdate.value)
        assertNull(model.manualResult.value)
        assertFalse(model.checking.value)
    }

    @Test fun `startup failure is silent and manual retry reports up to date`() = runTest(dispatcher) {
        var calls = 0
        val model = ViewModelProvider(store, factory {
            if (calls++ == 0) UpdateCheckResult.Failed else UpdateCheckResult.UpToDate
        })[UpdateViewModel::class.java]
        runCurrent()
        assertNull(model.manualResult.value)
        assertNull(model.availableUpdate.value)
        model.checkForUpdates()
        assertTrue(model.checking.value)
        runCurrent()
        assertEquals(UpdateCheckResult.UpToDate, model.manualResult.value)
        assertFalse(model.checking.value)
        assertNull(model.availableUpdate.value)
        assertEquals(2, calls)
    }

    @Test fun `manual check offers an update again after Later`() = runTest(dispatcher) {
        var calls = 0
        val available = UpdateCheckResult.Available(update)
        val model = ViewModelProvider(store, factory { calls++; available })[UpdateViewModel::class.java]
        runCurrent()
        model.dismiss()
        assertNull(model.availableUpdate.value)
        model.checkForUpdates()
        runCurrent()
        assertEquals(update, model.availableUpdate.value)
        assertEquals(available, model.manualResult.value)
        assertEquals(2, calls)
    }

    @Test fun `only one check runs at a time across startup taps and recreation`() = runTest(dispatcher) {
        var response = CompletableDeferred<UpdateCheckResult>()
        var calls = 0
        val factory = factory { calls++; response.await() }
        val model = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
        model.checkForUpdates()
        runCurrent()
        assertEquals(1, calls)
        response.complete(UpdateCheckResult.UpToDate)
        runCurrent()
        response = CompletableDeferred()
        model.checkForUpdates()
        model.checkForUpdates()
        runCurrent()
        val recreated = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
        assertSame(model, recreated)
        assertTrue(recreated.checking.value)
        recreated.checkForUpdates()
        assertEquals(2, calls)
        response.complete(UpdateCheckResult.Failed)
        runCurrent()
        assertEquals(UpdateCheckResult.Failed, recreated.manualResult.value)
        assertFalse(recreated.checking.value)
    }

    @Test fun `failed manual check can be retried and unexpected failures do not stick loading`() = runTest(dispatcher) {
        var calls = 0
        val model = ViewModelProvider(store, factory {
            when (calls++) {
                0 -> UpdateCheckResult.UpToDate
                1 -> throw java.io.IOException("offline")
                else -> UpdateCheckResult.Available(update)
            }
        })[UpdateViewModel::class.java]
        runCurrent()
        model.checkForUpdates()
        runCurrent()
        assertEquals(UpdateCheckResult.Failed, model.manualResult.value)
        assertFalse(model.checking.value)
        model.checkForUpdates()
        assertNull(model.manualResult.value)
        runCurrent()
        assertEquals(update, model.availableUpdate.value)
        assertEquals(UpdateCheckResult.Available(update), model.manualResult.value)
        assertEquals(3, calls)
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

    private fun factory(check: suspend () -> UpdateCheckResult) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(check) as T
    }
}
