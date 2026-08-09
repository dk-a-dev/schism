package ai.schism.split.sms.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsImportPreferenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        context.getSharedPreferences("sms_import", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `fresh install is disabled`() {
        assertFalse(SmsImportPreference(context).isEnabled)
    }

    @Test fun `explicit choice is synchronous and observable`() = runTest {
        val preference = SmsImportPreference(context)
        preference.setEnabled(true)

        assertTrue(preference.isEnabled)
        assertTrue(preference.enabled.first())

        preference.setEnabled(false)
        assertFalse(preference.isEnabled)
    }
}
