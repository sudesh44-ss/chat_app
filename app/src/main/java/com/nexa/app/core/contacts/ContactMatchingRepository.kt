package com.nexa.app.core.contacts

import android.content.Context
import android.provider.ContactsContract
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local-First Contact Matching & Phone Privacy Engine for NEXA.
 * Enforces Zero-Leakage:
 * 1. Checks peer phone against local device contacts strictly in-memory.
 * 2. If peer is not in local contacts and hasn't explicitly granted phone sharing:
 *    The phone number remains hidden/null in the local encrypted database.
 */
data class DeviceContactMetadata(
    val displayName: String,
    val photoUri: String? = null
)

class ContactMatchingRepository(private val context: Context) {

    /**
     * Extracts a map of normalized E.164 phone numbers to contact display names
     * from the local Android device address book.
     * Operates strictly on-device via ContentResolver (Zero Cloud Upload).
     */
    suspend fun getDeviceContactsMap(): Map<String, String> = withContext(Dispatchers.IO) {
        getDeviceContactDetailsMap().mapValues { it.value.displayName }
    }

    /**
     * Extracts comprehensive metadata (name and photo URI) from the device address book.
     */
    suspend fun getDeviceContactDetailsMap(): Map<String, DeviceContactMetadata> = withContext(Dispatchers.IO) {
        val contactsMap = mutableMapOf<String, DeviceContactMetadata>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val photoIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (cursor.moveToNext()) {
                    val rawNumber = cursor.getString(numberIndex)
                    val rawName = if (nameIndex != -1) cursor.getString(nameIndex) else null
                    val photoUri = if (photoIndex != -1) cursor.getString(photoIndex) else null

                    if (!rawNumber.isNullOrBlank()) {
                        ContactsProviderHelper.normalizePhoneNumber(rawNumber)?.let { normalized ->
                            val effectiveName = if (!rawName.isNullOrBlank()) rawName.trim() else normalized
                            contactsMap.putIfAbsent(normalized, DeviceContactMetadata(effectiveName, photoUri))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Permission not granted or query failure
        }
        contactsMap
    }

    /**
     * Extracts normalized E.164 phone numbers from the local Android device address book.
     * Operates strictly on-device via ContentResolver (Zero Cloud Upload).
     */
    suspend fun getDevicePhoneNumbers(): Set<String> = withContext(Dispatchers.IO) {
        getDeviceContactsMap().keys
    }

    /**
     * Looks up the address-book contact name for a given phone number.
     */
    suspend fun getDeviceContactName(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val normalized = ContactsProviderHelper.normalizePhoneNumber(phone) ?: return null
        return getDeviceContactDetailsMap()[normalized]?.displayName
    }

    /**
     * Looks up the address-book contact photo URI for a given phone number.
     */
    suspend fun getDeviceContactPhotoUri(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val normalized = ContactsProviderHelper.normalizePhoneNumber(phone) ?: return null
        return getDeviceContactDetailsMap()[normalized]?.photoUri
    }

    /**
     * Checks if a target phone number exists in the device's local address book.
     */
    suspend fun isPhoneInLocalAddressBook(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        val normalized = ContactsProviderHelper.normalizePhoneNumber(phone) ?: return false
        return getDeviceContactDetailsMap().containsKey(normalized)
    }

    /**
     * Constructs a privacy-evaluated ContactEntity based on local contact matching,
     * explicit peer visibility grants, and 3-Tier Identity Hierarchy.
     */
    suspend fun resolveContactPrivacy(
        peerNxId: String,
        peerPhone: String?,
        serverDisplayName: String,
        publicKeyHex: String = "",
        isSharedByPeer: Boolean = false,
        customNickname: String? = null,
        deviceContactName: String? = null,
        connectionSource: String = "QR",
        nexaAvatarUrl: String? = null,
        deviceContactPhotoUri: String? = null
    ): ContactEntity {
        val detailsMap = getDeviceContactDetailsMap()
        val normalized = peerPhone?.let { ContactsProviderHelper.normalizePhoneNumber(it) }
        val isLocal = normalized != null && detailsMap.containsKey(normalized)
        val shouldStorePhone = isLocal || isSharedByPeer
        val localDetails = normalized?.let { detailsMap[it] }
        val resolvedDeviceContactName = deviceContactName ?: localDetails?.displayName
        val resolvedPhotoUri = deviceContactPhotoUri ?: localDetails?.photoUri

        return ContactEntity(
            nxId = peerNxId,
            displayName = serverDisplayName,
            publicKeyHex = publicKeyHex,
            introMessage = null,
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = customNickname,
            deviceContactName = resolvedDeviceContactName,
            phoneNumber = if (shouldStorePhone) peerPhone else null,
            isPhoneInLocalAddressBook = isLocal,
            isPhoneSharedByPeer = isSharedByPeer,
            connectionSource = connectionSource,
            deviceContactPhotoUri = resolvedPhotoUri,
            nexaAvatarUrl = nexaAvatarUrl
        )
    }
}
