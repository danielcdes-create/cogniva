package br.com.concursoprep.app.data.local

object AccountDatabaseOpener {
    fun legacyOwnerToStore(databaseExists: Boolean, alreadyStored: Boolean, storedUserId: Long?): Long? {
        if (!databaseExists || alreadyStored) return null
        return storedUserId
    }

    fun fileName(legacyOwner: Long, accountUserId: Long): String =
        if (legacyOwner != 0L && legacyOwner == accountUserId) "concurso_prep.db" else "cogniva-$accountUserId.db"
}

class AccountDatabaseSession<T : Any>(
    private val openNamed: (String) -> T,
    private val close: (T) -> Unit,
) {
    private var current: T? = null

    fun open(name: String): T {
        current?.let(close)
        val next = openNamed(name)
        current = next
        return next
    }
}
