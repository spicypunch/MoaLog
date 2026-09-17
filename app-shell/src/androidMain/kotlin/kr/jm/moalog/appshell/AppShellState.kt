package kr.jm.moalog.appshell

import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AppShellUiState {
    data object Loading : AppShellUiState
    data object NeedsSetup : AppShellUiState
    data class Ready(val setup: LedgerSetup) : AppShellUiState
    data object LoadFailed : AppShellUiState
}

class AppShellStateHolder(
    private val repository: SetupRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<AppShellUiState>(AppShellUiState.Loading)
    val state: StateFlow<AppShellUiState> = mutableState.asStateFlow()
    private var observeJob: Job? = null

    init { load() }

    fun retry() = load()

    private fun load() {
        observeJob?.cancel()
        mutableState.value = AppShellUiState.Loading
        observeJob = scope.launch {
            try {
                repository.observeSetup().collect { setup ->
                    mutableState.value = setup?.let(AppShellUiState::Ready) ?: AppShellUiState.NeedsSetup
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = AppShellUiState.LoadFailed
            }
        }
    }
}
