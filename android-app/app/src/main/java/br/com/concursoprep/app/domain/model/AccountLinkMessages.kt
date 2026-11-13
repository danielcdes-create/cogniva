package br.com.concursoprep.app.domain.model

object AccountLinkMessages {
    const val INVALID_CREDENTIALS = "E-mail ou senha incorretos. Confira seus dados e tente novamente."
    const val EXPIRED = "Sessão inválida ou expirada. Entre novamente com seu e-mail e senha."
    const val EMAIL_IN_USE = "Este e-mail já está cadastrado. Use a aba Entrar para acessar sua conta."
    const val RATE_LIMITED = "Muitas tentativas em sequência. Aguarde alguns instantes antes de tentar novamente."
    const val OFFLINE = "Sem conexão com o servidor. Seus estudos locais continuam preservados."
    const val GENERIC = "Não foi possível autenticar agora. Confira os dados e tente novamente."

    fun fromHttp(status: Int, body: String): String = when {
        status == 401 && (body.contains("incorret", ignoreCase = true) || body.contains("credencia", ignoreCase = true)) -> INVALID_CREDENTIALS
        status == 401 -> EXPIRED
        status == 409 -> EMAIL_IN_USE
        status == 429 -> RATE_LIMITED
        else -> GENERIC
    }
}
