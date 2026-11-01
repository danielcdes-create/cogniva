package br.com.concursoprep.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Entidade Room para armazenamento Offline-First de questões (estudo no transporte sem internet)
 */
@Entity(tableName = "cached_questions")
data class CachedQuestionEntity(
    @PrimaryKey val id: String,
    val question: String,
    val questionType: String,
    val answersJson: String,
    val correctIndicesJson: String,
    val disciplina: String,
    val source: String,
    val examYear: Int?,
    val level: String,
    val explanation: String?,
    val inErrorNotebook: Boolean,
    val isMastered: Boolean,
    val userNote: String? = null,
    val canonicalSubjectId: String? = null,
    val canonicalTopicId: String? = null,
    val payloadJson: String? = null
)

/**
 * Fila local de respostas registradas offline para sincronização automática via WorkManager
 * com o endpoint `POST /api/v1/quiz/submit-batch` do backend Rust.
 */
@Entity(tableName = "pending_attempts")
data class PendingAttemptEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val questionId: String,
    val chosenIndex: Int,
    val chosenText: String,
    val isCorrect: Boolean,
    val timeSpentSeconds: Int,
    val confidenceLevel: Int,
    val sessionUuid: String,
    val quizMode: String,
    val examBlock: String?,
    val contestId: String?,
    val userNote: String?,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val canonicalSubjectId: String? = null,
    val canonicalTopicId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val ownerUserId: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "''") val attemptId: String = java.util.UUID.randomUUID().toString(),
    @androidx.room.ColumnInfo(defaultValue = "''") val questionRevision: String = "",
    val syncError: String? = null
)

@Dao
interface ConcursoLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<CachedQuestionEntity>)

    @Query("SELECT * FROM cached_questions WHERE id = :id LIMIT 1")
    suspend fun findQuestionById(id: String): CachedQuestionEntity?

    @Query("SELECT * FROM cached_questions WHERE (:disciplina IS NULL OR disciplina = :disciplina) ORDER BY RANDOM() LIMIT :limit")
    suspend fun getCachedQuestions(disciplina: String?, limit: Int): List<CachedQuestionEntity>

    @Query("SELECT * FROM cached_questions WHERE inErrorNotebook = 1")
    fun observeErrorNotebook(): Flow<List<CachedQuestionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueuePendingAttempt(attempt: PendingAttemptEntity)

    @Query("SELECT * FROM pending_attempts WHERE sessionUuid = :sessionUuid AND questionId = :questionId LIMIT 1")
    suspend fun findPendingAttemptInSession(sessionUuid: String, questionId: String): PendingAttemptEntity?

    @Query("SELECT * FROM pending_attempts ORDER BY createdAtEpochMs ASC")
    suspend fun getAllPendingAttempts(): List<PendingAttemptEntity>

    @Query("SELECT COUNT(*) FROM pending_attempts")
    suspend fun countPendingAttempts(): Int
    @Query("SELECT COUNT(*) FROM pending_attempts")
    fun observePendingAttemptCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cached_questions")
    suspend fun countCachedQuestions(): Int

    @Query("SELECT * FROM cached_questions WHERE inErrorNotebook = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun getErrorNotebookQuestions(limit: Int): List<CachedQuestionEntity>

    @Query("UPDATE cached_questions SET inErrorNotebook = :inErrorNotebook WHERE id = :questionId")
    suspend fun updateQuestionErrorNotebookFlag(questionId: String, inErrorNotebook: Boolean)

    @Query("DELETE FROM cached_questions")
    suspend fun clearCachedQuestions()

    @Query("DELETE FROM pending_attempts WHERE localId IN (:ids)")
    suspend fun deleteSyncedAttempts(ids: List<Long>)
    @Query("UPDATE pending_attempts SET ownerUserId=:owner WHERE ownerUserId=0")
    suspend fun bindLegacyOwner(owner: Long)
    @Query("DELETE FROM pending_attempts WHERE attemptId=:eventId AND ownerUserId=:owner")
    suspend fun acknowledgeAttempt(eventId: String, owner: Long)
    @Query("UPDATE pending_attempts SET syncError=:error WHERE attemptId=:eventId AND ownerUserId=:owner")
    suspend fun markAttemptError(eventId: String, owner: Long, error: String?)
    @Query("UPDATE cached_questions SET inErrorNotebook=:error, isMastered=:mastered WHERE id=:id")
    suspend fun updateLearningFlags(id: String, error: Boolean, mastered: Boolean)

}

@Database(
    entities = [
        CachedQuestionEntity::class,
        PendingAttemptEntity::class,
        CareerTrunkEntity::class,
        UserExamEntity::class,
        ExamSubjectEntity::class,
        ExamTopicEntity::class,
        LearningRecordEntity::class, PendingReportEntity::class, QuizSessionEntity::class, LocalMetadataEntity::class,
        CanonicalMasteryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun concursoDao(): ConcursoLocalDao
    abstract fun examDao(): ExamDao
    abstract fun durableDao(): DurableStateDao
}
