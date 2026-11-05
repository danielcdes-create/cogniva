package br.com.concursoprep.app.domain.model

object AccountLinkMessages {
    const val EXPIRED = "Código inválido ou expirado. Solicite um novo código e tente novamente."
    const val REUSED = "Este código já foi usado por outra instalação. Solicite um novo código."
    const val OFFLINE = "Sem conexão com o servidor. O vínculo não foi confirmado; tente novamente."
    const val GENERIC = "Não foi possível confirmar o vínculo. Confira os dados e tente novamente."

    fun fromHttp(status: Int, body: String): String = when {
        status == 401 && body.contains("já utilizado") -> REUSED
        status == 401 -> EXPIRED
        else -> GENERIC
    }
}
