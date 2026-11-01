package br.com.concursoprep.app.data.local

import androidx.room.*

@Entity(tableName = "learning_records")
data class LearningRecordEntity(@PrimaryKey val questionId: String, val revision: Long, val payloadJson: String)
@Entity(tableName = "pending_reports")
data class PendingReportEntity(@PrimaryKey val eventId: String, val ownerUserId: Long, val questionId: String, val category: String)
@Entity(tableName = "quiz_sessions")
data class QuizSessionEntity(@PrimaryKey val sessionId: String, val ownerUserId: Long, val payloadJson: String, val revision: Long, val active: Boolean = true, val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "local_metadata")
data class LocalMetadataEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface DurableStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLearning(records: List<LearningRecordEntity>)
    @Query("SELECT * FROM learning_records") suspend fun learning(): List<LearningRecordEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueueReport(report: PendingReportEntity)
    @Query("SELECT * FROM pending_reports WHERE ownerUserId=:owner") suspend fun reports(owner: Long): List<PendingReportEntity>
    @Query("DELETE FROM pending_reports WHERE eventId=:id AND ownerUserId=:owner") suspend fun acknowledgeReport(id: String, owner: Long)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun createSession(session: QuizSessionEntity)
    @Query("UPDATE quiz_sessions SET payloadJson=:payload, revision=:revision, updatedAt=:at WHERE sessionId=:id AND ownerUserId=:owner AND active=1 AND revision<:revision")
    suspend fun saveSession(id: String, owner: Long, payload: String, revision: Long, at: Long)
    @Query("UPDATE quiz_sessions SET active=0 WHERE sessionId=:id AND ownerUserId=:owner") suspend fun completeSession(id: String, owner: Long)
    @Query("SELECT * FROM quiz_sessions WHERE active=1 AND ownerUserId=:owner ORDER BY updatedAt DESC LIMIT 1") suspend fun pausedSession(owner: Long): QuizSessionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMetadata(value: LocalMetadataEntity)
    @Query("SELECT value FROM local_metadata WHERE `key`=:key") suspend fun metadata(key: String): String?
    @Query("SELECT value FROM local_metadata WHERE `key`=:key") fun observeMetadata(key: String): kotlinx.coroutines.flow.Flow<String?>
}
