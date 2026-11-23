package br.com.concursoprep.app

import br.com.concursoprep.app.data.AuthStore
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.remote.NativeSessionPayload
import br.com.concursoprep.app.domain.model.AuthSessionDto
import br.com.concursoprep.app.domain.model.UserProfileDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class AuthStoreTest {

    private fun fakeApi(onGuest: (NativeSessionPayload) -> AuthSessionDto): ConcursoApiService =
        Proxy.newProxyInstance(
            ConcursoApiService::class.java.classLoader,
            arrayOf(ConcursoApiService::class.java)
        ) { _, method, args ->
            when (method.name) {
                "guestSession" -> onGuest(args!![0] as NativeSessionPayload)
                else -> error("Unexpected API call: ${method.name}")
            }
        } as ConcursoApiService

    @Test
    fun aesGcmEncryptionUpgradeAndPlaintextFallbackWorkWithoutTautology() {
        val prefs = InMemorySharedPreferences()
        // Pre-populate a legacy unencrypted token before opening AuthStore with AES-256 key
        prefs.edit().putString("auth_token_500", "legacy-plaintext-jwt").commit()
        val key = TestAuthFixtures.newAes256Key()
        val store = AuthStore(prefs, keyProvider = { key })

        // First read returns exact plaintext AND upgrades stored representation to enc:v1:
        assertEquals("legacy-plaintext-jwt", store.token(500L))
        assertTrue(prefs.rawString("auth_token_500").orEmpty().startsWith("enc:v1:"))
        // Subsequent read decrypts via AES-GCM
        assertEquals("legacy-plaintext-jwt", store.token(500L))
    }

    @Test
    fun ensureSessionMirrorsGuestTokenForNegativeOwnerAndReusesValidJwtWithoutNetwork() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val key = TestAuthFixtures.newAes256Key()
        val store = AuthStore(prefs, keyProvider = { key })
        val offlineOwner = store.localUserId
        assertTrue("Offline localUserId must be strictly negative", offlineOwner < 0L)

        val validJwt = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L + 3600L, sub = 9001L)
        val guestCalls = AtomicInteger(0)
        val api = fakeApi { payload ->
            guestCalls.incrementAndGet()
            assertEquals(store.installationId, payload.installationId)
            assertEquals(store.installationSecret, payload.installationSecret)
            AuthSessionDto(
                token = validJwt,
                expiresInDays = 30,
                profile = UserProfileDto(userId = 9001L, displayName = "Convidado", isGuest = true)
            )
        }

        // 1. Initial ensureSession on negative offline owner fetches guest session and mirrors token under BOTH 9001L and negative owner
        store.ensureSession(api, offlineOwner)
        assertEquals(1, guestCalls.get())
        assertEquals(validJwt, store.token(9001L))
        assertEquals(validJwt, store.token(offlineOwner))

        // 2. Subsequent ensureSession while JWT exp > now + 60 reuses cached token with ZERO network calls
        store.ensureSession(api, offlineOwner)
        store.ensureSession(api, 9001L)
        assertEquals(1, guestCalls.get())
    }

    @Test
    fun ensureSessionRenewsExpiredGuestJwtAutomatically() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val key = TestAuthFixtures.newAes256Key()
        val store = AuthStore(prefs, keyProvider = { key })
        val expiredJwt = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L - 120L, sub = 9002L)
        val freshJwt = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L + 7200L, sub = 9002L)

        store.accept(
            AuthSessionDto(
                token = expiredJwt,
                profile = UserProfileDto(userId = 9002L, isGuest = true)
            )
        )
        assertFalse(store.isRegisteredAccount(9002L))

        val guestCalls = AtomicInteger(0)
        val api = fakeApi {
            guestCalls.incrementAndGet()
            AuthSessionDto(
                token = freshJwt,
                profile = UserProfileDto(userId = 9002L, isGuest = true)
            )
        }

        store.ensureSession(api, 9002L)
        assertEquals(1, guestCalls.get())
        assertEquals(freshJwt, store.token(9002L))
    }

    @Test
    fun ensureSessionBlocksExpiredRegisteredAccountAndPreservesPendingProfile() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val key = TestAuthFixtures.newAes256Key()
        val store = AuthStore(prefs, keyProvider = { key })
        val expiredJwt = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L - 10L, sub = 3001L)

        store.select(3001L)
        store.accept(
            AuthSessionDto(
                token = expiredJwt,
                profile = UserProfileDto(
                    userId = 3001L,
                    email = "candidato@cogniva.app",
                    displayName = "Candidato",
                    isGuest = false
                )
            )
        )
        store.setPendingProfile(3001L, """{"user_id":3001,"daily_questions_target":60}""")
        assertTrue(store.isRegisteredAccount(3001L))

        val guestCalls = AtomicInteger(0)
        val api = fakeApi {
            guestCalls.incrementAndGet()
            error("Registered account must never silently downgrade to guestSession")
        }

        val err = runCatching { store.ensureSession(api, 3001L) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message.orEmpty().contains("sessão expirou", ignoreCase = true))
        assertEquals(0, guestCalls.get())
        assertEquals("""{"user_id":3001,"daily_questions_target":60}""", store.pendingProfile(3001L))
    }

    @Test
    fun ensureSessionRejectsPositiveOwnerMismatchAgainstGuestSessionUserId() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val key = TestAuthFixtures.newAes256Key()
        val store = AuthStore(prefs, keyProvider = { key })
        store.setPendingProfile(777L, """{"user_id":777}""")

        val api = fakeApi {
            AuthSessionDto(
                token = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L + 3600L, sub = 888L),
                profile = UserProfileDto(userId = 888L, isGuest = true)
            )
        }

        val err = runCatching { store.ensureSession(api, 777L) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message.orEmpty().contains("proprietário original", ignoreCase = true))
        assertNull(store.token(777L))
        assertEquals("""{"user_id":777}""", store.pendingProfile(777L))
    }

    @Test
    fun wipeAccountAndResetToGuestRotatesInstallationIdentityAndPurgesAccountSecrets() {
        val prefs = InMemorySharedPreferences()
        val key = TestAuthFixtures.newAes256Key()
        val store = AuthStore(prefs, keyProvider = { key })
        val oldInstallationId = store.installationId
        val oldInstallationSecret = store.installationSecret
        val oldLocalUserId = store.localUserId

        val deletedUid = 4444L
        store.select(deletedUid)
        store.accept(
            AuthSessionDto(
                token = TestAuthFixtures.buildJwt(System.currentTimeMillis() / 1000L + 3600L, sub = deletedUid),
                profile = UserProfileDto(
                    userId = deletedUid,
                    email = "excluir@cogniva.app",
                    displayName = "Titular LGPD",
                    role = "admin",
                    isGuest = false,
                    isAdmin = true,
                    dailyQuestionsTarget = 90,
                    activeContestIds = listOf("RFB")
                )
            )
        )
        store.setPendingProfile(deletedUid, """{"user_id":4444}""")
        store.setPendingProfile(oldLocalUserId, """{"user_id":$oldLocalUserId}""")

        val freshGuestId = store.wipeAccountAndResetToGuest(deletedUid, "Conta excluída conforme LGPD.")

        assertTrue(freshGuestId < 0L)
        assertNotEquals(oldLocalUserId, freshGuestId)
        assertNotEquals(oldInstallationId, store.installationId)
        assertNotEquals(oldInstallationSecret, store.installationSecret)
        assertEquals(freshGuestId, store.localUserId)
        assertEquals(freshGuestId, store.selectedUserId)

        assertNull(store.token(deletedUid))
        assertNull(store.token(oldLocalUserId))
        assertNull(store.token(freshGuestId))
        assertFalse(store.isRegisteredAccount(deletedUid))
        assertNull(store.pendingProfile(deletedUid))
        assertNull(store.pendingProfile(oldLocalUserId))
        assertNull(prefs.getString("email", null))
        assertTrue(prefs.getBoolean("is_guest", false))
        assertFalse(prefs.getBoolean("is_admin", true))
        assertEquals("Conta excluída conforme LGPD.", prefs.getString("pending_feedback_message", null))
    }
}
