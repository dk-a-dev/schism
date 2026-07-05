package ai.schism.split.core.net

import ai.schism.split.core.db.OutboxDao
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ConnectivityViewModel @Inject constructor(
    observer: ConnectivityObserver,
    outboxDao: OutboxDao,
) : ViewModel() {
    val isOnline: StateFlow<Boolean> =
        observer.isOnline().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Number of writes queued locally, waiting to sync. */
    val pendingSync: StateFlow<Int> =
        outboxDao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
