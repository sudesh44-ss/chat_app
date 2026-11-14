package com.nexa.app.presentation.settings.sound

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.nexa.app.core.sound.AudioFileValidator
import com.nexa.app.core.sound.AudioValidationResult
import com.nexa.app.core.sound.NexaSoundManager
import com.nexa.app.data.local.preferences.SoundCategory
import com.nexa.app.data.local.preferences.SoundConfig
import com.nexa.app.data.local.preferences.SoundMode
import com.nexa.app.data.local.preferences.SoundPreferences
import kotlinx.coroutines.flow.StateFlow

class SoundSettingsViewModel(
    private val soundPreferences: SoundPreferences,
    private val soundManager: NexaSoundManager
) : ViewModel() {

    /** Reflects all 12 category configs as they change. */
    val preferencesState: StateFlow<Map<SoundCategory, SoundConfig>> =
        soundPreferences.preferencesState

    /** The category whose preview is currently playing, or null. */
    val previewingCategory: StateFlow<SoundCategory?> =
        soundManager.previewingCategory

    /** Starts (or restarts) preview for the given category. */
    fun previewSound(category: SoundCategory) {
        soundManager.previewSound(category)
    }

    /** Stops any active preview. */
    fun stopPreview() {
        soundManager.stopPreview()
    }

    /**
     * Changes the mode for one category and persists the updated config.
     * When switching away from LOCAL, the cached URI and display name are preserved so the
     * user can switch back without re-selecting the file.
     */
    fun setMode(category: SoundCategory, mode: SoundMode) {
        val current = soundPreferences.getConfig(category)
        val updated = current.copy(
            mode = mode,
            updatedAt = System.currentTimeMillis()
        )
        soundPreferences.updateConfig(updated)
    }

    /** Resets one category back to the app default sound. */
    fun resetToDefault(category: SoundCategory) {
        soundPreferences.resetToDefault(category)
    }

    /**
     * Validates the user-selected audio URI, caches it on-device, and—on success—
     * switches the category to LOCAL mode and stores the cached URI + display name.
     *
     * This runs synchronously on the caller's coroutine / thread; callers should wrap
     * it in a coroutine if they want it off the main thread.
     */
    fun processSelectedAudioUri(
        context: Context,
        uri: Uri,
        category: SoundCategory
    ): AudioValidationResult {
        val result = AudioFileValidator.validateAndCacheAudio(context, uri, category)
        if (result is AudioValidationResult.Success) {
            val updatedConfig = SoundConfig(
                category = category,
                mode = SoundMode.LOCAL,
                localUri = result.cachedUri,
                displayName = result.displayName,
                updatedAt = System.currentTimeMillis()
            )
            soundPreferences.updateConfig(updatedConfig)
        }
        return result
    }

    override fun onCleared() {
        super.onCleared()
        soundManager.stopPreview()
    }
}
