package ai.schism.split.groups.invite

import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParticipantInviteRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService
    private lateinit var repo: ParticipantInviteRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient.create(server.url("/").toString())
        repo = ParticipantInviteRepository(api)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun createRequestsAFreshTokenForOneParticipant() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"token":"tok-1","expiresAt":"2026-08-16T00:00:00Z"}"""),
        )

        assertEquals("tok-1", repo.create("g1", "p2").getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/groups/g1/participants/p2/invite", request.path)
    }

    @Test
    fun previewExposesOnlyGroupAndParticipantNames() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"groupId":"g1","groupName":"Goa Trip","currency":"₹",
                    "participantId":"p2","participantName":"Sam",
                    "expiresAt":"2026-08-16T00:00:00Z"}""",
            ),
        )

        val preview = repo.preview("tok-1").getOrThrow()

        assertEquals("/v1/invites/tok-1", server.takeRequest().path)
        assertEquals("Goa Trip", preview.groupName)
        assertEquals("Sam", preview.participantName)
        // The group id the server also sends is never decoded, so it cannot leak into the cache.
        assertFalse(preview.toString().contains("g1"))
    }

    @Test
    fun redeemReturnsTheGroupId() = runTest {
        server.enqueue(MockResponse().setBody("""{"groupId":"g1"}"""))

        assertEquals("g1", repo.redeem("tok-1").getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/invites/tok-1/redeem", request.path)
    }

    @Test
    fun replayExpiredAndLinkedProduceDistinctMessages() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"invite cannot be redeemed"}"""))
        val replay = repo.preview("tok-1").exceptionOrNull()

        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"invite expired"}"""))
        val expired = repo.preview("tok-1").exceptionOrNull()

        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"invite cannot be redeemed"}"""))
        val linked = repo.redeem("tok-1").exceptionOrNull()

        assertEquals(InviteError.AlreadyUsed, replay)
        assertEquals(InviteError.Expired, expired)
        assertEquals(InviteError.AlreadyLinked, linked)
        val messages = listOf(replay, expired, linked).map { inviteMessageRes(it!!) }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun unknownForbiddenAndUnauthenticatedMapToTheirOwnErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(InviteError.NotFound, repo.preview("nope").exceptionOrNull())

        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(InviteError.NotMember, repo.create("g1", "p2").exceptionOrNull())

        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(InviteError.SignInRequired, repo.redeem("tok-1").exceptionOrNull())
    }

    @Test
    fun serverFaultKeepsTheGenericMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = repo.preview("tok-1").exceptionOrNull()!!

        assertNotEquals(InviteError.NotFound, error)
        assertEquals(ai.schism.split.R.string.invite_error_generic, inviteMessageRes(error))
    }
}
