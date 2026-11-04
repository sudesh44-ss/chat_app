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
    private val E164_REGEX = Regex("""^\+[1-9]\d{7,14}$""")

    /**
     * Normalizes a phone number into strict E.164 international format.
     * Handles 10-digit national numbers by applying +91 country context when appropriate,
     * strips formatting characters, spaces, and hyphens.
     */
    fun normalizePhoneNumber(rawNumber: String): String? {
        val trimmed = rawNumber.trim()
        val cleaned = trimmed
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
            .replace(".", "")

        val formatted = when {
            cleaned.startsWith("+") -> cleaned
            cleaned.startsWith("00") -> "+" + cleaned.removePrefix("00")
            cleaned.length == 10 && (cleaned[0] in '6'..'9') -> "+91$cleaned"
            cleaned.startsWith("0") && cleaned.length == 11 -> "+91" + cleaned.removePrefix("0")
            else -> "+$cleaned"
        }

        return if (formatted.matches(E164_REGEX)) {
            formatted
        } else {
            null
        }
    }

    /**
     * Queries Android Contacts Provider locally via ContentResolver.
     * Strict Privacy: Only operates in local memory. Never uploads address-book lists.
     */
    suspend fun readDeviceContacts(context: Context): List<DeviceContact> = withContext(Dispatchers.IO) {
        val contactsList = mutableListOf<DeviceContact>()
        val seenNumbers = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"

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
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (c.moveToNext()) {
                    val rawNumber = if (numberIdx != -1) c.getString(numberIdx) ?: "" else ""
                    val normalized = normalizePhoneNumber(rawNumber) ?: continue

                    // Deduplicate identical normalized numbers
                    if (!seenNumbers.add(normalized)) {
                        continue
                    }

                    val id = if (idIdx != -1) c.getString(idIdx) else ""
                    val contactId = if (contactIdIdx != -1) c.getString(contactIdIdx) else id
                    val name = if (nameIdx != -1) c.getString(nameIdx) ?: normalized else normalized
                    val photoUri = if (photoIdx != -1) c.getString(photoIdx) else null

                    contactsList.add(
                        DeviceContact(
                            id = contactId,
                            displayName = name.ifBlank { normalized },
                            phoneNumber = rawNumber,
                            normalizedNumber = normalized,
                            avatarUri = photoUri
                        )
                    )
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
