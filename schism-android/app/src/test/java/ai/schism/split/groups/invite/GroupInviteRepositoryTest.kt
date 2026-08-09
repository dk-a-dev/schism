package ai.schism.split.groups.invite

import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupInviteRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService
    private lateinit var repo: GroupInviteRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient.create(server.url("/").toString())
        repo = GroupInviteRepository(api)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun createMintsTheGroupLinkAndRevokeDeletesIt() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"token":"tok-1"}"""))
        assertEquals("tok-1", repo.create("g1").getOrThrow())
        val created = server.takeRequest()
        assertEquals("POST", created.method)
        assertEquals("/v1/groups/g1/invite-link", created.path)

        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(repo.revoke("g1").isSuccess)
        val revoked = server.takeRequest()
        assertEquals("DELETE", revoked.method)
        assertEquals("/v1/groups/g1/invite-link", revoked.path)
    }

    @Test
    fun previewExposesOnlyTheGroupNameAndMemberCount() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"groupId":"g1","groupName":"Goa Trip","memberCount":4}"""),
        )

        val preview = repo.preview("tok-1").getOrThrow()

        assertEquals("/v1/group-invites/tok-1", server.takeRequest().path)
        assertEquals("Goa Trip", preview.groupName)
        assertEquals(4, preview.memberCount)
        // Any id the server might send is never decoded, so it cannot leak into the cache.
        assertFalse(preview.toString().contains("g1"))
    }

    @Test
    fun redeemReturnsTheGroupId() = runTest {
        server.enqueue(MockResponse().setBody("""{"groupId":"g1"}"""))

        assertEquals("g1", repo.redeem("tok-1").getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/group-invites/tok-1/redeem", request.path)
    }

    @Test
    fun deadRevokedAndForbiddenLinksMapToTheirOwnErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(InviteError.NotFound, repo.preview("nope").exceptionOrNull())

        // The backend answers 410 for both expiry and revocation; both say "ask for a new one".
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"invite revoked"}"""))
        assertEquals(InviteError.Expired, repo.redeem("tok-1").exceptionOrNull())

        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(InviteError.NotMember, repo.create("g1").exceptionOrNull())

        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(InviteError.SignInRequired, repo.redeem("tok-1").exceptionOrNull())
    }

    @Test
    fun groupLinksAreRecognisedAndKeptApartFromParticipantLinks() {
        assertEquals("tok-1", parseGroupInviteToken("https://api.schism.ai/i/g/tok-1"))
        assertEquals("tok-1", parseGroupInviteToken(" schism://group-invite/tok-1 "))
        assertEquals("tok-1", parseGroupInviteToken("https://api.schism.ai/i/g/tok-1?utm=x"))
        // A per-person link is not a group link, and vice versa.
        assertEquals("", parseGroupInviteToken("https://api.schism.ai/i/tok-1"))
        assertEquals("", parseGroupInviteToken("schism://invite/tok-1"))
        assertTrue(groupInviteLink("tok-1").endsWith("/i/g/tok-1"))
    }
}
