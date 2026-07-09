package ai.schism.split.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerPatchVersionIsDetected() {
        assertTrue(isNewer("1.1.2", "1.1.1"))
    }

    @Test
    fun newerMinorVersionIsDetected() {
        assertTrue(isNewer("1.2.0", "1.1.9"))
    }

    @Test
    fun identicalVersionsAreNotNewer() {
        assertFalse(isNewer("1.1.1", "1.1.1"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(isNewer("1.0.0", "1.1.0"))
    }

    @Test
    fun differentComponentCountsCompareCorrectly() {
        assertFalse(isNewer("1.1", "1.1.0"))
        assertTrue(isNewer("1.1.1", "1.1"))
    }
}
