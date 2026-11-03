package edu.itssg.compiler.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.itssg.compiler.ui.data.SavedSessionSummary
import edu.itssg.compiler.ui.data.SessionStore
import edu.itssg.compiler.ui.state.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryFilter(val displayName: String) {
    ALL("Todas"),
    WITH_ERRORS("Con errores"),
    WITHOUT_ERRORS("Sin errores")
}

enum class HistorySort(val displayName: String) {
    NEWEST_FIRST("Más recientes"),
    OLDEST_FIRST("Más antiguas")
}

/**
 * @property allSessions Todas las sesiones guardadas.
 * @property visibleSessions Las que pasan la búsqueda y el filtro, en el orden elegido.
 * @property pendingDeletion Sesión cuyo borrado espera confirmación (diálogo).
 */
data class HistoryUiState(
    val isLoading: Boolean = true,
    val allSessions: List<SavedSessionSummary> = emptyList(),
    val visibleSessions: List<SavedSessionSummary> = emptyList(),
    val query: String = "",
    val filter: HistoryFilter = HistoryFilter.ALL,
    val sort: HistorySort = HistorySort.NEWEST_FIRST,
    val pendingDeletion: SavedSessionSummary? = null,
    val loadError: String? = null
) {
    val isEmpty: Boolean get() = !isLoading && allSessions.isEmpty()
}

/**
 * ViewModel de la pantalla Historial. Se recarga solo cada vez que se guarda o elimina una sesión
 * (observa [SessionStore.revision]). Reabrir una sesión lo hace el espacio de trabajo con
 * `CompilerViewModel.openSource(title, sourceCode, alreadySaved = true)`.
 */
class HistoryViewModel(
    private val sessionStore: SessionStore
) : ViewModel() {

    private val state = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = state.asStateFlow()

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    private var deleteJob: Job? = null

    init {
        viewModelScope.launch {
            sessionStore.revision.collectLatest { reload() }
        }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun onQueryChange(query: String) = state.update { it.copy(query = query.take(MAX_QUERY_LENGTH)).refiltered() }

    fun onFilterChange(filter: HistoryFilter) = state.update { it.copy(filter = filter).refiltered() }

    fun onSortChange(sort: HistorySort) = state.update { it.copy(sort = sort).refiltered() }

    fun requestDeletion(session: SavedSessionSummary) = state.update { it.copy(pendingDeletion = session) }

    fun cancelDeletion() = state.update { it.copy(pendingDeletion = null) }

    fun confirmDeletion() {
        val target = state.value.pendingDeletion ?: return
        if (deleteJob?.isActive == true) return
        state.update { it.copy(pendingDeletion = null) }
        deleteJob = viewModelScope.launch {
            try {
                if (sessionStore.delete(target.id)) {
                    messageChannel.trySend(UiMessage("Se eliminó “${target.title}”."))
                } else {
                    messageChannel.trySend(UiMessage("La sesión ya no existía."))
                    reload()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(
                    UiMessage("No se pudo eliminar la sesión: ${e.message ?: e::class.simpleName}", isError = true)
                )
            }
        }
    }

    private suspend fun reload() {
        state.update { it.copy(isLoading = true, loadError = null) }
        try {
            val sessions = sessionStore.loadSummaries()
            state.update { it.copy(isLoading = false, allSessions = sessions).refiltered() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update {
                it.copy(isLoading = false, loadError = "No se pudo leer el historial: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun HistoryUiState.refiltered(): HistoryUiState {
        val needle = query.trim()
        val filtered = allSessions.filter { session ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.WITH_ERRORS -> session.hasErrors
                HistoryFilter.WITHOUT_ERRORS -> !session.hasErrors
            }
            matchesFilter && (needle.isEmpty() ||
                    session.title.contains(needle, ignoreCase = true) ||
                    session.sourceCode.contains(needle, ignoreCase = true))
        }
        val sorted = when (sort) {
            HistorySort.NEWEST_FIRST -> filtered.sortedWith(compareByDescending<SavedSessionSummary> { it.createdAtMillis }.thenByDescending { it.id })
            HistorySort.OLDEST_FIRST -> filtered.sortedWith(compareBy<SavedSessionSummary> { it.createdAtMillis }.thenBy { it.id })
        }
        return copy(visibleSessions = sorted)
    }

    override fun onCleared() {
        messageChannel.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
    }
}
