package wire

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
)

type PacketType int32

const (
	PacketType_PACKET_UNKNOWN        PacketType = 0
	PacketType_PACKET_MESSAGE        PacketType = 1
	PacketType_PACKET_ACK            PacketType = 2
	PacketType_PACKET_PING           PacketType = 3
	PacketType_PACKET_AUTH_CHALLENGE PacketType = 4
	PacketType_PACKET_AUTH_RESPONSE  PacketType = 5
	PacketType_PACKET_AUTH_SUCCESS   PacketType = 6
	PacketType_PACKET_AUTH_FAILURE   PacketType = 7
	PacketType_PACKET_TYPING         PacketType = 10
	PacketType_PACKET_DEVICE_SYNC    PacketType = 11
	PacketType_PACKET_SIGNALING      PacketType = 12 // OP_SIGNALING for WebRTC SDP & ICE candidates
)

type AckStatus int32

const (
	AckStatus_ACK_UNKNOWN          AckStatus = 0
	AckStatus_ACK_SERVER_RECEIVED  AckStatus = 1
	AckStatus_ACK_DEVICE_DELIVERED AckStatus = 2
	AckStatus_ACK_DEVICE_READ      AckStatus = 3
)

type DoubleRatchetHeader struct {
	RatchetPublicKey    []byte `json:"ratchet_public_key"`
	PreviousChainLength uint32 `json:"previous_chain_length"`
	MessageIndex        uint32 `json:"message_index"`
	SessionId           string `json:"session_id"`
	OneTimePrekeyId     int64  `json:"one_time_prekey_id,omitempty"`
	EphemeralPublicKey  []byte `json:"ephemeral_public_key,omitempty"`
}

type EncryptedMessagePayload struct {
	Header          *DoubleRatchetHeader `json:"header,omitempty"`
	AeadNonce       []byte               `json:"aead_nonce,omitempty"`
	ConversationSeq int64  `json:"conversation_seq"`
	Ciphertext      []byte `json:"ciphertext"`
	Nonce           []byte `json:"nonce"`
	AuthTag         []byte `json:"auth_tag"`
}

type DeliveryAckPayload struct {
	OriginalPacketId string    `json:"original_packet_id"`
	Status           AckStatus `json:"status"`
}

type AuthChallengePayload struct {
	ChallengeNonce []byte `json:"challenge_nonce"`
	Timestamp      int64  `json:"timestamp"`
	ExpiresAt      int64  `json:"expires_at"`
}

type AuthResponsePayload struct {
	Signature []byte `json:"signature"`
	PublicKey []byte `json:"public_key"`
	Timestamp int64  `json:"timestamp"`
}

type TypingPayload struct {
	ConversationId string `json:"conversation_id"`
	IsTyping       bool   `json:"is_typing"`
}

type DeviceSyncPayload struct {
	SyncId           string `json:"sync_id"`
	SourceDeviceId   string `json:"source_device_id"`
	SequenceNumber   int64  `json:"sequence_number"`
	EncryptedPayload []byte `json:"encrypted_payload"`
}

type SignalingPayload struct {
	TransferId int64  `json:"transfer_id"`
	Action     string `json:"action"` // "OFFER", "ACCEPT", "DECLINE", "SDP", "ICE", "RESUME", "TIMEOUT_FALLBACK"
	Data       string `json:"data,omitempty"`
}

type NexaWirePacket struct {
	PacketId      string                   `json:"packet_id"`
	Type          PacketType               `json:"type"`
	SenderNxId    string                   `json:"sender_nx_id"`
	RecipientNxId string                   `json:"recipient_nx_id"`
	Timestamp     int64                    `json:"timestamp"`
	Message       *EncryptedMessagePayload `json:"message,omitempty"`
	Ack           *DeliveryAckPayload      `json:"ack,omitempty"`
	AuthChallenge *AuthChallengePayload    `json:"auth_challenge,omitempty"`
	AuthResponse  *AuthResponsePayload     `json:"auth_response,omitempty"`
	Typing        *TypingPayload           `json:"typing,omitempty"`
	DeviceSync    *DeviceSyncPayload       `json:"device_sync,omitempty"`
	Signaling     *SignalingPayload        `json:"signaling,omitempty"`
}

// Encode encodes a NexaWirePacket into standard Protobuf wire binary format.
func (p *NexaWirePacket) Encode() ([]byte, error) {
	buf := new(bytes.Buffer)

	// Field 1: packet_id (string, wire type 2)
	if p.PacketId != "" {
		writeTag(buf, 1, 2)
		writeLengthDelimited(buf, []byte(p.PacketId))
	}

	// Field 2: type (enum, wire type 0)
	if p.Type != PacketType_PACKET_UNKNOWN {
		writeTag(buf, 2, 0)
		writeVarint(buf, uint64(p.Type))
	}

	// Field 3: sender_nx_id (string, wire type 2)
	if p.SenderNxId != "" {
		writeTag(buf, 3, 2)
		writeLengthDelimited(buf, []byte(p.SenderNxId))
	}

	// Field 4: recipient_nx_id (string, wire type 2)
	if p.RecipientNxId != "" {
		writeTag(buf, 4, 2)
		writeLengthDelimited(buf, []byte(p.RecipientNxId))
	}

	// Field 5: timestamp (int64, wire type 0)
	if p.Timestamp != 0 {
		writeTag(buf, 5, 0)
		writeVarint(buf, uint64(p.Timestamp))
	}

	// Field 6: message (EncryptedMessagePayload, wire type 2)
	if p.Message != nil {
		msgBuf := new(bytes.Buffer)
		if p.Message.ConversationSeq != 0 {
			writeTag(msgBuf, 1, 0)
			writeVarint(msgBuf, uint64(p.Message.ConversationSeq))
		}
		if len(p.Message.Ciphertext) > 0 {
			writeTag(msgBuf, 2, 2)
			writeLengthDelimited(msgBuf, p.Message.Ciphertext)
		}
		if len(p.Message.Nonce) > 0 {
			writeTag(msgBuf, 3, 2)
			writeLengthDelimited(msgBuf, p.Message.Nonce)
		}
		if len(p.Message.AuthTag) > 0 {
			writeTag(msgBuf, 4, 2)
			writeLengthDelimited(msgBuf, p.Message.AuthTag)
		}
		if p.Message.Header != nil {
			hBuf := new(bytes.Buffer)
			if len(p.Message.Header.RatchetPublicKey) > 0 {
				writeTag(hBuf, 1, 2)
				writeLengthDelimited(hBuf, p.Message.Header.RatchetPublicKey)
			}
			if p.Message.Header.PreviousChainLength != 0 {
				writeTag(hBuf, 2, 0)
				writeVarint(hBuf, uint64(p.Message.Header.PreviousChainLength))
			}
			if p.Message.Header.MessageIndex != 0 {
				writeTag(hBuf, 3, 0)
				writeVarint(hBuf, uint64(p.Message.Header.MessageIndex))
			}
			if len(p.Message.Header.SessionId) > 0 {
				writeTag(hBuf, 4, 2)
				writeLengthDelimited(hBuf, []byte(p.Message.Header.SessionId))
			}
			if p.Message.Header.OneTimePrekeyId != 0 {
				writeTag(hBuf, 5, 0)
				writeVarint(hBuf, uint64(p.Message.Header.OneTimePrekeyId))
			}
			if len(p.Message.Header.EphemeralPublicKey) > 0 {
				writeTag(hBuf, 6, 2)
				writeLengthDelimited(hBuf, p.Message.Header.EphemeralPublicKey)
			}
			writeTag(msgBuf, 5, 2)
			writeLengthDelimited(msgBuf, hBuf.Bytes())
		}
		if len(p.Message.AeadNonce) > 0 {
			writeTag(msgBuf, 6, 2)
			writeLengthDelimited(msgBuf, p.Message.AeadNonce)
		}

		writeTag(buf, 6, 2)
		writeLengthDelimited(buf, msgBuf.Bytes())
	}

	// Field 7: ack (DeliveryAckPayload, wire type 2)
	if p.Ack != nil {
		ackBuf := new(bytes.Buffer)
		if p.Ack.OriginalPacketId != "" {
			writeTag(ackBuf, 1, 2)
			writeLengthDelimited(ackBuf, []byte(p.Ack.OriginalPacketId))
		}
		if p.Ack.Status != AckStatus_ACK_UNKNOWN {
			writeTag(ackBuf, 2, 0)
			writeVarint(ackBuf, uint64(p.Ack.Status))
		}

		writeTag(buf, 7, 2)
		writeLengthDelimited(buf, ackBuf.Bytes())
	}

	// Field 8: auth_challenge (AuthChallengePayload, wire type 2)
	if p.AuthChallenge != nil {
		acBuf := new(bytes.Buffer)
		if len(p.AuthChallenge.ChallengeNonce) > 0 {
			writeTag(acBuf, 1, 2)
			writeLengthDelimited(acBuf, p.AuthChallenge.ChallengeNonce)
		}
		if p.AuthChallenge.Timestamp != 0 {
			writeTag(acBuf, 2, 0)
			writeVarint(acBuf, uint64(p.AuthChallenge.Timestamp))
		}
		if p.AuthChallenge.ExpiresAt != 0 {
			writeTag(acBuf, 3, 0)
			writeVarint(acBuf, uint64(p.AuthChallenge.ExpiresAt))
		}

		writeTag(buf, 8, 2)
		writeLengthDelimited(buf, acBuf.Bytes())
	}

	// Field 9: auth_response (AuthResponsePayload, wire type 2)
	if p.AuthResponse != nil {
		arBuf := new(bytes.Buffer)
		if len(p.AuthResponse.Signature) > 0 {
			writeTag(arBuf, 1, 2)
			writeLengthDelimited(arBuf, p.AuthResponse.Signature)
		}
		if len(p.AuthResponse.PublicKey) > 0 {
			writeTag(arBuf, 2, 2)
			writeLengthDelimited(arBuf, p.AuthResponse.PublicKey)
		}
		if p.AuthResponse.Timestamp != 0 {
			writeTag(arBuf, 3, 0)
			writeVarint(arBuf, uint64(p.AuthResponse.Timestamp))
		}

		writeTag(buf, 9, 2)
		writeLengthDelimited(buf, arBuf.Bytes())
	}

	// Field 10: typing (TypingPayload, wire type 2)
	if p.Typing != nil {
		tBuf := new(bytes.Buffer)
		if p.Typing.ConversationId != "" {
			writeTag(tBuf, 1, 2)
			writeLengthDelimited(tBuf, []byte(p.Typing.ConversationId))
		}
		if p.Typing.IsTyping {
			writeTag(tBuf, 2, 0)
			writeVarint(tBuf, 1)
		} else {
			writeTag(tBuf, 2, 0)
			writeVarint(tBuf, 0)
		}

		writeTag(buf, 10, 2)
		writeLengthDelimited(buf, tBuf.Bytes())
	}

	// Field 11: device_sync (DeviceSyncPayload, wire type 2)
	if p.DeviceSync != nil {
		dsBuf := new(bytes.Buffer)
		if p.DeviceSync.SyncId != "" {
			writeTag(dsBuf, 1, 2)
			writeLengthDelimited(dsBuf, []byte(p.DeviceSync.SyncId))
		}
		if p.DeviceSync.SourceDeviceId != "" {
			writeTag(dsBuf, 2, 2)
			writeLengthDelimited(dsBuf, []byte(p.DeviceSync.SourceDeviceId))
		}
		if p.DeviceSync.SequenceNumber != 0 {
			writeTag(dsBuf, 3, 0)
			writeVarint(dsBuf, uint64(p.DeviceSync.SequenceNumber))
		}
		if len(p.DeviceSync.EncryptedPayload) > 0 {
			writeTag(dsBuf, 4, 2)
			writeLengthDelimited(dsBuf, p.DeviceSync.EncryptedPayload)
		}

		writeTag(buf, 11, 2)
		writeLengthDelimited(buf, dsBuf.Bytes())
	}

	// Field 12: signaling (SignalingPayload, wire type 2)
	if p.Signaling != nil {
		sBuf := new(bytes.Buffer)
		if p.Signaling.TransferId != 0 {
			writeTag(sBuf, 1, 0)
			writeVarint(sBuf, uint64(p.Signaling.TransferId))
		}
		if p.Signaling.Action != "" {
			writeTag(sBuf, 2, 2)
			writeLengthDelimited(sBuf, []byte(p.Signaling.Action))
		}
		if p.Signaling.Data != "" {
			writeTag(sBuf, 3, 2)
			writeLengthDelimited(sBuf, []byte(p.Signaling.Data))
		}

		writeTag(buf, 12, 2)
		writeLengthDelimited(buf, sBuf.Bytes())
	}

	return buf.Bytes(), nil
}

// Decode decodes a NexaWirePacket from standard Protobuf wire binary format.
func Decode(data []byte) (*NexaWirePacket, error) {
	r := bytes.NewReader(data)
	packet := &NexaWirePacket{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1: // packet_id
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			packet.PacketId = string(val)
		case 2: // type
			val, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			packet.Type = PacketType(val)
		case 3: // sender_nx_id
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			packet.SenderNxId = string(val)
		case 4: // recipient_nx_id
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			packet.RecipientNxId = string(val)
		case 5: // timestamp
			val, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			packet.Timestamp = int64(val)
		case 6: // message
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			msg, err := decodeMessagePayload(val)
			if err != nil {
				return nil, err
			}
			packet.Message = msg
		case 7: // ack
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			ack, err := decodeAckPayload(val)
			if err != nil {
				return nil, err
			}
			packet.Ack = ack
		case 8: // auth_challenge
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			ac, err := decodeAuthChallengePayload(val)
			if err != nil {
				return nil, err
			}
			packet.AuthChallenge = ac
		case 9: // auth_response
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			ar, err := decodeAuthResponsePayload(val)
			if err != nil {
				return nil, err
			}
			packet.AuthResponse = ar
		case 10: // typing
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			typing, err := decodeTypingPayload(val)
			if err != nil {
				return nil, err
			}
			packet.Typing = typing
		case 11: // device_sync
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			ds, err := decodeDeviceSyncPayload(val)
			if err != nil {
				return nil, err
			}
			packet.DeviceSync = ds
		case 12: // signaling
			val, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			sig, err := decodeSignalingPayload(val)
			if err != nil {
				return nil, err
			}
			packet.Signaling = sig
		default:
			// Skip unknown fields according to wire type
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}

	return packet, nil
}

func decodeMessagePayload(data []byte) (*EncryptedMessagePayload, error) {
	r := bytes.NewReader(data)
	payload := &EncryptedMessagePayload{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.ConversationSeq = int64(v)
		case 2:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.Ciphertext = b
		case 3:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.Nonce = b
		case 4:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.AuthTag = b
		case 5:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			h, err := decodeDoubleRatchetHeader(b)
			if err != nil {
				return nil, err
			}
			payload.Header = h
		case 6:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.AeadNonce = b
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return payload, nil
}

func decodeDoubleRatchetHeader(data []byte) (*DoubleRatchetHeader, error) {
	r := bytes.NewReader(data)
	h := &DoubleRatchetHeader{}
	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}
		fieldNum := tag >> 3
		wireType := tag & 0x7
		switch fieldNum {
		case 1:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			h.RatchetPublicKey = b
		case 2:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			h.PreviousChainLength = uint32(v)
		case 3:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			h.MessageIndex = uint32(v)
		case 4:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			h.SessionId = string(b)
		case 5:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			h.OneTimePrekeyId = int64(v)
		case 6:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			h.EphemeralPublicKey = b
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return h, nil
}

func decodeAckPayload(data []byte) (*DeliveryAckPayload, error) {
	r := bytes.NewReader(data)
	payload := &DeliveryAckPayload{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.OriginalPacketId = string(b)
		case 2:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.Status = AckStatus(v)
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return payload, nil
}

func decodeAuthChallengePayload(data []byte) (*AuthChallengePayload, error) {
	r := bytes.NewReader(data)
	payload := &AuthChallengePayload{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.ChallengeNonce = b
		case 2:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.Timestamp = int64(v)
		case 3:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.ExpiresAt = int64(v)
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return payload, nil
}

func decodeAuthResponsePayload(data []byte) (*AuthResponsePayload, error) {
	r := bytes.NewReader(data)
	payload := &AuthResponsePayload{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.Signature = b
		case 2:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.PublicKey = b
		case 3:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.Timestamp = int64(v)
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return payload, nil
}

func decodeTypingPayload(data []byte) (*TypingPayload, error) {
	r := bytes.NewReader(data)
	payload := &TypingPayload{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.ConversationId = string(b)
		case 2:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.IsTyping = v != 0
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return payload, nil
}

func decodeDeviceSyncPayload(data []byte) (*DeviceSyncPayload, error) {
	r := bytes.NewReader(data)
	payload := &DeviceSyncPayload{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.SyncId = string(b)
		case 2:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.SourceDeviceId = string(b)
		case 3:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.SequenceNumber = int64(v)
		case 4:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.EncryptedPayload = b
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return payload, nil
}

func decodeSignalingPayload(data []byte) (*SignalingPayload, error) {
	r := bytes.NewReader(data)
	payload := &SignalingPayload{}

	for {
		tag, err := binary.ReadUvarint(r)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, err
		}

		fieldNum := tag >> 3
		wireType := tag & 0x7

		switch fieldNum {
		case 1:
			v, err := binary.ReadUvarint(r)
			if err != nil {
				return nil, err
			}
			payload.TransferId = int64(v)
		case 2:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.Action = string(b)
		case 3:
			b, err := readLengthDelimited(r)
			if err != nil {
				return nil, err
			}
			payload.Data = string(b)
		default:
			if err := skipField(r, wireType); err != nil {
				return nil, err
			}
		}
	}
	return payload, nil
}

func writeTag(w io.Writer, fieldNum int, wireType int) {
	tag := uint64((fieldNum << 3) | (wireType & 0x7))
	writeVarint(w, tag)
}

func writeVarint(w io.Writer, v uint64) {
	var buf [10]byte
	n := binary.PutUvarint(buf[:], v)
	w.Write(buf[:n])
}

func writeLengthDelimited(w io.Writer, data []byte) {
	writeVarint(w, uint64(len(data)))
	w.Write(data)
}

func readLengthDelimited(r io.Reader) ([]byte, error) {
	length, err := binary.ReadUvarint(r.(io.ByteReader))
	if err != nil {
		return nil, err
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func skipField(r io.Reader, wireType uint64) error {
	switch wireType {
	case 0: // Varint
		_, err := binary.ReadUvarint(r.(io.ByteReader))
		return err
	case 1: // 64-bit
		var b [8]byte
		_, err := io.ReadFull(r, b[:])
		return err
	case 2: // Length-delimited
		length, err := binary.ReadUvarint(r.(io.ByteReader))
		if err != nil {
			return err
		}
		_, err = io.CopyN(io.Discard, r, int64(length))
		return err
	case 5: // 32-bit
		var b [4]byte
		_, err := io.ReadFull(r, b[:])
		return err
	default:
		return errors.New("unsupported wire type")
	}
}
