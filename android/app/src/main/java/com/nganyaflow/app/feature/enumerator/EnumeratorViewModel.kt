package com.nganyaflow.app.feature.enumerator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nganyaflow.app.core.network.ApiFailure
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EnumeratorActionState(val busy: Boolean = false, val error: String? = null, val errorCode: Int? = null)

class EnumeratorViewModel(val repository: NganyaRepository) : ViewModel() {
    private val mutable = MutableStateFlow(EnumeratorActionState())
    val state = mutable.asStateFlow()

    init { perform { repository.initialize() } }

    fun perform(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.value = EnumeratorActionState(busy = true)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = EnumeratorActionState(error = e.message ?: e.javaClass.simpleName, errorCode = (e as? ApiFailure)?.code) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
