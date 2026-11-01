package br.com.concursoprep.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.concursoprep.app.domain.exam.StoredExam
import br.com.concursoprep.app.domain.exam.StoredSubject
import br.com.concursoprep.app.domain.exam.StoredTopic
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "career_trunks")
data class CareerTrunkEntity(
    @PrimaryKey val slug: String,
    val name: String,
    val iconKey: String,
    val isFocusedTrunk: Boolean = false
)

@Entity(
    tableName = "user_exams",
    indices = [Index(value = ["trunkSlug"]), Index(value = ["isActiveInLadder", "ladderPriority"])]
)
data class UserExamEntity(
    @PrimaryKey val examId: String,
    val title: String,
    val shortName: String,
    val institution: String,
    val role: String,
    val trunkSlug: String,
    val lifecycleStatus: String,
    val examBoardsCsv: String,
    val dateType: String,
    val exactDateIso: String?,
    val estimatedWindowLabel: String?,
    val estimatedSortDateIso: String,
    val historicalCutoffScore: Float,
    val isActiveInLadder: Boolean,
    val ladderPriority: Int,
    val confirmedTopicsCount: Int = 0,
    val newTopicsCount: Int = 0,
    val archivedTopicsCount: Int = 0,
    val syncStatus: String = "SYNCED",
    @androidx.room.ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val wrongAnswerPenalty: Double = 0.0,
    val syncError: String? = null
)

@Entity(
    tableName = "exam_subjects",
    primaryKeys = ["examId", "localSubjectId"],
    indices = [Index(value = ["canonicalSubjectId"])]
)
data class ExamSubjectEntity(
    val examId: String,
    val localSubjectId: String,
    val canonicalSubjectId: String,
    val displayName: String,
    val category: String,
    val weight: Float,
    val questionCount: Int?,
    val sortOrder: Int,
    val relevanceTier: String
)

@Entity(
    tableName = "exam_topics",
    primaryKeys = ["examId", "localTopicId"],
    indices = [Index(value = ["canonicalTopicId"]), Index(value = ["examId", "localSubjectId"])]
)
data class ExamTopicEntity(
    val examId: String,
    val localSubjectId: String,
    val localTopicId: String,
    val canonicalTopicId: String,
    val canonicalSubjectId: String,
    val displayTitle: String,
    val diffStatus: String
)

@Entity(tableName = "canonical_mastery")
data class CanonicalMasteryEntity(
    @PrimaryKey val canonicalTopicId: String,
    val canonicalSubjectId: String,
    val mastery: Double,
    val practiced: Boolean
)

@Dao
interface ExamDao {
    @Query("SELECT * FROM user_exams ORDER BY ladderPriority ASC, shortName ASC")
    fun observeAllExams(): Flow<List<UserExamEntity>>

    @Query("SELECT * FROM user_exams ORDER BY ladderPriority ASC, shortName ASC")
    suspend fun listExams(): List<UserExamEntity>

    @Query("SELECT * FROM user_exams WHERE syncStatus != 'SYNCED'")
    suspend fun listPendingExams(): List<UserExamEntity>

    @Query("SELECT COUNT(*) FROM user_exams")
    suspend fun countExams(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExam(exam: UserExamEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubjects(rows: List<ExamSubjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopics(rows: List<ExamTopicEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrunks(rows: List<CareerTrunkEntity>)

    @Query("SELECT * FROM exam_subjects WHERE examId = :examId ORDER BY sortOrder ASC")
    suspend fun subjectsFor(examId: String): List<ExamSubjectEntity>

    @Query("SELECT * FROM exam_topics WHERE examId = :examId")
    suspend fun topicsFor(examId: String): List<ExamTopicEntity>

    @Query("SELECT * FROM exam_topics WHERE canonicalTopicId = :canonicalTopicId")
    suspend fun topicsByCanonical(canonicalTopicId: String): List<ExamTopicEntity>

    @Query("SELECT * FROM career_trunks")
    suspend fun listTrunks(): List<CareerTrunkEntity>

    @Query("UPDATE career_trunks SET isFocusedTrunk = :focused WHERE slug = :slug")
    suspend fun setTrunkFocus(slug: String, focused: Boolean)

    @Query("UPDATE user_exams SET syncStatus = 'SYNCED' WHERE examId = :examId")
    suspend fun markSynced(examId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMastery(row: CanonicalMasteryEntity)

    @Query("SELECT * FROM canonical_mastery")
    suspend fun listMastery(): List<CanonicalMasteryEntity>
    @Query("DELETE FROM exam_subjects WHERE examId=:id") suspend fun clearSubjects(id: String)
    @Query("DELETE FROM exam_topics WHERE examId=:id") suspend fun clearTopics(id: String)
    @Query("UPDATE user_exams SET isActiveInLadder=:active WHERE examId=:id") suspend fun setActive(id: String, active: Boolean)
    @Query("UPDATE user_exams SET syncError=:error WHERE examId=:id") suspend fun setSyncError(id: String, error: String?)

}

object ExamLocalMapping {
    fun toEntity(exam: StoredExam): UserExamEntity = UserExamEntity(
        examId = exam.examId,
        title = exam.title,
        shortName = exam.shortName,
        institution = exam.institution,
        role = exam.role,
        trunkSlug = exam.trunkSlug,
        lifecycleStatus = exam.lifecycleStatus,
        examBoardsCsv = exam.examBoards.joinToString(","),
        dateType = exam.dateType,
        exactDateIso = exam.exactDateIso,
        estimatedWindowLabel = exam.estimatedWindowLabel,
        estimatedSortDateIso = exam.estimatedSortDateIso,
        historicalCutoffScore = exam.historicalCutoffScore.toFloat(),
        isActiveInLadder = exam.isActiveInLadder,
        ladderPriority = exam.ladderPriority,
        confirmedTopicsCount = exam.confirmedTopicsCount,
        newTopicsCount = exam.newTopicsCount,
        archivedTopicsCount = exam.archivedTopicsCount,
        syncStatus = exam.syncStatus,
        revision = exam.revision,
        wrongAnswerPenalty = exam.wrongAnswerPenalty,
        syncError = exam.syncError
    )

    fun subjectsOf(exam: StoredExam): List<ExamSubjectEntity> = exam.subjects.mapIndexed { index, subject ->
        ExamSubjectEntity(
            examId = exam.examId,
            localSubjectId = subject.localSubjectId,
            canonicalSubjectId = subject.canonicalSubjectId,
            displayName = subject.displayName,
            category = subject.category,
            weight = subject.weight.toFloat(),
            questionCount = subject.questionCount,
            sortOrder = index,
            relevanceTier = subject.relevanceTier
        )
    }

    fun topicsOf(exam: StoredExam): List<ExamTopicEntity> = exam.subjects.flatMap { subject ->
        subject.topics.map { topic ->
            ExamTopicEntity(
                examId = exam.examId,
                localSubjectId = subject.localSubjectId,
                localTopicId = topic.localTopicId,
                canonicalTopicId = topic.canonicalTopicId,
                canonicalSubjectId = topic.canonicalSubjectId,
                displayTitle = topic.displayTitle,
                diffStatus = topic.diffStatus
            )
        }
    }

    fun assemble(
        exam: UserExamEntity,
        subjects: List<ExamSubjectEntity>,
        topics: List<ExamTopicEntity>
    ): StoredExam {
        val topicsBySubject = topics.groupBy { it.localSubjectId }
        return StoredExam(
            examId = exam.examId,
            title = exam.title,
            shortName = exam.shortName,
            institution = exam.institution,
            role = exam.role,
            trunkSlug = exam.trunkSlug,
            lifecycleStatus = exam.lifecycleStatus,
            examBoards = exam.examBoardsCsv.split(",").filter { it.isNotBlank() },
            dateType = exam.dateType,
            exactDateIso = exam.exactDateIso,
            estimatedWindowLabel = exam.estimatedWindowLabel,
            estimatedSortDateIso = exam.estimatedSortDateIso,
            historicalCutoffScore = exam.historicalCutoffScore.toDouble(),
            isActiveInLadder = exam.isActiveInLadder,
            ladderPriority = exam.ladderPriority,
            confirmedTopicsCount = exam.confirmedTopicsCount,
            newTopicsCount = exam.newTopicsCount,
            archivedTopicsCount = exam.archivedTopicsCount,
            syncStatus = exam.syncStatus,
            revision = exam.revision,
            wrongAnswerPenalty = exam.wrongAnswerPenalty,
            syncError = exam.syncError,
            subjects = subjects.map { subject ->
                StoredSubject(
                    localSubjectId = subject.localSubjectId,
                    canonicalSubjectId = subject.canonicalSubjectId,
                    displayName = subject.displayName,
                    category = subject.category,
                    weight = subject.weight.toDouble(),
                    questionCount = subject.questionCount,
                    relevanceTier = subject.relevanceTier,
                    topics = topicsBySubject[subject.localSubjectId].orEmpty().map { topic ->
                        StoredTopic(
                            localTopicId = topic.localTopicId,
                            canonicalTopicId = topic.canonicalTopicId,
                            canonicalSubjectId = topic.canonicalSubjectId,
                            displayTitle = topic.displayTitle,
                            diffStatus = topic.diffStatus
                        )
                    }
                )
            }
        )
    }
}

object ExamMigrations {
    val MIGRATION_2_3 = object : Migration(2,3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cached_questions ADD COLUMN payloadJson TEXT")
            db.execSQL("ALTER TABLE pending_attempts ADD COLUMN ownerUserId INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pending_attempts ADD COLUMN attemptId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pending_attempts ADD COLUMN questionRevision TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pending_attempts ADD COLUMN syncError TEXT")
            db.execSQL("UPDATE pending_attempts SET attemptId=lower(hex(randomblob(4)))||'-'||lower(hex(randomblob(2)))||'-4'||substr(lower(hex(randomblob(2))),2)||'-a'||substr(lower(hex(randomblob(2))),2)||'-'||lower(hex(randomblob(6))), syncError='Registro anterior ao protocolo 2. Revisar recebimento no servidor antes de reenviar.'")
            db.execSQL("ALTER TABLE user_exams ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE user_exams ADD COLUMN syncError TEXT")
            db.execSQL("UPDATE user_exams SET syncStatus='REVIEW_REQUIRED', syncError='Edição anterior ao protocolo 2: confira e salve para enviar com identidade autenticada.' WHERE syncStatus!='SYNCED'")
            db.execSQL("ALTER TABLE user_exams ADD COLUMN wrongAnswerPenalty REAL NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE learning_records (questionId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, payloadJson TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pending_reports (eventId TEXT NOT NULL PRIMARY KEY, ownerUserId INTEGER NOT NULL, questionId TEXT NOT NULL, category TEXT NOT NULL)")
            db.execSQL("CREATE TABLE quiz_sessions (sessionId TEXT NOT NULL PRIMARY KEY, ownerUserId INTEGER NOT NULL, payloadJson TEXT NOT NULL, revision INTEGER NOT NULL, active INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE local_metadata (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
        }
    }

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_attempts ADD COLUMN canonicalSubjectId TEXT")
            db.execSQL("ALTER TABLE pending_attempts ADD COLUMN canonicalTopicId TEXT")
            db.execSQL("ALTER TABLE cached_questions ADD COLUMN canonicalSubjectId TEXT")
            db.execSQL("ALTER TABLE cached_questions ADD COLUMN canonicalTopicId TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS career_trunks (
                    slug TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    iconKey TEXT NOT NULL,
                    isFocusedTrunk INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_exams (
                    examId TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    shortName TEXT NOT NULL,
                    institution TEXT NOT NULL,
                    role TEXT NOT NULL,
                    trunkSlug TEXT NOT NULL,
                    lifecycleStatus TEXT NOT NULL,
                    examBoardsCsv TEXT NOT NULL,
                    dateType TEXT NOT NULL,
                    exactDateIso TEXT,
                    estimatedWindowLabel TEXT,
                    estimatedSortDateIso TEXT NOT NULL,
                    historicalCutoffScore REAL NOT NULL,
                    isActiveInLadder INTEGER NOT NULL,
                    ladderPriority INTEGER NOT NULL,
                    confirmedTopicsCount INTEGER NOT NULL,
                    newTopicsCount INTEGER NOT NULL,
                    archivedTopicsCount INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_user_exams_trunkSlug ON user_exams(trunkSlug)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_user_exams_isActiveInLadder_ladderPriority ON user_exams(isActiveInLadder, ladderPriority)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS exam_subjects (
                    examId TEXT NOT NULL,
                    localSubjectId TEXT NOT NULL,
                    canonicalSubjectId TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    category TEXT NOT NULL,
                    weight REAL NOT NULL,
                    questionCount INTEGER,
                    sortOrder INTEGER NOT NULL,
                    relevanceTier TEXT NOT NULL,
                    PRIMARY KEY(examId, localSubjectId)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_exam_subjects_canonicalSubjectId ON exam_subjects(canonicalSubjectId)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS exam_topics (
                    examId TEXT NOT NULL,
                    localSubjectId TEXT NOT NULL,
                    localTopicId TEXT NOT NULL,
                    canonicalTopicId TEXT NOT NULL,
                    canonicalSubjectId TEXT NOT NULL,
                    displayTitle TEXT NOT NULL,
                    diffStatus TEXT NOT NULL,
                    PRIMARY KEY(examId, localTopicId)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_exam_topics_canonicalTopicId ON exam_topics(canonicalTopicId)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_exam_topics_examId_localSubjectId ON exam_topics(examId, localSubjectId)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS canonical_mastery (
                    canonicalTopicId TEXT NOT NULL PRIMARY KEY,
                    canonicalSubjectId TEXT NOT NULL,
                    mastery REAL NOT NULL,
                    practiced INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}
