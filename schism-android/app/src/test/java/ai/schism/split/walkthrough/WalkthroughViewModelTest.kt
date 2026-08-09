package ai.schism.split.walkthrough

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalkthroughViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeWalkthroughRepository()
    private val userIds = MutableStateFlow("u1")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repo: WalkthroughRepository = repository) = WalkthroughViewModel(repo, userIds)

    @Test
    fun theTourIsOfferedThenAcceptedAtTheFirstStep() = runTest {
        val vm = viewModel()
        assertEquals(WalkthroughStatus.ELIGIBLE, vm.state.value.status)

        vm.offerTour()
        assertEquals(WalkthroughStatus.OFFERED, vm.state.value.status)

        vm.acceptTour()
        assertEquals(WalkthroughStatus.ACTIVE, vm.state.value.status)
        assertEquals(WalkthroughStep.GROUP, vm.state.value.currentStep)
    }

    @Test
    fun everyStepAdvancesOnlyOnItsOwnAction() = runTest {
        val vm = viewModel()
        vm.acceptTour()

        vm.completeStep(WalkthroughStep.BALANCES)
        assertEquals(WalkthroughStep.GROUP, vm.state.value.currentStep)

        vm.completeStep(WalkthroughStep.GROUP)
        vm.completeStep(WalkthroughStep.RECEIPT)
        assertEquals(WalkthroughStep.ASSIGN, vm.state.value.currentStep)
    }

    @Test
    fun theAssignStepAdvancesOnARealClaimAndKeepsTotalsBalanced() = runTest {
        val vm = viewModel()
        vm.acceptTour()
        vm.completeStep(WalkthroughStep.GROUP)
        vm.completeStep(WalkthroughStep.RECEIPT)

        val before = vm.demoSnapshot.value
        vm.assignItem("paneer-tikka", setOf("demo-kabir"))

        assertEquals(WalkthroughStep.BALANCES, vm.state.value.currentStep)
        assertNotEquals(before.participantTotalsMinor, vm.demoSnapshot.value.participantTotalsMinor)
        assertEquals(
            vm.demoSnapshot.value.receipt.totalMinor,
            vm.demoSnapshot.value.participantTotalsMinor.values.sum(),
        )
    }

    @Test
    fun finishingTheLastStepCompletesTheTourAndClearsTheDemo() = runTest {
        val vm = viewModel()
        vm.acceptTour()
        val pristine = vm.demoSnapshot.value
        vm.assignItem("lime-sodas", setOf("demo-maya"))
        WalkthroughStep.entries.forEach(vm::completeStep)

        assertEquals(WalkthroughStatus.COMPLETED, vm.state.value.status)
        assertNull(vm.state.value.currentStep)
        assertEquals(pristine, vm.demoSnapshot.value)
    }

    @Test
    fun skipWorksFromEveryStateAndAlsoClearsTheDemo() = runTest {
        val offered = viewModel(FakeWalkthroughRepository()).apply { offerTour(); skipTour() }
        assertEquals(WalkthroughStatus.SKIPPED, offered.state.value.status)

        val vm = viewModel()
        vm.acceptTour()
        vm.completeStep(WalkthroughStep.GROUP)
        val pristine = DemoRepository().snapshot
        vm.assignItem("garlic-naan", setOf("demo-you"))
        vm.skipTour()

        assertEquals(WalkthroughStatus.SKIPPED, vm.state.value.status)
        assertNull(vm.state.value.currentStep)
        assertEquals(pristine, vm.demoSnapshot.value)
    }

    @Test
    fun backIsBoundedByTheFirstStep() = runTest {
        val vm = viewModel()
        vm.acceptTour()

        repeat(3) { vm.back() }
        assertEquals(WalkthroughStep.GROUP, vm.state.value.currentStep)

        vm.completeStep(WalkthroughStep.GROUP)
        vm.back()
        assertEquals(WalkthroughStep.GROUP, vm.state.value.currentStep)
    }

    @Test
    fun processDeathRestoresTheSameStep() = runTest {
        val vm = viewModel()
        vm.acceptTour()
        vm.completeStep(WalkthroughStep.GROUP)

        val restored = viewModel()
        assertEquals(WalkthroughStatus.ACTIVE, restored.state.value.status)
        assertEquals(WalkthroughStep.RECEIPT, restored.state.value.currentStep)
    }

    @Test
    fun progressAndDismissedTipsAreIsolatedPerAccount() = runTest {
        val vm = viewModel()
        vm.acceptTour()
        vm.dismissHint(FeatureHint.SMS_OPT_IN)

        userIds.value = "u2"
        assertEquals(WalkthroughState(), vm.state.value)

        userIds.value = "u1"
        assertEquals(WalkthroughStatus.ACTIVE, vm.state.value.status)
        assertEquals(setOf("sms_opt_in"), vm.state.value.dismissedHintIds)
    }

    @Test
    fun replayAndResetTipsAreIndependent() = runTest {
        val vm = viewModel()
        vm.acceptTour()
        vm.dismissHint(FeatureHint.OCR_DOWNLOAD)
        WalkthroughStep.entries.forEach(vm::completeStep)

        vm.replayTour()
        assertEquals(WalkthroughStatus.ACTIVE, vm.state.value.status)
        assertEquals(setOf("ocr_download"), vm.state.value.dismissedHintIds)

        vm.resetTips()
        assertTrue(vm.state.value.dismissedHintIds.isEmpty())
        assertEquals(WalkthroughStatus.ACTIVE, vm.state.value.status)
    }

    @Test
    fun theViewModelOnlyDependsOnProgressAndAnAccountId() {
        val parameters = WalkthroughViewModel::class.java.declaredConstructors.single().parameterTypes
        assertEquals(
            listOf(WalkthroughRepository::class.java, Flow::class.java),
            parameters.toList(),
        )
    }

    @Test
    fun persistedProgressSurvivesAnEncodeDecodeRound() {
        val state = WalkthroughState(
            status = WalkthroughStatus.ACTIVE,
            currentStep = WalkthroughStep.BALANCES,
            dismissedHintIds = setOf("sms_opt_in", "upi_settle"),
        )

        assertEquals(state, decodeWalkthroughState(encodeWalkthroughState(state)))
        assertEquals(
            WalkthroughState(),
            decodeWalkthroughState(encodeWalkthroughState(WalkthroughState())),
        )
        assertNull(decodeWalkthroughState("garbage"))
        assertNull(decodeWalkthroughState("1|NOPE||"))
    }
}

private class FakeWalkthroughRepository : WalkthroughRepository {
    private val records = mutableMapOf<String, MutableStateFlow<WalkthroughState?>>()

    private fun record(userId: String) = records.getOrPut(userId) { MutableStateFlow(null) }

    override fun observe(userId: String): Flow<WalkthroughState?> = record(userId)

    override suspend fun save(userId: String, state: WalkthroughState) {
        record(userId).value = state
    }

    override suspend fun clear(userId: String) {
        record(userId).value = null
    }
}
