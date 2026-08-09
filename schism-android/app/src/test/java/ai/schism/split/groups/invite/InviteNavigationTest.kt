package ai.schism.split.groups.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link parsing that decides where an incoming invite (or an old group link) is routed. */
class InviteNavigationTest {

    @Test
    fun parsesTheDeepLinkTheHttpsLandingAndABareToken() {
        assertEquals("tok-1", parseInviteToken("schism://invite/tok-1"))
        assertEquals("tok-1", parseInviteToken("https://api.schism.ai/i/tok-1"))
        assertEquals("tok-1", parseInviteToken("https://api.schism.ai/i/tok-1?utm=whatsapp"))
        assertEquals("tok-1", parseInviteToken("  tok-1  "))
    }

    @Test
    fun sharedLinkIsTheHttpsLandingForTheToken() {
        assertTrue(inviteLink("tok-1").endsWith("/i/tok-1"))
        assertEquals("tok-1", parseInviteToken(inviteLink("tok-1")))
    }

    @Test
    fun legacyGroupLinksAreRecognisedAndNeverYieldAToken() {
        assertTrue(isLegacyGroupLink("schism://group/g1"))
        assertTrue(isLegacyGroupLink("https://api.schism.ai/g/g1"))
        assertFalse(isLegacyGroupLink("schism://invite/tok-1"))

        assertEquals("", parseInviteToken("schism://group/g1"))
        assertEquals("", parseInviteToken("https://api.schism.ai/g/g1"))
    }

    @Test
    fun unrelatedLinksAndEmptyInputYieldNothing() {
        assertEquals("", parseInviteToken(""))
        assertEquals("", parseInviteToken("https://example.com/hello"))
    }
}
