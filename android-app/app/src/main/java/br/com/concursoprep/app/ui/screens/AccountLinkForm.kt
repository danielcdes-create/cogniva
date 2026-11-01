package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.AccountLinkUiState

/** The production account form; fields remain full-width at large font sizes. */
@Composable
fun AccountLinkForm(
    userId: String,
    onUserIdChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    state: AccountLinkUiState,
    onConfirm: (Long, String?, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCode by remember { mutableStateOf(false) }
    val parsedId = userId.toLongOrNull()?.takeIf { it > 0 }
    val canConfirm = parsedId != null && code.isNotBlank() && !state.isSubmitting
    val confirm: () -> Unit = {
        if (canConfirm) onConfirm(parsedId!!, username.trim().removePrefix("@").ifBlank { null }, code.trim())
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recuperar conta e histórico", style = MaterialTheme.typography.titleSmall)
        Text(
            "Informe o ID da conta e um código de acesso válido. O vínculo só é confirmado pelo botão abaixo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = userId,
            onValueChange = { onUserIdChange(it.filter(Char::isDigit)) },
            label = { Text("ID numérico do Telegram") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().testTag("account_link_id")
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("@username (opcional)") },
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().testTag("account_link_username")
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("Código de vínculo") },
            supportingText = { Text("O código é temporário. Cole o conteúdo completo recebido.") },
            visualTransformation = if (showCode) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showCode = !showCode }) {
                    Icon(
                        if (showCode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showCode) "Ocultar código" else "Mostrar código"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().testTag("account_link_code")
        )
        Button(
            onClick = confirm,
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_link_confirm")
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.isSubmitting) "Confirmando…" else "Confirmar vínculo")
        }
        state.message?.let { message ->
            Text(
                message,
                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("account_link_status")
            )
        }
    }
}
