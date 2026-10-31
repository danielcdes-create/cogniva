package br.com.concursoprep.app

import android.app.Application
import androidx.room.Room
import br.com.concursoprep.app.data.local.AppDatabase
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.repository.ConcursoRepository
import br.com.concursoprep.app.data.sync.OfflineSyncWorker
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class ConcursoApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: ConcursoRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Inicializa o Room DB Offline-First
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "concurso_prep.db"
        )
            .fallbackToDestructiveMigration()
            .build()

        // 2. Configura OkHttp com timeouts generosos para LLMs (Gemini / OpenAI)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        // 3. Serializador Kotlinx com tolerância a novos campos do MongoDB
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        // 4. Retrofit apontando para o container api-rust-core no Droplet DigitalOcean (via BuildConfig.API_BASE_URL)
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
            json = json
        )

        // 6. Agenda sincronização periódica de fila offline
        OfflineSyncWorker.schedulePeriodicSync(this)
    }

    companion object {
        lateinit var instance: ConcursoApp
            private set
    }
}
