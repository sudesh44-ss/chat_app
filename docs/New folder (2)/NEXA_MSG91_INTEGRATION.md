# NEXA MSG91 Production SMS Integration Documentation

**Component:** `backend/internal/auth/sms_provider.go` (`Msg91Provider`)  
**Specification:** Official MSG91 Send OTP v5 API  
**Target Region:** India (+91 Telecom MSISDN)  
**Status:** **IMPLEMENTED & VERIFIED**  

---

## 1. MSG91 Architecture & Endpoint Mapping

- **API Endpoint:** `https://api.msg91.com/api/v5/otp`
- **HTTP Method:** `POST`
- **Authentication:** `authkey` header passed in HTTP request.
- **Parameters:**
  - `template_id`: Approved TRAI DLT Template ID.
  - `mobile`: Clean E.164 phone number without `+` prefix (e.g. `919876543210`).
  - `otp`: Cryptographically generated 6-digit uniform numeric OTP.
- **Payload / Query:** Parameters encoded in query string and credentials passed in header.

---

## 2. Response Parsing & Delivery Acceptance

MSG91 returns JSON responses in this format:
```json
{
  "message": "OTP sent successfully",
  "type": "success"
}
```
Or upon rejection (e.g. invalid template ID, expired balance, unapproved header):
```json
{
  "message": "Authentication failed: Auth key is invalid",
  "type": "error"
}
```

### Safety Features in Code:
1. **Type Check:** Even if MSG91 returns HTTP 200, `Msg91Provider` parses the body and verifies that `type != "error"`. If `type == "error"`, it treats the response as a failure and rolls back the OTP state.
2. **Credential Redaction:** The `authkey` is never logged, printed, or included in error traces.
3. **Phone Masking:** Logs output `+91****43210` to comply with zero-knowledge privacy standards.

---

## 3. Automated Mock Unit Tests

The integration was tested using in-memory mock transports:
1. `TestMsg91Adapter_Success`: Verifies parameter formatting, headers, and successful dispatch parsing.
2. `TestMsg91Adapter_Rejection`: Verifies that provider error bodies are caught and surfaced as errors.
3. `TestMsg91Adapter_Timeout`: Verifies that HTTP timeouts fail gracefully without crashing or hanging.
