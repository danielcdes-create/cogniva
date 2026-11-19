package br.com.concursoprep.app

import android.app.Application
import androidx.room.Room
import androidx.work.WorkManager
import br.com.concursoprep.app.data.AuthStore
import br.com.concursoprep.app.data.local.AccountDatabaseOpener
import br.com.concursoprep.app.data.local.AccountDatabaseSession
import br.com.concursoprep.app.data.local.AppDatabase
import br.com.concursoprep.app.data.local.ExamMigrations
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.sync.OfflineSyncWorker
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class ConcursoApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: ConcursoRepository
        private set

    lateinit var authStore: AuthStore
        private set

    private var accountDatabases: AccountDatabaseSession<AppDatabase>? = null
    private var activeHttpClient: OkHttpClient? = null

    suspend fun selectAccount(userId: Long) {
        if (::repository.isInitialized) {
            repository.awaitIdleForClose()
        }
        authStore.select(userId)
        openAccount(userId)
    }

    /**
     * Fecha o banco Room da conta excluída, apaga seus arquivos locais SQLite/WAL/SHM e backups,
     * e abre o novo banco limpo de convidado (LGPD Art. 18).
     */
    suspend fun wipeLocalAccountDatabaseAndOpenGuest(deletedUserId: Long, nextGuestUserId: Long) {
        runCatching { WorkManager.getInstance(this).cancelUniqueWork("CognivaImmediateSync") }
        if (::repository.isInitialized) {
            repository.awaitIdleForClose()
        }
        val prefs = getSharedPreferences("cogniva_prefs", MODE_PRIVATE)
        val legacyOwner = prefs.getLong("legacy_database_owner", 0L)
        val deletedDbName = AccountDatabaseOpener.fileName(legacyOwner, deletedUserId)

        authStore.select(nextGuestUserId)
        openAccount(nextGuestUserId)

        if (deletedUserId != nextGuestUserId) {
            runCatching { deleteDatabase(deletedDbName) }
            runCatching { deleteDatabase("cogniva-$deletedUserId.db") }
            if (legacyOwner != 0L && legacyOwner == deletedUserId) {
                runCatching { deleteDatabase("concurso_prep.db") }
                prefs.edit().remove("legacy_database_owner").apply()
            }
        }
        runCatching {
            File(cacheDir, "preserved").listFiles()?.forEach { it.delete() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        authStore = AuthStore(this)
        val prefs = getSharedPreferences("cogniva_prefs", MODE_PRIVATE)
        val legacyOwner = AccountDatabaseOpener.legacyOwnerToStore(
            databaseExists = getDatabasePath("concurso_prep.db").exists(),
            alreadyStored = prefs.contains("legacy_database_owner"),
            storedUserId = if (prefs.contains("user_id")) prefs.getLong("user_id", 0L) else null,
        )
        if (legacyOwner != null) {
            check(prefs.edit().putLong("legacy_database_owner", legacyOwner).commit())
        }
        authStore.select(authStore.selectedUserId)
        openAccount(authStore.selectedUserId)
        OfflineSyncWorker.schedulePeriodicSync(this)
    }

    private fun openAccount(ownerUserId: Long) {
        val prefs = getSharedPreferences("cogniva_prefs", MODE_PRIVATE)
        val databaseName = AccountDatabaseOpener.fileName(prefs.getLong("legacy_database_owner", 0L), ownerUserId)
        val session = accountDatabases ?: AccountDatabaseSession(
            openNamed = { name ->
                Room.databaseBuilder(applicationContext, AppDatabase::class.java, name)
                    .addMigrations(
                        ExamMigrations.MIGRATION_1_2,
                        ExamMigrations.MIGRATION_2_3,
                        ExamMigrations.MIGRATION_3_4,
                        ExamMigrations.MIGRATION_4_5,
                        ExamMigrations.MIGRATION_5_6,
                        ExamMigrations.MIGRATION_6_7
                    )
                    .build()
            },
            close = { it.close() },
        ).also { accountDatabases = it }
        database = session.open(databaseName)

        activeHttpClient?.let { previous ->
            previous.dispatcher.executorService.shutdown()
            previous.connectionPool.evictAll()
        }

        // 2. Configura OkHttp com timeout estendido (65s para xAI tutor/grading) e interceptor de log restrito a DEBUG
        val okHttpBuilder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = authStore.token(ownerUserId)
                val request = chain.request().newBuilder().apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
            }
            okHttpBuilder.addInterceptor(loggingInterceptor)
        }

        val okHttpClient = okHttpBuilder.build()
        activeHttpClient = okHttpClient

        // 3. Serializador Kotlinx com tolerância a novos campos do MongoDB
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        // 4. Retrofit apontando para o container api-rust-core (via BuildConfig.API_BASE_URL)
        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        val apiService = retrofit.create(ConcursoApiService::class.java)

        // 5. Injeta Repositório
        repository = ConcursoRepository(
            api = apiService,
            dao = database.concursoDao(),
            examDao = database.examDao(),
            json = json,
            durable = database.durableDao(),
            database = database,
            ownerUserId = ownerUserId,
            auth = authStore
        )
    }

    companion object {
        lateinit var instance: ConcursoApp
            private set
    }
}
