package com.novelfa.reader.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Playback state reported to the reader UI (and the service notification). */
enum class TtsState { IDLE, PLAYING, PAUSED, ERROR }

/**
 * Why playback failed. The reader shows one honest message per case
 * (see the tts_* strings) and offers nothing fake: no engine means no
 * engine, missing voice data means the system installer, an
 * unsupported language means this book cannot be read aloud.
 */
enum class TtsError {
    NO_ENGINE, MISSING_DATA, LANGUAGE_NOT_SUPPORTED, INIT_FAILED, SPEAK_FAILED
}

/** Callbacks into the owning service; always delivered on the main thread. */
interface TtsPlayerListener {
    fun onStateChanged(state: TtsState)
    /** The queued chunks for the current chapter are all spoken. */
    fun onChapterFinished()
    fun onError(error: TtsError)
}

/**
 * The TextToSpeech engine wrapper, owned by [TtsService] (never by the
 * UI: the engine must survive the reader's composition and the screen
 * turning off). One [start] call queues one chapter's chunks from
 * [TtsChunker]; [onChapterFinished] fires when the last chunk ends and
 * the service asks the reader for the next chapter.
 *
 * Audio focus: a transient loss (a notification, a call) pauses; a
 * permanent loss stops. A headset being unplugged (ACTION_AUDIO_
 * BECOMING_NOISY) pauses instead of blaring from the speaker. The
 * sleep timer stops playback when it fires; it is session-only and is
 * cleared on every [start]/[stop].
 */
class TtsPlayer(
    context: Context,
    private val listener: TtsPlayerListener
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var engineFailed = false
    private var pendingStart: PendingStart? = null
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var locale: Locale = Locale.US
    private var speed: Float = TtsSpeed.DEFAULT
    private var pausedByFocusLoss = false
    private var sleepJob: Job? = null

    /** The state the UI/notification shows; only ever set via [setState]. */
    var state: TtsState = TtsState.IDLE
        private set

    private data class PendingStart(
        val chunks: List<String>,
        val locale: Locale,
        val speed: Float
    )

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (state == TtsState.PLAYING) {
                    pausedByFocusLoss = true
                    pauseInternal()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss && state == TtsState.PAUSED) {
                    pausedByFocusLoss = false
                    resumeInternal()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocusLoss = false
                stopInternal()
            }
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                if (state != TtsState.PLAYING) return@post
                // The player knows chunks, not chapters: the
                // chapter-advance decision (next chapter vs book
                // end) lives in TtsPlaybackPlan and is made by the
                // service/reader, which know the real chapter count.
                if (chunkIndex + 1 < chunks.size) {
                    chunkIndex++
                    speakCurrent()
                } else {
                    stopInternal()
                    listener.onChapterFinished()
                }
            }
        }

        override fun onError(utteranceId: String?) {
            mainHandler.post {
                if (state == TtsState.IDLE) return@post
                stopInternal()
                setState(TtsState.ERROR)
                listener.onError(TtsError.SPEAK_FAILED)
            }
        }
    }

    /**
     * Reads [newChunks] aloud in [newLocale] at [newSpeed]. A call while
     * the engine is still initialising is remembered and starts when
     * onInit fires; a call while already playing replaces the queue
     * (chapter advance). Empty chunks are a no-op for the caller to
     * report (there is no text to read) — nothing is spoken.
     */
    fun start(newChunks: List<String>, newLocale: Locale, newSpeed: Float) {
        if (newChunks.isEmpty()) return
        chunks = newChunks
        chunkIndex = 0
        locale = newLocale
        speed = TtsSpeed.clamp(newSpeed)
        sleepJob?.cancel()
        sleepJob = null
        if (engineFailed) {
            setState(TtsState.ERROR)
            listener.onError(TtsError.INIT_FAILED)
            return
        }
        if (!engineReady) {
            pendingStart = PendingStart(newChunks, newLocale, speed)
            ensureEngine()
            return
        }
        beginSpeaking()
    }

    fun pause() {
        if (state == TtsState.PLAYING) {
            pausedByFocusLoss = false
            pauseInternal()
        }
    }

    fun resume() {
        if (state != TtsState.PAUSED) return
        pausedByFocusLoss = false
        if (!requestFocus()) return
        resumeInternal()
    }

    fun stop() {
        stopInternal()
    }

    /** Applies to the next utterance spoken (the engine has no live rate). */
    fun setSpeed(newSpeed: Float) {
        speed = TtsSpeed.clamp(newSpeed)
        tts?.setSpeechRate(speed)
    }

    /**
     * Stops playback [minutes] minutes from now; null clears a running
     * timer. Session-only: it is never persisted.
     */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null || minutes <= 0) return
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            stopInternal()
        }
    }

    /** Releases the engine, focus and receivers; the service calls this in onDestroy. */
    fun shutdown() {
        sleepJob?.cancel()
        sleepJob = null
        pendingStart = null
        abandonFocus()
        unregisterNoisyReceiver()
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        engineReady = false
        setState(TtsState.IDLE)
        scope.coroutineContext[Job]?.cancel()
    }

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (status != TextToSpeech.SUCCESS) {
                    engineFailed = true
                    setState(TtsState.ERROR)
                    listener.onError(TtsError.INIT_FAILED)
                    return@post
                }
                engineReady = true
                tts?.setOnUtteranceProgressListener(progressListener)
                val pending = pendingStart
                pendingStart = null
                if (pending != null) {
                    chunks = pending.chunks
                    chunkIndex = 0
                    locale = pending.locale
                    speed = pending.speed
                    beginSpeaking()
                }
            }
        }
    }

    private fun beginSpeaking() {
        val engine = tts ?: return
        val availability = engine.isLanguageAvailable(locale)
        when (availability) {
            TextToSpeech.LANG_MISSING_DATA -> {
                setState(TtsState.ERROR)
                listener.onError(TtsError.MISSING_DATA)
                return
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                setState(TtsState.ERROR)
                listener.onError(TtsError.LANGUAGE_NOT_SUPPORTED)
                return
            }
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                // Supported: fall through.
            }
            else -> {
                // LANG_NOT_SUPPORTED covers the negative codes; any
                // other value is treated as available, like the
                // engine's own setLanguage contract.
            }
        }
        val setResult = engine.setLanguage(locale)
        if (setResult == TextToSpeech.LANG_MISSING_DATA) {
            setState(TtsState.ERROR)
            listener.onError(TtsError.MISSING_DATA)
            return
        }
        if (setResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            setState(TtsState.ERROR)
            listener.onError(TtsError.LANGUAGE_NOT_SUPPORTED)
            return
        }
        if (!requestFocus()) return
        registerNoisyReceiver()
        engine.setSpeechRate(speed)
        setState(TtsState.PLAYING)
        speakCurrent()
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        val text = chunks.getOrNull(chunkIndex) ?: return
        val params = Bundle()
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId(chunkIndex))
        if (result == TextToSpeech.ERROR) {
            stopInternal()
            setState(TtsState.ERROR)
            listener.onError(TtsError.SPEAK_FAILED)
        }
    }

    private fun utteranceId(index: Int): String = "novelfa-tts-$index"

    private fun pauseInternal() {
        tts?.stop()
        setState(TtsState.PAUSED)
    }

    private fun resumeInternal() {
        // TTS has no resume: re-speak the chunk that was interrupted
        // (it was stopped mid-utterance, so it restarts from its
        // start — a few repeated words, never a skipped sentence).
        val engine = tts ?: return
        engine.setSpeechRate(speed)
        setState(TtsState.PLAYING)
        speakCurrent()
    }

    private fun stopInternal() {
        sleepJob?.cancel()
        sleepJob = null
        pausedByFocusLoss = false
        tts?.stop()
        abandonFocus()
        unregisterNoisyReceiver()
        setState(TtsState.IDLE)
    }

    private fun setState(next: TtsState) {
        if (state == next) return
        state = next
        listener.onStateChanged(next)
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
            return true
        }
        return false
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    if (state == TtsState.PLAYING) {
                        pausedByFocusLoss = false
                        pauseInternal()
                    }
                }
            }
        }
        noisyReceiver = receiver
        appContext.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun unregisterNoisyReceiver() {
        val receiver = noisyReceiver ?: return
        noisyReceiver = null
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}
