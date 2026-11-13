package br.com.concursoprep.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import br.com.concursoprep.app.domain.model.AccountAuthUiState

/** Formulário nativo de autenticação Cogniva (e-mail + senha Argon2id); largura total preservada em fontScale = 2f. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountAuthForm(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isGuest: Boolean,
    authenticatedEmail: String?,
    isAdmin: Boolean,
    state: AccountAuthUiState,
    onAuthenticate: (email: String, password: String, isRegister: Boolean) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isRegister by rememberSaveable { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!isGuest) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Conta autenticada",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = authenticatedEmail ?: "Sessão ativa",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (isAdmin) {
                        Text(
                            text = "Perfil administrativo habilitado",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onLogout,
                enabled = !state.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("account_auth_logout")
            ) {
                Text("Sair da conta neste aparelho")
            }
        } else {
            Text("Conta Cogniva e sincronização na nuvem", style = MaterialTheme.typography.titleSmall)
            Text(
                if (isRegister) {
                    "Crie sua conta com e-mail e senha para proteger e sincronizar todo o progresso já registrado neste aparelho sem perder nada."
                } else {
                    "Entre com seu e-mail e senha para sincronizar seu histórico e sua escada de concursos entre aparelhos."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = !isRegister,
                    onClick = { isRegister = false },
                    enabled = !state.isSubmitting,
                    label = { Text("Entrar") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("account_auth_mode_login")
                )
                FilterChip(
                    selected = isRegister,
                    onClick = { isRegister = true },
                    enabled = !state.isSubmitting,
                    label = { Text("Criar conta") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("account_auth_mode_register")
                )
            }
            val normalizedEmail = email.trim()
            val canSubmit = normalizedEmail.contains("@") &&
                (if (isRegister) password.length >= 10 else password.isNotBlank()) &&
                !state.isSubmitting
            val submit: () -> Unit = {
                if (canSubmit) onAuthenticate(normalizedEmail, password, isRegister)
            }
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text("E-mail") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                singleLine = true,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth().testTag("account_auth_email")
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(if (isRegister) "Nova senha (mín. 10 caracteres)" else "Senha") },
                supportingText = {
                    Text(
                        if (isRegister) "Mínimo de 10 caracteres. Seu progresso atual será preservado na mesma conta."
                        else "Informe a senha cadastrada na sua conta Cogniva."
                    )
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Ocultar senha" else "Mostrar senha"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                singleLine = true,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth().testTag("account_auth_password")
            )
            Button(
                onClick = submit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_auth_submit")
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        state.isSubmitting -> "Autenticando…"
                        isRegister -> "Criar conta e vincular progresso"
                        else -> "Entrar na conta"
                    }
                )
            }
        }
        state.message?.let { message ->
            Text(
                message,
                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("account_auth_status")
            )
        }
    }
}
