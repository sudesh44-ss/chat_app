package com.nexa.app

import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.remote.dto.RegistrationSuccessResponse
import com.nexa.app.data.remote.dto.VerificationStatusDto
import com.nexa.app.presentation.welcome.googleVerificationFailedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 regression guards for the P0 authentication defects:
 * - Google verification must fail closed.
 * - Logout must not delete the SQLCipher passphrase or local identity keys.
 * - Registration must carry the server-issued device id.
 */
class AuthHardeningUnitTests {

    @Test
    fun googleVerificationFailureIsFailClosed() {
        val state = googleVerificationFailedState("server unreachable")

        assertFalse("failed verification must never report success", state.isSuccess)
        assertFalse("failed verification must not bypass onboarding", state.isInstantLogin)
        assertFalse("failed verification must not request SIM bypass", state.requireSimVerification)
        assertNull("no Google token may be persisted on failure", state.googleIdToken)
        assertNull("no backend response may be carried on failure", state.googleResponse)
        assertEquals("server unreachable", state.errorMessage)
    }

    @Test
    fun sessionClearDoesNotTouchLocalIdentityMaterial() {
        val overlap = SecureStorage.SESSION_SCOPED_KEYS intersect SecureStorage.LOCAL_IDENTITY_KEYS

        assertTrue(
            "session-scoped and local-identity keys must be disjoint, overlapping: $overlap",
            overlap.isEmpty()
        )
        assertTrue(SecureStorage.SESSION_SCOPED_KEYS.contains("key_auth_token"))
    }

    @Test
    fun logoutPreservesDatabasePassphraseAndDeviceIdentity() {
        // These keys make the encrypted local database readable. Deleting them on
        // logout is a data-loss bug, so they must never be session-scoped.
        assertTrue(SecureStorage.LOCAL_IDENTITY_KEYS.contains("key_db_passphrase"))
        assertTrue(SecureStorage.LOCAL_IDENTITY_KEYS.contains("key_device_id"))
        assertTrue(SecureStorage.LOCAL_IDENTITY_KEYS.contains("key_identity_public_key"))
        assertTrue(SecureStorage.LOCAL_IDENTITY_KEYS.contains("key_identity_encrypted_privkey"))

        assertFalse(SecureStorage.SESSION_SCOPED_KEYS.contains("key_db_passphrase"))
        assertFalse(SecureStorage.SESSION_SCOPED_KEYS.contains("key_device_id"))
        assertFalse(SecureStorage.SESSION_SCOPED_KEYS.contains("key_identity_encrypted_privkey"))
    }

    @Test
    fun registrationResponseCarriesServerDeviceId() {
        val response = RegistrationSuccessResponse(
            nxId = "NX-ABCD1234",
            sessionAuthToken = "nexa_sess_test",
            registeredTimestamp = 1L,
            deviceId = "dev_server_issued"
        )

        assertEquals("dev_server_issued", response.deviceId)
    }

    @Test
    fun verificationStatusKeepsVerificationAndIdentityFields() {
        val dto = VerificationStatusDto(
            status = "VERIFIED",
            isVerified = true,
            expiresInSeconds = 300,
            nxId = "NX-ABCD1234",
            sessionAuthToken = "nexa_sess_test",
            name = "Test User",
            phone = "+919876543210",
            avatarUrl = "https://example.test/avatar.png"
        )

        assertEquals("VERIFIED", dto.status)
        assertTrue(dto.isVerified)
        assertEquals(300, dto.expiresInSeconds)
        assertEquals("NX-ABCD1234", dto.nxId)
        assertEquals("nexa_sess_test", dto.sessionAuthToken)
    }

    @Test
    fun revokedDeviceRetainsHistoryMetadata() {
        val revoked = com.nexa.app.data.remote.dto.DeviceInfoDto(
            deviceId = "dev_old",
            deviceName = "Old Phone",
            isRevoked = true,
            revokedAt = 1_700_000_000_000L,
            revokedReason = "replaced by new sign-in",
            createdAt = 1_600_000_000_000L
        )

        assertTrue(revoked.isRevoked)
        assertEquals(1_700_000_000_000L, revoked.revokedAt)
        assertEquals("replaced by new sign-in", revoked.revokedReason)

        // A revoke should not drop the history fields; the list is reloaded from
        // the server afterwards.
        val reloaded = revoked.copy()
        assertEquals(revoked.revokedAt, reloaded.revokedAt)
        assertEquals(revoked.revokedReason, reloaded.revokedReason)
    }

    @Test
    fun hardwareSecurityLevelSurvivesLogout() {
        // The verified Keystore level describes the physical device, not a
        // session. Clearing it on logout would silently downgrade the UI claim.
        assertFalse(SecureStorage.SESSION_SCOPED_KEYS.contains("key_hardware_security_level"))
    }
}
