package com.nexa.app

import com.nexa.app.core.webrtc.ConnectionDiagnostic
import com.nexa.app.core.webrtc.TurnConfigManager
import com.nexa.app.core.webrtc.TurnServerConfig
import com.nexa.app.core.webrtc.WebRtcClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.PeerConnection

class WebRtcTurnRelayUnitTests {

    @Before
    fun setUp() {
        TurnConfigManager.clear()
    }

    @After
    fun tearDown() {
        TurnConfigManager.clear()
    }

    @Test
    fun testStunOnlyConfigurationRegression() {
        // When no TURN server is configured, baseline STUN servers must remain present
        val iceServers = WebRtcClient.buildIceServerList(emptyList())
        assertEquals(3, iceServers.size)
        // Verify default STUN endpoints
        assertTrue(iceServers.any { it.urls.any { url -> url.contains("stun.l.google.com:19302") } })
        assertTrue(iceServers.any { it.urls.any { url -> url.contains("stun1.l.google.com:19302") } })
        assertTrue(iceServers.any { it.urls.any { url -> url.contains("stun2.l.google.com:19302") } })
    }

    @Test
    fun testTurnConfiguredIceServerParsing() {
        val turnUdp = TurnServerConfig(
            url = "turn.nexa.network:3478",
            username = "alice_relay",
            credential = "secret_turn_password_123",
            transport = "udp"
        )
        val turnTcp = TurnServerConfig(
            url = "turn:turn.nexa.network:3478",
            username = "alice_relay",
            credential = "secret_turn_password_123",
            transport = "tcp"
        )
        val turnsTls = TurnServerConfig(
            url = "turns:turn.nexa.network:5349",
            username = "alice_relay",
            credential = "secret_turn_password_123",
            transport = "tcp"
        )

        val iceServerUdp = turnUdp.toIceServer()
        assertNotNull(iceServerUdp)
        assertEquals("alice_relay", iceServerUdp.username)
        assertEquals("secret_turn_password_123", iceServerUdp.password)
        assertTrue(iceServerUdp.urls.any { it.contains("turn:turn.nexa.network:3478?transport=udp") })

        val iceServerTcp = turnTcp.toIceServer()
        assertTrue(iceServerTcp.urls.any { it.contains("turn:turn.nexa.network:3478?transport=tcp") })

        val iceServerTls = turnsTls.toIceServer()
        assertTrue(iceServerTls.urls.any { it.startsWith("turns:turn.nexa.network:5349") })

        val combinedList = WebRtcClient.buildIceServerList(listOf(turnUdp, turnTcp, turnsTls))
        // 3 baseline STUN + 3 TURN servers = 6 total
        assertEquals(6, combinedList.size)
    }

    @Test
    fun testCredentialRedactionInLogs() {
        val secretPassword = "super_sensitive_turn_credential_never_leak_in_logs"
        val config = TurnServerConfig(
            url = "turn:turn.relay.org:3478",
            username = "user_node_99",
            credential = secretPassword,
            transport = "udp"
        )

        val logString = config.toRedactedString()
        assertFalse("Log string leaked plaintext password!", logString.contains(secretPassword))
        assertTrue("Log string must mask credential with ***", logString.contains("cred=***"))
        assertTrue("Log string should keep username", logString.contains("user=user_node_99"))
        assertTrue("Log string should show URL", logString.contains("turn:turn.relay.org:3478"))
    }

    @Test
    fun testDiagnosticDirectP2PvsTurnRelayed() {
        // Connected with direct candidate (host or srflx) -> DIRECT_P2P
        val directDiag = WebRtcClient.determineDiagnostic(
            iceState = PeerConnection.IceConnectionState.CONNECTED,
            candidateType = "host",
            hasTurnConfig = true
        )
        assertEquals(ConnectionDiagnostic.DIRECT_P2P, directDiag)

        val srflxDiag = WebRtcClient.determineDiagnostic(
            iceState = PeerConnection.IceConnectionState.COMPLETED,
            candidateType = "srflx",
            hasTurnConfig = true
        )
        assertEquals(ConnectionDiagnostic.DIRECT_P2P, srflxDiag)

        // Connected with relay candidate -> TURN_RELAYED
        val relayDiag = WebRtcClient.determineDiagnostic(
            iceState = PeerConnection.IceConnectionState.CONNECTED,
            candidateType = "relay",
            hasTurnConfig = true
        )
        assertEquals(ConnectionDiagnostic.TURN_RELAYED, relayDiag)
    }

    @Test
    fun testDiagnosticIceFailureWithAndWithoutTurn() {
        // ICE failed without TURN configured -> TURN_UNAVAILABLE
        val failNoTurn = WebRtcClient.determineDiagnostic(
            iceState = PeerConnection.IceConnectionState.FAILED,
            candidateType = null,
            hasTurnConfig = false
        )
        assertEquals(ConnectionDiagnostic.TURN_UNAVAILABLE, failNoTurn)

        // ICE failed even with TURN configured -> ICE_FAILED
        val failWithTurn = WebRtcClient.determineDiagnostic(
            iceState = PeerConnection.IceConnectionState.FAILED,
            candidateType = null,
            hasTurnConfig = true
        )
        assertEquals(ConnectionDiagnostic.ICE_FAILED, failWithTurn)
    }

    @Test
    fun testTurnConfigManagerDynamicRegistration() {
        assertEquals(0, TurnConfigManager.turnConfigs.size)

        val cfg = TurnServerConfig("turn.coturn.cloud:3478", "test_user", "test_pass")
        TurnConfigManager.addTurnServer(cfg)
        assertEquals(1, TurnConfigManager.turnConfigs.size)

        TurnConfigManager.clear()
        assertEquals(0, TurnConfigManager.turnConfigs.size)
    }
}
