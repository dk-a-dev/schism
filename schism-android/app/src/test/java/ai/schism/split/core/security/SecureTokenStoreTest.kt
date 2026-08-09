package ai.schism.split.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureTokenStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before fun clear() {
        context.getSharedPreferences("secure_auth", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `token is encrypted and round trips`() {
        val store = SecureTokenStore(context) { key }
        store.write("secret-token")

        assertEquals("secret-token", store.read())
        assertFalse(store.encryptedValueForTest().orEmpty().contains("secret-token"))
    }

    @Test fun `same token gets a unique IV and tamper clears safely`() {
        val store = SecureTokenStore(context) { key }
        store.write("secret-token")
        val first = store.encryptedValueForTest()
        store.write("secret-token")
        val second = store.encryptedValueForTest()
        assertNotEquals(first, second)

        context.getSharedPreferences("secure_auth", Context.MODE_PRIVATE)
            .edit().putString("token_v1", second.orEmpty().dropLast(2) + "AA").commit()
        assertEquals("", store.read())
        assertEquals(null, store.encryptedValueForTest())
    }
}
