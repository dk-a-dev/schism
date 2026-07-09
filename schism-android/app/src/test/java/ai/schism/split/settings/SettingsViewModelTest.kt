package ai.schism.split.settings

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.update.UpdateChecker
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: SettingsRepository
    private val updateChecker = UpdateChecker()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun exposesDefaultsForFreshlyClearedRepo() = runTest(dispatcher) {
        val vm = SettingsViewModel(settings, updateChecker)
        backgroundScope.launch { vm.state.collect {} } // keep the WhileSubscribed flow hot

        val ui = vm.state.first { it.currencyCode == "INR" }
        assertEquals("₹", ui.currencySymbol)
        assertEquals("INR", ui.currencyCode)
        assertEquals("", ui.profileName)
    }

    @Test
    fun savedValuesPersistAndSurfaceInState() = runTest(dispatcher) {
        val vm = SettingsViewModel(settings, updateChecker)
        backgroundScope.launch { vm.state.collect {} } // keep the WhileSubscribed flow hot

        vm.saveProfileName("  Dev  ") // setProfileName trims
        vm.saveDefaultCurrency("€", "EUR")

        // Wait for the fully-consistent state (combine of separate DataStore flows can briefly
        // surface a mixed tuple where the code has updated but the symbol hasn't yet).
        val ui = vm.state.first {
            it.profileName == "Dev" && it.currencyCode == "EUR" && it.currencySymbol == "€"
        }
        assertEquals("Dev", ui.profileName)
        assertEquals("€", ui.currencySymbol)
        assertEquals("EUR", ui.currencyCode)
    }
}
