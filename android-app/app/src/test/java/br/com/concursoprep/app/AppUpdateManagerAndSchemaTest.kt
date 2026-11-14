package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.ExamMigrations
import br.com.concursoprep.app.data.update.AppUpdateManager
import br.com.concursoprep.app.domain.model.ApkInfoDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdateManagerAndSchemaTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun validateMetadataEnforcesPositiveSizeNonBlankHexSha256AndNewerVersionCode() {
        val validSha = "a".repeat(64)
        val valid = AppUpdateManager.validateMetadata(
            info = ApkInfoDto(
                versionCode = 27,
                versionName = "2.0.2",
                sizeBytes = 1024L,
                sha256Checksum = validSha.uppercase()
            ),
            installedVersionCode = 26
        )
        assertEquals(27, valid.versionCode)
        assertEquals(1024L, valid.expectedBytes)
        assertEquals(validSha, valid.expectedSha256Hex)

        // Rejects equal or older versionCode
        assertTrue(
            runCatching {
                AppUpdateManager.validateMetadata(
                    ApkInfoDto(26, "2.0.1", 1024L, validSha),
                    installedVersionCode = 26
                )
            }.isFailure
        )
        // Rejects zero or negative sizeBytes
        assertTrue(
            runCatching {
                AppUpdateManager.validateMetadata(
                    ApkInfoDto(27, "2.0.2", 0L, validSha),
                    installedVersionCode = 26
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                AppUpdateManager.validateMetadata(
                    ApkInfoDto(27, "2.0.2", -50L, validSha),
                    installedVersionCode = 26
                )
            }.isFailure
        )
        // Rejects oversized package
        assertTrue(
            runCatching {
                AppUpdateManager.validateMetadata(
                    ApkInfoDto(27, "2.0.2", AppUpdateManager.MAX_APK_BYTES + 1L, validSha),
                    installedVersionCode = 26
                )
            }.isFailure
        )
        // Rejects blank or non-hex SHA-256
        listOf("", "   ", "deadbeef", "g".repeat(64)).forEach { badHash ->
            assertTrue(
                "Expected rejection for hash '$badHash'",
                runCatching {
                    AppUpdateManager.validateMetadata(
                        ApkInfoDto(27, "2.0.2", 1024L, badHash),
                        installedVersionCode = 26
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun downloadVerifyAndStreamToSinkVerifiesPartFileStreamsFromOpenDescriptorAndCleansUp() {
        val payload = ByteArray(48_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val expectedSha = sha256Hex(payload)
        val validated = AppUpdateManager.validateMetadata(
            ApkInfoDto(27, "2.0.2", payload.size.toLong(), expectedSha),
            installedVersionCode = 26
        )

        val tempDir = Files.createTempDirectory("cogniva-ota-test").toFile()
        try {
            var inspectedExistsDuringCallback = false
            var sinkExpectedBytes = -1L
            val sinkBuffer = ByteArrayOutputStream()

            AppUpdateManager.downloadVerifyAndStreamToSink(
                source = ByteArrayInputStream(payload),
                validated = validated,
                updatesDir = tempDir,
                onInspectVerifiedTempFile = { part ->
                    inspectedExistsDuringCallback = part.exists() && part.name.endsWith(".apk.part")
                },
                sinkOpener = { expected ->
                    sinkExpectedBytes = expected
                    sinkBuffer
                }
            )

            assertTrue(inspectedExistsDuringCallback)
            assertEquals(payload.size.toLong(), sinkExpectedBytes)
            assertArrayEquals(payload, sinkBuffer.toByteArray())
            assertTrue(
                "Temporary .apk.part must be deleted after streaming into session",
                tempDir.listFiles().orEmpty().none { it.name.endsWith(".part") }
            )

            // Rejects mismatched SHA-256 BEFORE opening session sink
            val tampered = payload.copyOf().also { it[10] = (it[10].toInt() xor 0xFF).toByte() }
            var sinkOpenedOnTamper = false
            assertTrue(
                runCatching {
                    AppUpdateManager.downloadVerifyAndStreamToSink(
                        source = ByteArrayInputStream(tampered),
                        validated = validated,
                        updatesDir = tempDir,
                        sinkOpener = {
                            sinkOpenedOnTamper = true
                            ByteArrayOutputStream()
                        }
                    )
                }.isFailure
            )
            assertFalse("Session sink must NEVER open when SHA-256 fails", sinkOpenedOnTamper)
            assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".part") })

            // Rejects truncated or oversized streams
            assertTrue(
                runCatching {
                    AppUpdateManager.downloadVerifyAndStreamToSink(
                        source = ByteArrayInputStream(payload.copyOfRange(0, payload.size - 1)),
                        validated = validated,
                        updatesDir = tempDir,
                        sinkOpener = { ByteArrayOutputStream() }
                    )
                }.isFailure
            )
            assertTrue(
                runCatching {
                    AppUpdateManager.downloadVerifyAndStreamToSink(
                        source = ByteArrayInputStream(payload + byteArrayOf(0x01)),
                        validated = validated,
                        updatesDir = tempDir,
                        sinkOpener = { ByteArrayOutputStream() }
                    )
                }.isFailure
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun resolveDownloadUrlEnforcesHttpsAndSameOrigin() {
        assertEquals(
            "https://api.cogniva.com.br/api/v1/apk/download",
            AppUpdateManager.resolveDownloadUrl("https://api.cogniva.com.br/api/v1", "/api/v1/apk/download")
        )
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("http://insecure.example.com/api/v1", "/api/v1/apk/download")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://api.cogniva.com.br/api/v1", "https://evil.example.com/app.apk")
            }.isFailure
        )
    }

    @Test
    fun roomSchemaV6ExportAndMigrationChainAreComplete() {
        val migrations = listOf(
            ExamMigrations.MIGRATION_1_2,
            ExamMigrations.MIGRATION_2_3,
            ExamMigrations.MIGRATION_3_4,
            ExamMigrations.MIGRATION_4_5,
            ExamMigrations.MIGRATION_5_6
        )
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6), migrations.map { it.startVersion to it.endVersion })

        val schemaCandidates = listOf(
            File("schemas/br.com.concursoprep.app.data.local.AppDatabase/6.json"),
            File("app/schemas/br.com.concursoprep.app.data.local.AppDatabase/6.json")
        )
        val schemaFile = schemaCandidates.firstOrNull { it.exists() }
        assertTrue("Room v6 exported schema 6.json must exist at ${schemaCandidates.map { it.path }}", schemaFile != null)

        val root = Json.parseToJsonElement(schemaFile!!.readText()).jsonObject
        assertEquals(1, root["formatVersion"]!!.jsonPrimitive.int)
        val dbObj = root["database"]!!.jsonObject
        assertEquals(6, dbObj["version"]!!.jsonPrimitive.int)
        val entitiesByName = dbObj["entities"]!!.jsonArray.associate {
            val obj = it.jsonObject
            obj["tableName"]!!.jsonPrimitive.content to obj
        }
        val expectedTables = setOf(
            "cached_questions",
            "pending_attempts",
            "user_exams",
            "exam_subjects",
            "exam_topics",
            "career_trunks",
            "learning_records",
            "pending_reports",
            "quiz_sessions",
            "local_metadata",
            "canonical_mastery"
        )
        assertEquals(expectedTables, entitiesByName.keys)

        val userExamFields = entitiesByName.getValue("user_exams")["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            .toSet()
        assertTrue(
            userExamFields.containsAll(
                listOf("templateSourceId", "templateRevisionSynced", "isCustomized", "upstreamUpdateAvailable")
            )
        )
    }
}
