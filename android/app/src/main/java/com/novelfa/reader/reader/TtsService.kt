package com.novelfa.reader.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.novelfa.reader.R
import java.util.Locale

/**
 * Foreground service for read-aloud playback (ReadEra/Moon parity):
 * the [TtsPlayer] engine lives here, not in the reader UI, so playback
 * survives the screen turning off and the reader being backgrounded.
 * The notification carries play/pause/stop; the service stops itself
 * when the reader closes (the reader unbinds and stops it on dispose)
 * and when the book ends.
 *
 * The reader drives it through the local [Binder]: [startReading] with
 * one chapter's chunks, [pause]/[resume]/[stop], [setSpeed],
 * [setSleepTimerMinutes]. Chapter advance is NOT done here — on
 * [TtsListener.onChapterFinished] the bound reader loads the next
 * chapter and calls [startReading] again; the service only knows the
 * current chapter's queue.
 */
class TtsService : Service() {

    interface TtsListener {
        fun onStateChanged(state: TtsState)
        fun onChapterFinished()
        fun onError(error: TtsError)
    }

    inner class LocalBinder : Binder() {
        fun service(): TtsService = this@TtsService
    }

    companion object {
        const val CHANNEL_ID = "novelfa_tts"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.novelfa.reader.tts.PAUSE"
        const val ACTION_RESUME = "com.novelfa.reader.tts.RESUME"
        const val ACTION_STOP = "com.novelfa.reader.tts.STOP"

        fun intent(context: Context): Intent = Intent(context, TtsService::class.java)
    }

    private val binder = LocalBinder()
    private lateinit var player: TtsPlayer

    /** Set by the bound reader; null when nobody is listening. */
    var listener: TtsListener? = null

    /** Book/chapter shown in the notification (book data, never a string). */
    var bookTitle: String = ""
        private set
    var chapterTitle: String = ""
        private set

    val state: TtsState get() = if (::player.isInitialized) player.state else TtsState.IDLE

    private val playerListener = object : TtsPlayerListener {
        override fun onStateChanged(state: TtsState) {
            updateNotification()
            listener?.onStateChanged(state)
        }

        override fun onChapterFinished() {
            updateNotification()
            listener?.onChapterFinished()
        }

        override fun onError(error: TtsError) {
            updateNotification()
            listener?.onError(error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        player = TtsPlayer(this, playerListener)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The reader stops the service explicitly on close; an unbind
        // with nothing playing means the session is over either way.
        if (state == TtsState.IDLE) stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> player.pause()
            ACTION_RESUME -> player.resume()
            ACTION_STOP -> {
                player.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        player.shutdown()
        super.onDestroy()
    }

    /**
     * Reads one chapter's chunks aloud. Promotes the service to the
     * foreground first (mediaPlayback type, targetSdk 35): playback
     * must survive screen-off, and a foreground service may only
     * start speaking from the foreground.
     */
    fun startReading(
        chunks: List<String>,
        isEnglish: Boolean,
        speed: Float,
        bookTitle: String,
        chapterTitle: String
    ) {
        this.bookTitle = bookTitle
        this.chapterTitle = chapterTitle
        startForegroundService()
        player.start(chunks, ttsLocale(isEnglish), speed)
        updateNotification()
    }

    fun pause() = player.pause()

    fun resume() = player.resume()

    fun stop() {
        player.stop()
        updateNotification()
    }

    fun setSpeed(speed: Float) {
        player.setSpeed(speed)
        updateNotification()
    }

    fun setSleepTimerMinutes(minutes: Int?) = player.setSleepTimer(minutes)

    /** Book language → TTS locale (the book's own direction, not the UI language). */
    fun ttsLocale(isEnglish: Boolean): Locale =
        if (isEnglish) Locale.US else Locale("fa", "IR")

    private fun startForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        // Before the first startReading there is no foreground
        // notification to update; startReading builds it.
        if (state == TtsState.IDLE && bookTitle.isEmpty()) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tts_listen),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.tts_listen)
        manager.createNotificationChannel(channel)
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, TtsService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val title = bookTitle.ifEmpty { getString(R.string.tts_listen) }
        builder
            .setContentTitle(title)
            .setContentText(chapterTitle.ifEmpty { null })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(state == TtsState.PLAYING)
            .setOnlyAlertOnce(true)
        // The notification's tap target is the reader itself: reopen
        // the app (the reader route restores the open book).
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        if (launch != null) builder.setContentIntent(launch)
        if (state == TtsState.PLAYING) {
            builder.addAction(
                Notification.Action.Builder(
                    null, getString(R.string.tts_pause), actionIntent(ACTION_PAUSE)
                ).build()
            )
        } else if (state == TtsState.PAUSED) {
            builder.addAction(
                Notification.Action.Builder(
                    null, getString(R.string.tts_resume), actionIntent(ACTION_RESUME)
                ).build()
            )
        }
        builder.addAction(
            Notification.Action.Builder(
                null, getString(R.string.tts_stop), actionIntent(ACTION_STOP)
            ).build()
        )
        return builder.build()
    }
}
