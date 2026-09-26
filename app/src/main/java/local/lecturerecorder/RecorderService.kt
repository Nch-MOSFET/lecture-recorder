package local.lecturerecorder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

private const val TAG = "RecorderService"
const val CH_STATUS = "status"
const val CH_ALERT = "alert"
private const val NOTIF_ID = 1
const val NOTIF_RESUME_ID = 2

/**
 * 自動録音の待機と録音を担う常駐サービス。
 * マイク用のフォアグラウンドサービスは画面を開いている間にしか開始できないため、
 * 利用者がアプリで「自動録音」をオンにしたときに開始し、以後は通知を出したまま待機する。
 */
class RecorderService : Service() {

    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var session: RecordingSession? = null
    private var manualLectureId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** 停止後の後処理（文字起こしの締め・変換・書き出し）が残っている件数 */
    private var finalizing = 0
    /** 録音の開始に失敗した直後は、すぐに再試行して空回りしないよう、この時刻まで待つ */
    private var retryAfter: LocalDateTime = LocalDateTime.MIN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        createChannels(this)
        instance = this
        recoverInterrupted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            // バックグラウンドからは開始できない。再開を促す通知を出して終わる
            Log.e(TAG, "startForeground failed", t)
            store.lastError = "自動録音を開始できませんでした：${t.message}"
            postResumeNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_RESUME_ID)
        when (intent?.action) {
            ACTION_MANUAL_START -> intent.getStringExtra(EXTRA_LECTURE_ID)?.let { manualStart(it) }
            ACTION_STOP_CURRENT -> stopCurrentByUser()
            ACTION_DISABLE -> {
                store.autoEnabled = false
                manualLectureId = null
            }
        }
        evaluate()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        session?.let { finishSession(it) }
        session = null
        instance = null
        cancelAlarm(this)
        super.onDestroy()
    }

    /** 今この時刻に何をすべきかを判断して録音を開始・停止し、次のアラームを設定する。 */
    fun evaluate() {
        val now = LocalDateTime.now()
        val scheduled = if (store.autoEnabled) Schedule.current(store, now) else null
        val manual = manualLectureId?.let { store.find(it) }

        val current = session
        when {
            // 手動録音は、止めるまで（または科目の終了時刻まで）続ける
            current != null && current.manual -> {
                val endAt = current.slot.end
                if (now >= endAt) {
                    manualLectureId = null
                    finishSession(current)
                    session = null
                }
            }
            current != null && (scheduled == null || scheduled.lecture.id != current.slot.lecture.id) -> {
                finishSession(current)
                session = null
            }
        }
        if (session == null && now >= retryAfter) {
            when {
                manual != null && manualLectureId != null -> startSession(manualSlot(manual, now), manual = true)
                scheduled != null -> startSession(scheduled, manual = false)
            }
        }

        io.execute { Exporter.flush(this) }
        scheduleAlarm(this, store)
        updateNotification()
        // 念のため1分ごとにも確認する（アラームが遅れた場合の保険。Doze 中は止まる）
        main.removeCallbacks(tick)
        main.postDelayed(tick, 60_000)

        if (session == null && !store.autoEnabled && finalizing == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        listeners.forEach { it() }
    }

    private val tick = Runnable { evaluate() }

    private fun manualSlot(lecture: Lecture, now: LocalDateTime): Slot {
        // 今日の授業時間中ならその終了時刻まで、それ以外は3時間で止める
        val todayEnd = LocalDateTime.of(now.toLocalDate(), lecture.end)
        val end = if (lecture.day == now.dayOfWeek && now < todayEnd.plusMinutes(store.endLateMin.toLong()))
            todayEnd.plusMinutes(store.endLateMin.toLong()) else now.plusHours(3)
        return Slot(lecture, now.toLocalDate(), now, end)
    }

    private fun manualStart(lectureId: String) {
        val current = session
        if (current != null) {
            finishSession(current)
            session = null
        }
        manualLectureId = lectureId
    }

    private fun stopCurrentByUser() {
        val current = session ?: return
        if (current.manual) {
            manualLectureId = null
        } else {
            // 自動録音中に止めた場合は、この回を再開しないようスキップ扱いにする
            store.find(current.slot.lecture.id)?.let { store.upsert(it.copy(skipDate = current.slot.date)) }
        }
        finishSession(current)
        session = null
    }

    private fun startSession(slot: Slot, manual: Boolean) {
        val dir = File(filesDir, "recording").apply { mkdirs() }
        val base = "${Exporter.safeName(slot.lecture.name)}_${slot.date.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
        val stamp = System.currentTimeMillis()
        val aac = File(dir, "$stamp.aac")
        val txt = File(dir, "$stamp.txt")
        store.activeRecording = listOf(slot.lecture.id, slot.lecture.name, base, aac.path, txt.path).joinToString("|")
        val s = RecordingSession(slot, manual, base, aac, txt)
        session = s
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureRecorder:rec").apply { acquire(6 * 60 * 60 * 1000L) }
        s.start()
        Log.i(TAG, "start ${slot.lecture.name} until ${slot.end}")
    }

    private fun finishSession(s: RecordingSession) {
        Log.i(TAG, "finish ${s.slot.lecture.name}")
        finalizing++
        s.stop { finalize(s.slot.lecture, s.baseName, s.aacFile, s.txtFile) }
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    /** 停止後の後処理：m4a へ変換し、保存先への書き出しを予約する。 */
    private fun finalize(lecture: Lecture, base: String, aac: File, txt: File) {
        io.execute {
            val items = mutableListOf<PendingExport>()
            if (aac.exists() && aac.length() > 0) {
                val m4a = File(aac.parentFile, aac.nameWithoutExtension + ".m4a")
                val (file, name) = if (AudioRecorder.remuxToM4a(aac, m4a)) {
                    aac.delete(); m4a to "$base.m4a"
                } else aac to "$base.aac"
                items += PendingExport(file.path, lecture.id, lecture.name, name, Exporter.mimeFor(name))
            } else {
                aac.delete()
            }
            // 見出しだけで本文（[時:分:秒] の行）がなければ書き出さない
            val hasText = txt.exists() && txt.useLines { lines -> lines.any { it.startsWith("[") } }
            if (hasText) {
                items += PendingExport(txt.path, lecture.id, lecture.name, "$base.txt", "text/plain")
            } else {
                txt.delete()
            }
            Exporter.enqueue(this, items)
            if (store.activeRecording?.contains(aac.path) == true) store.activeRecording = null
            Exporter.flush(this)
            main.post {
                finalizing = (finalizing - 1).coerceAtLeast(0)
                if (instance === this) evaluate()
            }
        }
    }

    /** 前回、録音中にアプリが強制終了していたら、残っているファイルを回収する。 */
    private fun recoverInterrupted() {
        val info = store.activeRecording?.split("|") ?: return
        if (info.size < 5) {
            store.activeRecording = null
            return
        }
        val lecture = store.find(info[0]) ?: Lecture(id = info[0], name = info[1], day = LocalDate.now().dayOfWeek,
            start = java.time.LocalTime.MIDNIGHT, end = java.time.LocalTime.MIDNIGHT)
        Log.w(TAG, "recovering interrupted recording ${info[2]}")
        finalizing++
        finalize(lecture, info[2] + "_中断", File(info[3]), File(info[4]))
    }

    fun snapshot(): Status {
        val s = session
        val now = LocalDateTime.now()
        return Status(
            recordingName = s?.slot?.lecture?.name,
            recordingSince = s?.startedAt,
            recordingUntil = s?.slot?.end,
            manual = s?.manual ?: false,
            level = s?.recorder?.peakLevel ?: 0f,
            transcriptTail = s?.tail(),
            partial = s?.partial,
            transcriberStatus = s?.transcriberStatus,
            next = if (store.autoEnabled) Schedule.next(store, now) else null,
        )
    }

    data class Status(
        val recordingName: String?,
        val recordingSince: LocalDateTime?,
        val recordingUntil: LocalDateTime?,
        val manual: Boolean,
        val level: Float,
        val transcriptTail: String?,
        val partial: String?,
        val transcriberStatus: String?,
        val next: Slot?,
    )

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val fmt = DateTimeFormatter.ofPattern("M/d(E) H:mm", java.util.Locale.JAPAN)
        val hm = DateTimeFormatter.ofPattern("H:mm")
        val s = session
        val next = if (store.autoEnabled) Schedule.next(store, LocalDateTime.now()) else null
        val title: String
        val text: String
        if (s != null) {
            title = "● 録音中：${s.slot.lecture.name}"
            text = "${s.slot.end.format(hm)} に停止" + (next?.let { "／次：${it.lecture.name} ${it.start.format(fmt)}" } ?: "")
        } else {
            title = "自動録音 待機中"
            text = next?.let { "次：${it.lecture.name} ${it.start.format(fmt)}" } ?: "予定されている授業はありません"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (s != null) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP_CURRENT),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, "この回の録音を停止", stop).build())
        }
        return b.build()
    }

    /** 1回分の録音（音声＋文字起こし） */
    inner class RecordingSession(
        val slot: Slot,
        val manual: Boolean,
        val baseName: String,
        val aacFile: File,
        val txtFile: File,
    ) {
        val startedAt: LocalDateTime = LocalDateTime.now()
        private val startedElapsed = SystemClock.elapsedRealtime()
        private val writer = OutputStreamWriter(FileOutputStream(txtFile, true), Charsets.UTF_8)
        private val lines = ArrayDeque<String>()
        @Volatile var partial: String? = null
        @Volatile var transcriberStatus: String? = null
        private val transcriber = Transcriber(
            this@RecorderService, store, slot.lecture.language,
            onFinal = { text -> appendLine(text) },
            onPartial = { partial = it },
            onStatus = { transcriberStatus = it },
        )
        val recorder = AudioRecorder(
            this@RecorderService,
            aacFile,
            onPcm = { transcriber.feed(it) },
            onFatal = { t ->
                store.lastError = "録音エラー（${LocalDateTime.now().format(DateTimeFormatter.ofPattern("M/d H:mm"))}）：${t.javaClass.simpleName} ${t.message.orEmpty()}"
                main.post {
                    if (session === this) {
                        finishSession(this)
                        session = null
                        retryAfter = LocalDateTime.now().plusMinutes(1)
                        evaluate()
                    }
                }
            },
        )

        fun start() {
            synchronized(writer) {
                writer.write("# ${slot.lecture.name}  ${startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} 録音開始\n")
                writer.write("# 端末内音声認識による自動文字起こし（${slot.lecture.language.label}・誤りを含みます）\n\n")
                writer.flush()
            }
            recorder.start()
            transcriber.start()
        }

        private fun appendLine(text: String) {
            val sec = (SystemClock.elapsedRealtime() - startedElapsed) / 1000
            val line = "[%02d:%02d:%02d] %s".format(sec / 3600, sec / 60 % 60, sec % 60, text)
            partial = null
            synchronized(writer) {
                runCatching { writer.write(line + "\n"); writer.flush() }
            }
            synchronized(lines) {
                lines.addLast(line)
                while (lines.size > 30) lines.removeFirst()
            }
            main.post { listeners.forEach { it() } }
        }

        fun tail(): String = synchronized(lines) { lines.joinToString("\n") }

        fun stop(onFinished: () -> Unit) {
            io.execute {
                recorder.stop()
                transcriber.stop {
                    synchronized(writer) { runCatching { writer.close() } }
                    onFinished()
                }
            }
        }
    }

    companion object {
        const val ACTION_MANUAL_START = "manual_start"
        const val ACTION_STOP_CURRENT = "stop_current"
        const val ACTION_DISABLE = "disable"
        const val EXTRA_LECTURE_ID = "lecture_id"

        @Volatile var instance: RecorderService? = null
            private set

        /** 画面側の表示更新用 */
        val listeners = mutableSetOf<() -> Unit>()

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "録音の状態", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "自動録音の再開のお願い", NotificationManager.IMPORTANCE_HIGH))
        }

        /** アプリの画面から呼ぶ（マイクのフォアグラウンドサービスは画面表示中にしか開始できない） */
        fun startFromUi(context: Context, action: String? = null, lectureId: String? = null) {
            val i = Intent(context, RecorderService::class.java).setAction(action)
            if (lectureId != null) i.putExtra(EXTRA_LECTURE_ID, lectureId)
            context.startForegroundService(i)
        }

        fun scheduleAlarm(context: Context, store: Store) {
            val am = context.getSystemService(AlarmManager::class.java)
            val next = if (store.autoEnabled) Schedule.nextBoundary(store, LocalDateTime.now()) else null
            val pi = alarmIntent(context)
            if (next == null) {
                am.cancel(pi)
                return
            }
            val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Android 12 で「アラームとリマインダー」が未許可のとき。数分ずれることがある
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        fun cancelAlarm(context: Context) {
            if (Store(context).autoEnabled) return // 待機を続ける場合はアラームを残す（受信側で再開を促す）
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        }

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun postResumeNotification(context: Context) {
            createChannels(context)
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("自動録音が止まっています")
                .setContentText("タップして自動録音を再開してください")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIF_RESUME_ID, n)
        }
    }
}
