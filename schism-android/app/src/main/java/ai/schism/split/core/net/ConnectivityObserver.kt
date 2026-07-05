package ai.schism.split.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits whether the device currently has a validated internet connection. Used to show an offline
 * banner and (later) to trigger sync of any queued writes when connectivity returns.
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Flow<Boolean> = callbackFlow {
        val cm = manager
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        fun current(): Boolean {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        trySend(current())
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
