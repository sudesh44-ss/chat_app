package com.nexa.app.presentation.search.model

/**
 * Advanced Search Filters in exact mandatory order:
 * 1. Unread
 * 2. Audio
 * 3. Favorite
 * 4. Archived
 * 5. Groups
 * 6. Contacts
 * 7. Photos
 * 8. Documents
 * 9. Videos
 * 10. GIFs
 * 11. Links
 * 12. Non-contacts
 */
enum class SearchFilter(val displayName: String) {
    UNREAD("Unread"),
    AUDIO("Audio"),
    FAVORITE("Favorite"),
    ARCHIVED("Archived"),
    GROUPS("Groups"),
    CONTACTS("Contacts"),
    PHOTOS("Photos"),
    DOCUMENTS("Documents"),
    VIDEOS("Videos"),
    GIFS("GIFs"),
    LINKS("Links"),
    NON_CONTACTS("Non-contacts")
}
