package com.nexa.app.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.nexa.app.data.local.preferences.SoundCategory
import com.nexa.app.data.local.preferences.SoundConfig
import com.nexa.app.data.local.preferences.SoundMode
import com.nexa.app.data.local.preferences.SoundPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Production Centralized Sound Manager for NEXA.
 * Controls preview playback, event audio alerts, call ringtone looping,
 * deduplication cooldowns, audio focus management, and safe resource cleanup.
 */
class NexaSoundManager(
    private val context: Context,
    private val soundPreferences: SoundPreferences
) {
    companion object {
        private const val TAG = "NexaSoundManager"
        private const val NEARBY_COOLDOWN_MS = 30_000L // 30s cooldown for nearby node discovery
        private const val GENERIC_DEDUP_COOLDOWN_MS = 5_000L // 5s deduplication for connection requests / mesh
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(Dispatchers.Main)

    // State for UI to observe which category is currently being previewed
    private val _previewingCategory = MutableStateFlow<SoundCategory?>(null)
    val previewingCategory: StateFlow<SoundCategory?> = _previewingCategory.asStateFlow()

    // Active media player instances
    private var previewPlayer: MediaPlayer? = null
    private var ringtonePlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Deduplication tracker: eventKey -> lastTimestamp
    private val eventDeduplicationMap = ConcurrentHashMap<String, Long>()

    // Audio Focus Change Listener
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently. Stopping playback.")
                stopPreview()
                stopCallRingtone()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost transiently. Pausing ringtone.")
                try {
                    if (ringtonePlayer?.isPlaying == true) {
                        ringtonePlayer?.pause()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error pausing ringtone: ${e.message}")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus regained.")
                try {
                    ringtonePlayer?.start()
                } catch (e: Exception) {
                    Log.w(TAG, "Error resuming ringtone: ${e.message}")
                }
            }
        }
    }

    // ==========================================
    // 1. Preview Playback System
    // ==========================================

    /**
     * Previews a sound category or specific configuration.
     * Stops any existing preview and requests transient audio focus.
     */
    @Synchronized
    fun previewSound(
        category: SoundCategory,
        customConfig: SoundConfig? = null,
        onFinished: () -> Unit = {}
    ) {
        stopPreview()

        val config = customConfig ?: soundPreferences.getConfig(category)
        if (config.mode == SoundMode.SILENT) {
            Log.d(TAG, "Category $category is set to SILENT. Skipping preview.")
            _previewingCategory.value = null
            onFinished()
            return
        }

        val soundUri = resolveSoundUri(config)
        if (soundUri == null) {
            Log.w(TAG, "Could not resolve sound URI for $category")
            _previewingCategory.value = null
            onFinished()
            return
        }

        requestAudioFocus(isRingtone = (category == SoundCategory.CALL_RINGTONE))

        try {
            val player = MediaPlayer().apply {
                val usage = if (category == SoundCategory.CALL_RINGTONE) {
                    AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                } else {
                    AudioAttributes.USAGE_NOTIFICATION
                }
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(usage)
                        .build()
                )
                setDataSource(context, soundUri)
                prepare()
                setOnCompletionListener {
                    Log.d(TAG, "Preview completed for $category")
                    releasePreviewPlayer()
                    _previewingCategory.value = null
                    onFinished()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer preview error: what=$what, extra=$extra")
                    releasePreviewPlayer()
                    _previewingCategory.value = null
                    onFinished()
                    true
                }
                start()
            }
            previewPlayer = player
            _previewingCategory.value = category
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview for $category", e)
            releasePreviewPlayer()
            _previewingCategory.value = null
            onFinished()
        }
    }

    /**
     * Stops and releases active preview player.
     */
    @Synchronized
    fun stopPreview() {
        releasePreviewPlayer()
        _previewingCategory.value = null
    }

    private fun releasePreviewPlayer() {
        try {
            previewPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing preview player: ${e.message}")
        } finally {
            previewPlayer = null
            abandonAudioFocus()
        }
    }

    // ==========================================
    // 2. Call Ringtone Subsystem
    // ==========================================

    /**
     * Starts looping call ringtone for incoming calls.
     */
    @Synchronized
    fun startCallRingtone() {
        stopCallRingtone()
        stopPreview()

        val config = soundPreferences.getConfig(SoundCategory.CALL_RINGTONE)
        if (config.mode == SoundMode.SILENT) {
            Log.d(TAG, "Call ringtone is SILENT. No audio will play.")
            return
        }

        val ringtoneUri = resolveSoundUri(config) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        requestAudioFocus(isRingtone = true)

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                )
                setDataSource(context, ringtoneUri)
                isLooping = true
                prepare()
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Ringtone player error: what=$what, extra=$extra")
                    stopCallRingtone()
                    true
                }
                start()
            }
            ringtonePlayer = player
            Log.d(TAG, "Call ringtone started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call ringtone", e)
            stopCallRingtone()
        }
    }

    /**
     * Stops call ringtone playback and releases resources.
     * Invoked when call is answered, rejected, cancelled, or timed out.
     */
    @Synchronized
    fun stopCallRingtone() {
        try {
            ringtonePlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ringtone player: ${e.message}")
        } finally {
            ringtonePlayer = null
            abandonAudioFocus()
            Log.d(TAG, "Call ringtone stopped and audio focus released")
        }
    }

    // ==========================================
    // 3. Event Sound Playback (Lightweight One-Shot)
    // ==========================================

    /**
     * Plays the configured sound for an application event with deduplication.
     */
    fun playEventSound(category: SoundCategory, eventKey: String? = null) {
        val now = System.currentTimeMillis()

        // Deduplication & cooldown check
        if (eventKey != null) {
            val cooldown = when (category) {
                SoundCategory.NEARBY_DEVICE -> NEARBY_COOLDOWN_MS
                SoundCategory.CONNECTION_REQUEST,
                SoundCategory.MESH_MESSAGE -> GENERIC_DEDUP_COOLDOWN_MS
                SoundCategory.TRANSFER_COMPLETED,
                SoundCategory.TRANSFER_FAILED,
                SoundCategory.MESH_TRANSFER_COMPLETED -> Long.MAX_VALUE // Play once per unique transfer ID
                else -> 1000L
            }

            val lastTime = eventDeduplicationMap[eventKey] ?: 0L
            if (now - lastTime < cooldown) {
                Log.d(TAG, "Suppressed duplicate sound for $category (key=$eventKey)")
                return
            }
            eventDeduplicationMap[eventKey] = now
        }

        val config = soundPreferences.getConfig(category)
        if (config.mode == SoundMode.SILENT) {
            return
        }

        val soundUri = resolveSoundUri(config) ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                    setDataSource(context, soundUri)
                    prepare()
                    setOnCompletionListener {
                        try {
                            it.reset()
                            it.release()
                        } catch (_: Exception) {}
                    }
                    setOnErrorListener { mp, _, _ ->
                        try {
                            mp.reset()
                            mp.release()
                        } catch (_: Exception) {}
                        true
                    }
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play event sound for $category: ${e.message}")
            }
        }
    }

    // ==========================================
    // 4. URI Resolution & Missing-File Fallback
    // ==========================================

    /**
     * Resolves the actual Uri to play.
     * Safely falls back to system default sound if a local file was moved, deleted, or unreadable.
     */
    fun resolveSoundUri(config: SoundConfig): Uri? {
        if (config.mode == SoundMode.SILENT) return null

        if (config.mode == SoundMode.LOCAL && !config.localUri.isNullOrBlank()) {
            val uri = Uri.parse(config.localUri)
            val scheme = uri.scheme
            if (scheme == "file") {
                val path = uri.path
                if (path != null && File(path).exists()) {
                    return uri
                } else {
                    Log.w(TAG, "Local sound file missing at $path. Falling back to default.")
                }
            } else if (scheme == "content") {
                try {
                    context.contentResolver.openInputStream(uri)?.close()
                    return uri
                } catch (e: Exception) {
                    Log.w(TAG, "Local content URI inaccessible: $uri. Falling back to default.")
                }
            }
        }

        // Default Sound Fallback
        return if (config.category == SoundCategory.CALL_RINGTONE) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    // ==========================================
    // 5. Audio Focus Management
    // ==========================================

    private fun requestAudioFocus(isRingtone: Boolean) {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusGain = if (isRingtone) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            val usage = if (isRingtone) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION
            val req = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_NOTIFICATION,
                if (isRingtone) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
        }
    }

    /**
     * Releases all media players on application teardown.
     */
    fun releaseAll() {
        stopPreview()
        stopCallRingtone()
        eventDeduplicationMap.clear()
    }
}
