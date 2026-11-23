package br.com.concursoprep.app

import br.com.concursoprep.app.data.local.ExamMigrations
import br.com.concursoprep.app.data.update.ApkInstallStatusReceiver
import br.com.concursoprep.app.data.update.AppUpdateManager
import br.com.concursoprep.app.domain.model.ApkInfoDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        assertEquals(
            "https://198.199.65.103/api/v1/apk/download",
            AppUpdateManager.resolveDownloadUrl("https://198.199.65.103:443/api/v1/", "https://198.199.65.103/api/v1/apk/download")
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
        // Blocks host-prefix spoofing, userinfo confusion, protocol-relative, port mismatch, and path traversal
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://198.199.65.103/api/v1", "https://198.199.65.103.evil.com/api/v1/apk/download")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://198.199.65.103/api/v1", "https://attacker@198.199.65.103/api/v1/apk/download")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://198.199.65.103/api/v1", "//evil.com/apk/download")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://198.199.65.103/api/v1", "https://198.199.65.103:8443/api/v1/apk/download")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://198.199.65.103/api/v1", "/api/v1/../../etc/passwd")
            }.isFailure
        )
        // Blocks percent-encoded traversal (%2e, %2f, %5c case-insensitive), fragments (#), userInfo with password, custom base/target ports, and non-/api/ paths
        listOf(
            "/api/v1/%2e%2e/%2e%2e/etc/passwd",
            "/api/v1/%2E%2E/secret",
            "/api/v1/apk%2f..%2fsecret",
            "/api/v1/apk%2Fdownload",
            "/api/v1/apk%5cdownload",
            "/api/v1/apk%5Cdownload",
            "/api/v1/apk/download#fragment",
            "https://198.199.65.103/api/v1/apk/download#evil",
            "https://user:pass@198.199.65.103/api/v1/apk/download",
            "https://198.199.65.103:9443/api/v1/apk/download",
            "https://198.199.65.103/public/cogniva.apk"
        ).forEach { maliciousPath ->
            assertTrue(
                "Expected rejection for downloadPath '$maliciousPath'",
                runCatching {
                    AppUpdateManager.resolveDownloadUrl("https://198.199.65.103/api/v1", maliciousPath)
                }.isFailure
            )
        }
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://user@198.199.65.103/api/v1", "/api/v1/apk/download")
            }.isFailure
        )
        assertTrue(
            runCatching {
                AppUpdateManager.resolveDownloadUrl("https://198.199.65.103/api/v1#frag", "/api/v1/apk/download")
            }.isFailure
        )
    }

    @Test
    fun uiCollectorLifecycleControlsApkInstallStatusReceiverConfirmationRouting() = runBlocking {
        AppUpdateManager.resetUiCollectorsForTest()
        val receivedEvents = java.util.Collections.synchronizedList(
            mutableListOf<AppUpdateManager.InstallSessionEvent>()
        )
        // Simulate MainViewModel background collector on statusEvents (always subscribed while VM lives)
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            AppUpdateManager.statusEvents.collect { receivedEvents.add(it) }
        }
        try {
            // 1. ViewModel is subscribed, but MainActivity is STOPPED/rotating (no UI collector started):
            //    hasActiveUiCollectors() MUST be false, and receiver MUST launch confirmIntent directly!
            assertFalse(AppUpdateManager.hasActiveUiCollectors())
            val dummyConfirmIntent = android.content.Intent()
            var directStartCount = 0
            ApkInstallStatusReceiver.handleInstallStatus(
                status = android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION,
                confirmIntent = dummyConfirmIntent,
                statusMessage = null,
                startConfirmActivity = { directStartCount++ }
            )
            assertEquals(1, directStartCount)
            assertTrue(receivedEvents.none { it is AppUpdateManager.InstallSessionEvent.PendingUserConfirmation })

            // 2. MainActivity enters STARTED -> onUiCollectorStarted():
            //    hasActiveUiCollectors() becomes true, and receiver routes PendingUserConfirmation to UI without duplicate direct start!
            AppUpdateManager.onUiCollectorStarted()
            assertTrue(AppUpdateManager.hasActiveUiCollectors())
            ApkInstallStatusReceiver.handleInstallStatus(
                status = android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION,
                confirmIntent = dummyConfirmIntent,
                statusMessage = null,
                startConfirmActivity = { directStartCount++ }
            )
            assertEquals(1, directStartCount)
            assertEquals(1, receivedEvents.count { it is AppUpdateManager.InstallSessionEvent.PendingUserConfirmation })

            // 3. MainActivity stops again (ON_STOP) -> onUiCollectorStopped():
            //    hasActiveUiCollectors() immediately returns false, restoring direct fallback!
            AppUpdateManager.onUiCollectorStopped()
            assertFalse(AppUpdateManager.hasActiveUiCollectors())
            ApkInstallStatusReceiver.handleInstallStatus(
                status = android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION,
                confirmIntent = dummyConfirmIntent,
                statusMessage = null,
                startConfirmActivity = { directStartCount++ }
            )
            assertEquals(2, directStartCount)

            // 4. STATUS_SUCCESS and STATUS_FAILURE emit status events cleanly
            ApkInstallStatusReceiver.handleInstallStatus(
                status = android.content.pm.PackageInstaller.STATUS_SUCCESS,
                confirmIntent = null,
                statusMessage = null,
                startConfirmActivity = {}
            )
            ApkInstallStatusReceiver.handleInstallStatus(
                status = android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,
                confirmIntent = null,
                statusMessage = "Usuário cancelou",
                startConfirmActivity = {}
            )
            assertTrue(receivedEvents.any { it is AppUpdateManager.InstallSessionEvent.Succeeded })
            assertTrue(receivedEvents.any { it is AppUpdateManager.InstallSessionEvent.Failed && it.message.contains("Usuário cancelou") })
        } finally {
            collectorJob.cancel()
            AppUpdateManager.resetUiCollectorsForTest()
        }
    }

    @Test
    fun performanceEnginesHandleExtremeEarlyMinutesAndNonFiniteInputsSafely() {
        val fatigue = br.com.concursoprep.app.domain.exam.FatigueEngine.evaluateFatigueWithDifficulty(
            qualifyingSessions = 4,
            earlyCorrect = 50,
            earlyTotal = 65,
            lateCorrect = 40,
            lateTotal = 65,
            earlyAvgDifficulty = Double.NaN,
            lateAvgDifficulty = Double.POSITIVE_INFINITY,
            earlyMeanMinutes = 290.0,
            lateMeanMinutes = 295.0
        )
        assertTrue(fatigue.estimatedLambda != null && fatigue.estimatedLambda!!.isFinite())
        assertTrue(fatigue.shrunkAccuracyDelta != null && fatigue.shrunkAccuracyDelta!!.isFinite())

        val ret = br.com.concursoprep.app.domain.exam.RetentionEngine.updateRetention(
            priorStability = Double.NaN,
            priorDifficulty = Double.NaN,
            isCorrect = true,
            confidence = 3,
            elapsedDays = Double.NaN,
            responseSeconds = 30,
            expectedSeconds = Double.NaN
        )
        assertTrue(ret.nextStability.isFinite() && ret.nextDifficulty.isFinite())

        val strat = br.com.concursoprep.app.domain.exam.StrategyEngine.evaluateStrategy(
            pCalibrated = Double.NaN,
            penalty = Double.NaN,
            cutoffPct = Double.NaN,
            currentProjectedPct = Double.NaN,
            remainingFraction = Double.NaN
        )
        assertTrue(strat.expectedValue.isFinite())
    }

    @Test
    fun examBlueprintPayloadAcceptsBackendMongoAndDtoFieldAliases() {
        val parser = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val wire = """
            {
              "_id": "exam-1",
              "revision": 3,
              "wrong_answer_penalty": 0.5,
              "title": "BCB 2027",
              "short_name": "BCB",
              "institution": "Banco Central",
              "role": "Analista",
              "trunk_slug": "fiscal",
              "status": "PROVA_REALIZADA",
              "exam_boards": ["Cebraspe"],
              "target_date": {"type": "UNDEFINED"},
              "historical_cutoff_score": 72.0,
              "is_active_in_ladder": true,
              "ladder_priority": 1,
              "subjects": [],
              "sync_status": "SYNCED",
              "template_source_id": "tpl-1",
              "base_template_revision": 9,
              "is_custom_copy": true,
              "upstream_update_available": false
            }
        """.trimIndent()
        val parsed = parser.decodeFromString<br.com.concursoprep.app.data.remote.ExamBlueprintPayload>(wire)
        assertEquals(9L, parsed.templateRevisionSynced)
        assertTrue(parsed.isCustomized)
    }

    @Test
    fun roomSchemaV7ExportAndMigrationChainAreComplete() {
        val migrations = listOf(
            ExamMigrations.MIGRATION_1_2,
            ExamMigrations.MIGRATION_2_3,
            ExamMigrations.MIGRATION_3_4,
            ExamMigrations.MIGRATION_4_5,
            ExamMigrations.MIGRATION_5_6,
            ExamMigrations.MIGRATION_6_7
        )
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7),
            migrations.map { it.startVersion to it.endVersion }
        )

        val schemaCandidates = listOf(
            File("schemas/br.com.concursoprep.app.data.local.AppDatabase/7.json"),
            File("app/schemas/br.com.concursoprep.app.data.local.AppDatabase/7.json")
        )
        val schemaFile = schemaCandidates.firstOrNull { it.exists() }
        assertTrue("Room v7 exported schema 7.json must exist at ${schemaCandidates.map { it.path }}", schemaFile != null)

        val root = Json.parseToJsonElement(schemaFile!!.readText()).jsonObject
        assertEquals(1, root["formatVersion"]!!.jsonPrimitive.int)
        val dbObj = root["database"]!!.jsonObject
        assertEquals(7, dbObj["version"]!!.jsonPrimitive.int)
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

        val pendingAttemptFields = entitiesByName.getValue("pending_attempts")["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            .toSet()
        assertTrue(
            pendingAttemptFields.containsAll(
                listOf(
                    "isBlank",
                    "confidenceExplicit",
                    "selectedEvidenceId",
                    "positionInSession",
                    "elapsedSessionSeconds",
                    "sessionInterrupted"
                )
            )
        )
    }
}
