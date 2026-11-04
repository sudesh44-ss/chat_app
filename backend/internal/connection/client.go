package connection

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"time"

	"github.com/gorilla/websocket"
	"nexa/wire/internal/wire"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 512 * 1024 // 512 KB max packet size
	authTimeout    = 60 * time.Second
)

type ClientSession struct {
	Hub             *Hub
	Conn            *websocket.Conn
	NxId            string
	DeviceId        string
	Send            chan []byte
	Authenticated   bool
	ChallengeNonce  []byte
	ChallengeTime   int64
	ChallengeExpiry int64
	AuthTimer       *time.Timer
}

func NewClientSession(hub *Hub, conn *websocket.Conn, nxId string, deviceId string) *ClientSession {
	if deviceId == "" {
		deviceId = "default"
	}
	return &ClientSession{
		Hub:           hub,
		Conn:          conn,
		NxId:          nxId,
		DeviceId:      deviceId,
		Send:          make(chan []byte, 256),
		Authenticated: false,
	}
}

// SafeSend attempts to enqueue data to Send channel, recovering safely if closed or full.
func (c *ClientSession) SafeSend(data []byte) (sent bool) {
	defer func() {
		if r := recover(); r != nil {
			sent = false
		}
	}()
	select {
	case c.Send <- data:
		return true
	default:
		return false
	}
}

// SendChallenge generates a single-use 32-byte cryptographic challenge nonce and dispatches it to the client.
func (c *ClientSession) SendChallenge() error {
	nonce := make([]byte, 32)
	if _, err := rand.Read(nonce); err != nil {
		return fmt.Errorf("failed to generate secure random nonce: %w", err)
	}

	now := time.Now().UnixMilli()
	expiry := now + int64(authTimeout/time.Millisecond)

	c.ChallengeNonce = nonce
	c.ChallengeTime = now
	c.ChallengeExpiry = expiry

	challengePacket := &wire.NexaWirePacket{
		PacketId:      fmt.Sprintf("auth_chal_%s_%d", c.NxId, now),
		Type:          wire.PacketType_PACKET_AUTH_CHALLENGE,
		SenderNxId:    "SERVER",
		RecipientNxId: c.NxId,
		Timestamp:     now,
		AuthChallenge: &wire.AuthChallengePayload{
			ChallengeNonce: nonce,
			Timestamp:      now,
			ExpiresAt:      expiry,
		},
	}

	raw, err := challengePacket.Encode()
	if err != nil {
		return fmt.Errorf("failed to encode auth challenge packet: %w", err)
	}

	select {
	case c.Send <- raw:
	default:
		return fmt.Errorf("failed to queue auth challenge packet: buffer full")
	}

	// 60-second strict auth deadline
	c.AuthTimer = time.AfterFunc(authTimeout, func() {
		if !c.Authenticated {
			log.Printf("[Session %s] Authentication timeout expired. Terminating connection.", c.NxId)
			c.CloseWithUnauthorized("Authentication timeout")
		}
	})

	return nil
}

// VerifyAuthResponse validates the client's Ed25519 signature against the challenge.
func (c *ClientSession) VerifyAuthResponse(packet *wire.NexaWirePacket) bool {
	if packet.AuthResponse == nil {
		log.Printf("[Session %s] Auth failed: missing AuthResponse payload", c.NxId)
		return false
	}
	resp := packet.AuthResponse

	// Single-use check: challenge must be present
	if len(c.ChallengeNonce) == 0 {
		log.Printf("[Session %s] Auth failed: no active challenge (replay or already used)", c.NxId)
		return false
	}

	// Expiry check (60 seconds)
	if time.Now().UnixMilli() > c.ChallengeExpiry {
		log.Printf("[Session %s] Auth failed: challenge expired", c.NxId)
		c.ChallengeNonce = nil
		return false
	}

	// Connection binding: sender must match registered NX-ID
	if packet.SenderNxId != c.NxId {
		log.Printf("[Session %s] Auth failed: sender NX-ID mismatch (%s vs %s)", c.NxId, packet.SenderNxId, c.NxId)
		c.ChallengeNonce = nil
		return false
	}

	if len(resp.PublicKey) != ed25519.PublicKeySize {
		log.Printf("[Session %s] Auth failed: invalid Ed25519 public key length (%d bytes)", c.NxId, len(resp.PublicKey))
		c.ChallengeNonce = nil
		return false
	}

	if len(resp.Signature) != ed25519.SignatureSize {
		log.Printf("[Session %s] Auth failed: invalid Ed25519 signature length (%d bytes)", c.NxId, len(resp.Signature))
		c.ChallengeNonce = nil
		return false
	}

	// Data to sign: challengeNonce + ":" + nxId + ":" + challengeTime
	dataToSign := append(append(c.ChallengeNonce, []byte(":"+c.NxId+":")...), []byte(fmt.Sprintf("%d", c.ChallengeTime))...)
	altDataToSign := []byte(fmt.Sprintf("%s:%s:%d", hex.EncodeToString(c.ChallengeNonce), c.NxId, c.ChallengeTime))

	// Invalidate challenge immediately (anti-replay single use)
	c.ChallengeNonce = nil

	valid := ed25519.Verify(resp.PublicKey, dataToSign, resp.Signature) ||
		ed25519.Verify(resp.PublicKey, altDataToSign, resp.Signature)

	if !valid {
		log.Printf("[Session %s] Auth failed: cryptographic Ed25519 signature verification failed", c.NxId)
		return false
	}

	c.Authenticated = true
	if c.AuthTimer != nil {
		c.AuthTimer.Stop()
	}

	return true
}

func (c *ClientSession) CloseWithUnauthorized(reason string) {
	closeMsg := websocket.FormatCloseMessage(4001, reason)
	c.Conn.WriteControl(websocket.CloseMessage, closeMsg, time.Now().Add(time.Second))
	c.Conn.Close()
}

// ReadPump pumps messages from the websocket connection to the Hub.
func (c *ClientSession) ReadPump() {
	defer func() {
		if c.AuthTimer != nil {
			c.AuthTimer.Stop()
		}
		if c.Authenticated {
			c.Hub.Unregister(c)
		}
		c.Conn.Close()
	}()

	c.Conn.SetReadLimit(maxMessageSize)
	c.Conn.SetReadDeadline(time.Now().Add(pongWait))
	c.Conn.SetPongHandler(func(string) error {
		c.Conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		messageType, data, err := c.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("[Session %s] Read error: %v", c.NxId, err)
			}
			break
		}

		if messageType != websocket.BinaryMessage {
			continue
		}

		packet, err := wire.Decode(data)
		if err != nil {
			log.Printf("[Session %s] Malformed wire packet received: %v", c.NxId, err)
			continue
		}

		// Handle Authentication Handshake
		if packet.Type == wire.PacketType_PACKET_AUTH_RESPONSE {
			if c.VerifyAuthResponse(packet) {
				log.Printf("[Session %s] Ed25519 authentication SUCCESSFUL. Registering session in Hub.", c.NxId)
				c.Hub.Register(c)

				// Send confirmation ACK (PACKET_AUTH_SUCCESS)
				successPacket := &wire.NexaWirePacket{
					PacketId:      fmt.Sprintf("auth_ack_%s_%d", c.NxId, time.Now().UnixMilli()),
					Type:          wire.PacketType_PACKET_AUTH_SUCCESS,
					SenderNxId:    "SERVER",
					RecipientNxId: c.NxId,
					Timestamp:     time.Now().UnixMilli(),
				}
				if raw, err := successPacket.Encode(); err == nil {
					c.Send <- raw
				}
			} else {
				log.Printf("[Session %s] Ed25519 authentication FAILED. Disconnecting with 4001.", c.NxId)
				failPacket := &wire.NexaWirePacket{
					PacketId:      fmt.Sprintf("auth_fail_%s_%d", c.NxId, time.Now().UnixMilli()),
					Type:          wire.PacketType_PACKET_AUTH_FAILURE,
					SenderNxId:    "SERVER",
					RecipientNxId: c.NxId,
					Timestamp:     time.Now().UnixMilli(),
				}
				if raw, err := failPacket.Encode(); err == nil {
					c.Conn.WriteMessage(websocket.BinaryMessage, raw)
				}
				c.CloseWithUnauthorized("Ed25519 verification failed")
				break
			}
			continue
		}

		// Gate all application traffic behind authenticated status
		if !c.Authenticated {
			log.Printf("[Session %s] Security violation: received packet type %v before authentication. Dropping connection.", c.NxId, packet.Type)
			c.CloseWithUnauthorized("Authentication required")
			break
		}

		// Route authenticated packet
		c.Hub.RouteDecodedPacket(c, packet, data)
	}
}

// WritePump pumps messages from the Send channel to the websocket connection.
func (c *ClientSession) WritePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.Conn.Close()
	}()

	for {
		select {
		case message, ok := <-c.Send:
			c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				// The Hub closed the channel
				c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			w, err := c.Conn.NextWriter(websocket.BinaryMessage)
			if err != nil {
				return
			}
			w.Write(message)
			if err := w.Close(); err != nil {
				return
			}

			// Drain queued messages as separate binary frames (prevents Protobuf packet concatenation)
			n := len(c.Send)
			for i := 0; i < n; i++ {
				queuedMsg, ok := <-c.Send
				if !ok {
					return
				}
				c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
				wNext, err := c.Conn.NextWriter(websocket.BinaryMessage)
				if err != nil {
					return
				}
				wNext.Write(queuedMsg)
				if err := wNext.Close(); err != nil {
					return
				}
			}

		case <-ticker.C:
			c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
