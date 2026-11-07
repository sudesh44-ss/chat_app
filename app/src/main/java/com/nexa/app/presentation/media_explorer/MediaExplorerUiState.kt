package com.nexa.app.presentation.media_explorer

import com.nexa.app.data.local.entity.TransferEntity

enum class MediaCategory(val label: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    DOCUMENTS("Documents"),
    OTHER_FILES("Other Files")
}

data class MediaExplorerUiState(
    val conversationId: String = "",
    val participantName: String = "",
    val selectedCategory: MediaCategory = MediaCategory.IMAGES,
    val images: List<TransferEntity> = emptyList(),
    val videos: List<TransferEntity> = emptyList(),
    val documents: List<TransferEntity> = emptyList(),
    val otherFiles: List<TransferEntity> = emptyList(),
    val activeViewerImage: Any? = null,
    val isLoading: Boolean = false
)
