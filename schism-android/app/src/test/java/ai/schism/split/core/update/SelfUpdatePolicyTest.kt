package ai.schism.split.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Play-installed build must never offer its own APK update — that is a Play Device and Network
 * Abuse violation, and the update banner links directly at a GitHub release asset.
 */
class SelfUpdatePolicyTest {

    @Test
    fun `play installs never self-update`() {
        assertFalse(selfUpdateAllowed("com.android.vending"))
        assertFalse(selfUpdateAllowed("com.google.android.feedback"))
    }

    @Test
    fun `sideloaded installs still self-update`() {
        // Nothing else tells these builds a new version exists.
        assertTrue(selfUpdateAllowed(null))
        assertTrue(selfUpdateAllowed("com.google.android.packageinstaller"))
        assertTrue(selfUpdateAllowed("com.android.shell"))
        assertTrue(selfUpdateAllowed("org.fdroid.fdroid"))
    }
}
