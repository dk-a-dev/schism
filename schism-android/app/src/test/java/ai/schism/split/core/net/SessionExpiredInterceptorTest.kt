package ai.schism.split.core.net

import ai.schism.split.core.settings.SettingsRepository
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
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

/**
 * Covers the Part A cutover: a 401 on a request we authenticated means OUR session ended (clear the
 * token, tell the app to route to sign-in) — but a 401 on an anonymous request, or on the
 * login/register endpoints themselves (bad credentials), must NOT trigger any of that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionExpiredInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsRepository
    private lateinit var authEvents: AuthEvents
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
        authEvents = AuthEvents()
        client = OkHttpClient.Builder()
            .addInterceptor(SessionExpiredInterceptor(settings, authEvents))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request(path: String, withToken: Boolean): Request {
        val builder = Request.Builder().url(server.url(path))
        if (withToken) builder.header("Authorization", "Bearer some-raw-token")
        return builder.build()
    }

    /**
     * Subscribes to [AuthEvents.sessionExpired] using [Dispatchers.Unconfined], which runs the
     * collector body eagerly (no dispatch) until its first suspension point — i.e. by the time this
     * function returns, the collector is already registered with the (replay = 0) SharedFlow. This
     * avoids a race where an event emitted before a subscriber exists would otherwise be dropped.
     */
    private fun subscribeToSessionExpired(onEvent: () -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch {
            authEvents.sessionExpired.collect { onEvent() }
        }
    }

    @Test
    fun tokenBearing401ClearsTokenAndEmitsSessionExpired() = runBlocking {
        settings.setIdentity("u1", "some-raw-token")
        server.enqueue(MockResponse().setResponseCode(401))

        var emitted = false
        subscribeToSessionExpired { emitted = true }

        client.newCall(request("/v1/groups", withToken = true)).execute().close()

        assertTrue("sessionExpired should have been emitted", emitted)
        assertEquals("", settings.authToken.first())
    }

    @Test
    fun noAuthHeader401DoesNotClearOrEmit() = runBlocking {
        settings.setIdentity("u1", "some-raw-token")
        server.enqueue(MockResponse().setResponseCode(401))

        var emitted = false
        subscribeToSessionExpired { emitted = true }

        client.newCall(request("/v1/categories", withToken = false)).execute().close()

        assertFalse("no Authorization header on the request must not raise sessionExpired", emitted)
        assertEquals("some-raw-token", settings.authToken.first())
    }

    @Test
    fun authLoginEndpoint401DoesNotClearOrEmit() = runBlocking {
        settings.setIdentity("u1", "some-raw-token")
        server.enqueue(MockResponse().setResponseCode(401))

        var emitted = false
        subscribeToSessionExpired { emitted = true }

        // Bad credentials on /v1/auth/login are surfaced by the screen itself, not a global logout —
        // even though this request happens to carry a (stale) Authorization header.
        client.newCall(request("/v1/auth/login", withToken = true)).execute().close()

        assertFalse("a 401 on the login endpoint must not raise sessionExpired", emitted)
        assertEquals("some-raw-token", settings.authToken.first())
    }

    @Test
    fun authRegisterEndpoint401DoesNotClearOrEmit() = runBlocking {
        settings.setIdentity("u1", "some-raw-token")
        server.enqueue(MockResponse().setResponseCode(401))

        var emitted = false
        subscribeToSessionExpired { emitted = true }

        client.newCall(request("/v1/auth/register", withToken = true)).execute().close()

        assertFalse(emitted)
        assertEquals("some-raw-token", settings.authToken.first())
    }

    @Test
    fun nonTokenBearingSuccessLeavesTokenAlone() = runBlocking {
        settings.setIdentity("u1", "some-raw-token")
        server.enqueue(MockResponse().setResponseCode(200))

        var emitted = false
        subscribeToSessionExpired { emitted = true }

        client.newCall(request("/v1/groups", withToken = true)).execute().close()

        assertFalse("a 200 must never clear the token or emit", emitted)
        assertEquals("some-raw-token", settings.authToken.first())
    }
}
