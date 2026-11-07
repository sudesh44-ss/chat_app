package com.nexa.app.presentation.media_explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.data.local.dao.FileTransferDao
import com.nexa.app.data.local.entity.TransferEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MediaExplorerViewModel(
    private val conversationId: String,
    private val participantName: String,
    private val fileTransferDao: FileTransferDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MediaExplorerUiState(
            conversationId = conversationId,
            participantName = participantName
        )
    )
    val uiState: StateFlow<MediaExplorerUiState> = _uiState.asStateFlow()

    init {
        observeConversationTransfers()
    }

    private fun observeConversationTransfers() {
        viewModelScope.launch {
            fileTransferDao.observeTransfersForConversation(conversationId).collect { transfers ->
                val images = mutableListOf<TransferEntity>()
                val videos = mutableListOf<TransferEntity>()
                val documents = mutableListOf<TransferEntity>()
                val others = mutableListOf<TransferEntity>()

                for (tr in transfers) {
                    val ext = tr.fileName.substringAfterLast('.', "").lowercase()
                    when (ext) {
                        "jpg", "jpeg", "png", "webp", "gif", "heic" -> images.add(tr)
                        "mp4", "mkv", "webm", "avi", "mov", "3gp" -> videos.add(tr)
                        "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv" -> documents.add(tr)
                        else -> others.add(tr)
                    }
                }

                _uiState.update { current ->
                    current.copy(
                        images = images,
                        videos = videos,
                        documents = documents,
                        otherFiles = others,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun selectCategory(category: MediaCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setActiveViewerImage(imageModel: Any?) {
        _uiState.update { it.copy(activeViewerImage = imageModel) }
    }
}
