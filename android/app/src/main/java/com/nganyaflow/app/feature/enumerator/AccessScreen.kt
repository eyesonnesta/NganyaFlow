package com.nganyaflow.app.feature.enumerator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nganyaflow.app.R
import com.nganyaflow.app.core.designsystem.*
import com.nganyaflow.app.data.NganyaRepository
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

@Composable
internal fun enumeratorModel(repository: NganyaRepository, key: String): EnumeratorViewModel = viewModel(key = key, factory = remember(repository) {
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = EnumeratorViewModel(repository) as T
    }
})

@Composable
internal fun clock(repository: NganyaRepository): Instant {
    var now by remember { mutableStateOf(repository.now()) }
    LaunchedEffect(repository) { while (true) { now = repository.now(); delay(1000) } }
    return now
}

@Composable
fun AccessScreen(
    repository: NganyaRepository,
    onEnumerator: () -> Unit,
    onCrew: () -> Unit,
    onTitleChange: (String) -> Unit = {},
) {
    val model = enumeratorModel(repository, "access")
    val action by model.state.collectAsStateWithLifecycle()
    val auth by repository.auth.collectAsStateWithLifecycle()
    val now = clock(repository)
    val valid = auth?.takeIf { runCatching { Instant.parse(it.expiresAt) > now }.getOrDefault(false) }
    var code by remember { mutableStateOf("") }
    val title = stringResource(R.string.enum_access_title)
    LaunchedEffect(title) { onTitleChange(title) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (valid != null) {
            val role = stringResource(if (valid.role == "enumerator") R.string.enum_enumerator_role else R.string.enum_crew_role)
            NoticeCard(role, stringResource(R.string.enum_role_detail, elapsedLabel(now, Instant.parse(valid.expiresAt))))
            PrimaryButton(stringResource(if (valid.role == "enumerator") R.string.enum_continue_enum else R.string.enum_continue_crew), {
                if (valid.role == "enumerator") onEnumerator() else onCrew()
            })
            OutlinedButton({ model.perform { repository.signOut() } }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !action.busy) { Text(stringResource(R.string.enum_sign_out)) }
        } else {
            Text(stringResource(R.string.enum_access_detail), style = MaterialTheme.typography.bodyLarge)
            if (auth != null) NoticeCard(stringResource(R.string.enum_auth_required), stringResource(R.string.enum_auth_detail), kind = NoticeKind.Warning)
            OutlinedTextField(code, { code = it.uppercase(Locale.ROOT).filter { character -> character.isLetterOrDigit() || character == '-' }.take(40) },
                Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.enum_access_code)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            PrimaryButton(stringResource(if (action.busy) R.string.enum_working else R.string.enum_sign_in), {
                val supplied = code
                model.perform { repository.redeem(supplied); code = "" }
            }, enabled = !action.busy && code.replace("-", "").length >= 4)
        }
        ActionError(action)
        NoticeCard(stringResource(R.string.rider_privacy), stringResource(R.string.privacy_summary))
    }
}

@Composable
internal fun ActionError(action: EnumeratorActionState) {
    if (action.error != null) {
        val message = when (action.errorCode) {
            401 -> stringResource(R.string.enum_auth_detail)
            403 -> stringResource(R.string.enum_wrong_role)
            429 -> stringResource(R.string.enum_too_many_attempts)
            else -> action.error
        }
        NoticeCard(stringResource(R.string.enum_operation_failed), message, kind = NoticeKind.Error)
    }
}
