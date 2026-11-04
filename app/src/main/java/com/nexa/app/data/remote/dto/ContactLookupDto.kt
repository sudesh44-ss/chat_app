package com.nexa.app.data.remote.dto

data class ContactMatchDto(
    val phoneNumber: String,
    val nxId: String,
    val displayName: String,
    val publicKeyHex: String
)

data class ContactLookupRequest(
    val phoneNumbers: List<String>
)

data class ContactLookupResponse(
    val matches: List<ContactMatchDto>
)
