package auth

import (
	"crypto/hmac"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base64"
	"sort"
	"strings"
)

// ValidateTwilioSignature validates an inbound Twilio webhook request using HMAC-SHA1
// per Twilio's official webhook signing specification.
//
// Twilio algorithm:
// 1. Take the full request URL as invoked by Twilio (including protocol and query string, if any).
// 2. Sort all POST parameters alphabetically by parameter key.
// 3. Append each key and its corresponding value to the base URL string with no delimiters.
// 4. Compute HMAC-SHA1 using the Twilio Auth Token as secret.
// 5. Base64-encode the resulting digest.
// 6. Compare with X-Twilio-Signature in constant time.
func ValidateTwilioSignature(authToken string, requestURL string, params map[string]string, signature string) bool {
	if strings.TrimSpace(authToken) == "" || strings.TrimSpace(signature) == "" || strings.TrimSpace(requestURL) == "" {
		return false
	}

	// 1. Sort parameter keys alphabetically
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	// 2. Build string: URL + Key1 + Value1 + Key2 + Value2 ...
	var sb strings.Builder
	sb.WriteString(requestURL)
	for _, k := range keys {
		sb.WriteString(k)
		sb.WriteString(params[k])
	}

	// 3. HMAC-SHA1 calculation
	mac := hmac.New(sha1.New, []byte(authToken))
	mac.Write([]byte(sb.String()))
	expectedSig := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	// 4. Constant-time signature comparison (protects against timing attacks)
	return subtle.ConstantTimeCompare([]byte(expectedSig), []byte(signature)) == 1
}
