package ai.schism.split.core.net

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
) : ViewModel() {
    val isOnline: StateFlow<Boolean> =
        observer.isOnline().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
}
