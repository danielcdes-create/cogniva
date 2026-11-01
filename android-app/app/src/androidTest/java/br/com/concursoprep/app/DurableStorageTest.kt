package br.com.concursoprep.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.concursoprep.app.data.local.*
import br.com.concursoprep.app.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SQLite migration/reopen tests. Every database has a disposable UUID name. */
@RunWith(AndroidJUnit4::class)
class DurableStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun open(name: String) = Room.databaseBuilder(context,AppDatabase::class.java,name)
        .addMigrations(ExamMigrations.MIGRATION_1_2,ExamMigrations.MIGRATION_2_3).build()

    @Test fun versionTwoPreservesLegacyBytesAndQuarantinesAmbiguousDelivery() = runBlocking {
        val name="cogniva-test-migration-${UUID.randomUUID()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object:SupportSQLiteOpenHelper.Callback(2){
                override fun onCreate(db:SupportSQLiteDatabase){
                    // The original version-1 tables, followed by the shipped 1->2 migration.
                    db.execSQL("CREATE TABLE cached_questions (id TEXT NOT NULL PRIMARY KEY, question TEXT NOT NULL, questionType TEXT NOT NULL, answersJson TEXT NOT NULL, correctIndicesJson TEXT NOT NULL, disciplina TEXT NOT NULL, source TEXT NOT NULL, examYear INTEGER, level TEXT NOT NULL, explanation TEXT, inErrorNotebook INTEGER NOT NULL, isMastered INTEGER NOT NULL, userNote TEXT)")
                    db.execSQL("CREATE TABLE pending_attempts (localId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, questionId TEXT NOT NULL, chosenIndex INTEGER NOT NULL, chosenText TEXT NOT NULL, isCorrect INTEGER NOT NULL, timeSpentSeconds INTEGER NOT NULL, confidenceLevel INTEGER NOT NULL, sessionUuid TEXT NOT NULL, quizMode TEXT NOT NULL, examBlock TEXT, contestId TEXT, userNote TEXT, createdAtEpochMs INTEGER NOT NULL)")
                    ExamMigrations.MIGRATION_1_2.migrate(db)
                }
                override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Unexpected fixture upgrade")
            }).build())
        try {
            val legacy=helper.writableDatabase
            legacy.execSQL("INSERT INTO cached_questions VALUES ('q','Questão com acentuação ç','MULTIPLE_CHOICE','[\"A\",\"B\"]','[1]','Finanças Públicas','Fixture',2026,'Médio','Explicação',1,0,'nota preservada',NULL,NULL)")
            legacy.execSQL("INSERT INTO pending_attempts VALUES (1,'q',1,'B',1,42,3,'old-session','STANDARD',NULL,'CPNU','nota preservada',1793480400000,NULL,NULL)")
            helper.close()
            var db=open(name)
            val question=db.concursoDao().findQuestionById("q")!!
            val attempt=db.concursoDao().getAllPendingAttempts().single()
            assertEquals("Questão com acentuação ç",question.question)
            assertEquals("nota preservada",question.userNote)
            assertNull(question.canonicalTopicId)
            assertEquals(1793480400000L,attempt.createdAtEpochMs)
            assertEquals(42,attempt.timeSpentSeconds)
            assertEquals("nota preservada",attempt.userNote)
            assertNotNull(attempt.syncError)
            UUID.fromString(attempt.attemptId)
            db.concursoDao().bindLegacyOwner(123)
            db.close()
            db=open(name)
            val reopened=db.concursoDao().getAllPendingAttempts().single()
            assertEquals(attempt.attemptId,reopened.attemptId)
            assertEquals(123L,reopened.ownerUserId)
            db.concursoDao().bindLegacyOwner(456)
            assertEquals(123L,db.concursoDao().getAllPendingAttempts().single().ownerUserId)
            db.close()
        } finally { helper.close();context.deleteDatabase(name) }
    }

    @Test fun sessionAndDeadlineSurviveReopenAndLateSavesCannotResurrectCompletion()=runBlocking {
        val name="cogniva-test-session-${UUID.randomUUID()}.db"
        try {
            var db=open(name)
            val question=QuestionDto(id="q",question="Persistida",answers=listOf("A","B"),correctAnswerIndices=listOf(1),disciplina="Fixture",source="Fixture",level="Médio")
            val snapshot=SessionSnapshot("s",123,listOf(question),QuizMode.SIMULADO,null,deadlineEpochMs=1793484000000,answers=mapOf("q" to SessionAnswer(1,3,42,true)),revision=2)
            db.durableDao().createSession(QuizSessionEntity("s",123,Json.encodeToString(snapshot),2))
            db.close();db=open(name)
            assertNull(db.durableDao().pausedSession(456))
            val restored=Json.decodeFromString<SessionSnapshot>(db.durableDao().pausedSession(123)!!.payloadJson)
            assertEquals(snapshot,restored)
            db.durableDao().saveSession("s",123,Json.encodeToString(snapshot.copy(revision=1)),1,0)
            assertEquals(2L,db.durableDao().pausedSession(123)!!.revision)
            db.durableDao().completeSession("s",123)
            db.durableDao().saveSession("s",123,Json.encodeToString(snapshot.copy(revision=3)),3,0)
            assertNull(db.durableDao().pausedSession(123))
            db.close()
        } finally { context.deleteDatabase(name) }
    }
}
