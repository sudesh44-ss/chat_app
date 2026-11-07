package com.nexa.app.core.contacts

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import com.nexa.app.domain.model.DeviceContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ContactsProviderHelper {

    private const val TAG = "ContactsProviderHelper"
    private val E164_REGEX = Regex("""^\+[1-9]\d{6,14}$""")

    /**
     * Normalizes a phone number safely for search, display, and comparison.
     * Strips formatting artifacts (spaces, hyphens, brackets, dots).
     * Returns null for invalid or unparseable numbers (less than 7 digits, empty, or non-numeric).
     */
    fun normalizePhoneNumber(rawNumber: String): String? {
        val trimmed = rawNumber.trim()
        if (trimmed.isBlank()) return null

        val hasLeadingPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }

        // Must have at least 7 digits (ITU-T E.164 minimum local subscriber number) and at most 15 digits
        if (digitsOnly.length < 7 || digitsOnly.length > 15) return null

        val formatted = when {
            hasLeadingPlus -> "+$digitsOnly"
            digitsOnly.startsWith("00") -> "+" + digitsOnly.removePrefix("00")
            digitsOnly.length == 10 && (digitsOnly[0] in '6'..'9') -> "+91$digitsOnly"
            digitsOnly.length == 11 && digitsOnly.startsWith("0") && (digitsOnly[1] in '6'..'9') -> "+91" + digitsOnly.removePrefix("0")
            digitsOnly.length == 11 && digitsOnly.startsWith("1") -> "+$digitsOnly"
            else -> if (hasLeadingPlus) "+$digitsOnly" else "+$digitsOnly"
        }

        return if (formatted.matches(E164_REGEX)) {
            formatted
        } else {
            "+$digitsOnly"
        }
    }

    /**
     * Queries Android Contacts Provider locally via ContentResolver.
     * Strict Privacy: Reads local address-book entries purely in local memory.
     * Never transmits or uploads raw address books to any external server.
     */
    suspend fun readDeviceContacts(context: Context): List<DeviceContact> = withContext(Dispatchers.IO) {
        val contactsList = mutableListOf<DeviceContact>()
        val seenPairs = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        // Case-insensitive alphabetical sort
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
                val contactIdIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val lookupKeyIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (c.moveToNext()) {
                    try {
                        val rawNumber = if (numberIdx != -1) c.getString(numberIdx)?.trim().orEmpty() else ""
                        val normalized = normalizePhoneNumber(rawNumber) ?: rawNumber.filter { it.isDigit() || it == '+' }

                        val id = if (idIdx != -1) c.getString(idIdx).orEmpty() else ""
                        val contactId = if (contactIdIdx != -1) c.getString(contactIdIdx) ?: id else id
                        val lookupKey = if (lookupKeyIdx != -1) c.getString(lookupKeyIdx).orEmpty() else ""
                        val effectiveId = if (lookupKey.isNotBlank()) lookupKey else contactId

                        // Deduplicate identical contact + number entries
                        val dedupeKey = "${effectiveId}_$normalized"
                        if (normalized.isNotBlank() && !seenPairs.add(dedupeKey)) {
                            continue
                        }

                        val rawName = if (nameIdx != -1) c.getString(nameIdx) else null
                        val displayName = when {
                            !rawName.isNullOrBlank() -> rawName.trim()
                            rawNumber.isNotBlank() -> rawNumber
                            else -> "Unknown Contact"
                        }

                        val photoUri = if (photoIdx != -1) c.getString(photoIdx) else null

                        contactsList.add(
                            DeviceContact(
                                id = effectiveId.ifBlank { id },
                                displayName = displayName,
                                phoneNumber = rawNumber,
                                normalizedNumber = normalized,
                                avatarUri = photoUri
                            )
                        )
                    } catch (rowEx: Exception) {
                        Log.w(TAG, "Skipping malformed or concurrently deleted contact row: ${rowEx.message}")
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission not granted or revoked during query", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Android ContactsProvider", e)
        }

        return@withContext contactsList
    }

    /**
     * Debounced Android ContentObserver for real-time address-book change notifications.
     */
    class ContactsChangeObserver(
        private val coroutineScope: CoroutineScope,
        private val onChangeDebounced: () -> Unit
    ) : ContentObserver(Handler(Looper.getMainLooper())) {

        private var debounceJob: Job? = null

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            debounceJob?.cancel()
            debounceJob = coroutineScope.launch {
                delay(500) // 500ms debounce
                onChangeDebounced()
            }
        }
    }
}
