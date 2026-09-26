package local.lecturerecorder

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "Transcriber"

/** 複数言語のときに認識セッションを区切る間隔 */
private const val ROTATE_MS = 90_000L

/**
 * 端末内の音声認識（Pixel では Google の端末内認識）へ、録音中の PCM をパイプで流し込んで文字起こしする。
 * マイクは [AudioRecorder] が使うため、認識エンジンには EXTRA_AUDIO_SOURCE で音声を渡す。
 * セッションが終わったりエラーになったりしたら、録音が続く限り張り直す。
 */
class Transcriber(
    context: Context,
    private val store: Store,
    private val language: Language,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val ctx = context.applicationContext
    /**
     * true：録音中の音声をパイプで渡す（Android 13 以降）。
     * false：認識エンジンが自分でマイクを使い、発話ごとにセッションを張り直す（Android 12）。
     * 後者は録音と同時にマイクを使うため、端末によっては認識側が無音になる。
     */
    private val pipeMode = AudioRecorder.capturesPcm
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var backoffMs = 1000L
    private var sessionHadResult = false
    private var failuresWithoutResult = 0
    /** 言語の自動切り替えを使うか（非対応のエンジンでは外して再試行する） */
    private var languageSwitch = true
    /** 今のセッションで主に使っている言語（判定結果に応じて切り替える） */
    private var currentTag = language.primaryTag

    private val queue = ArrayBlockingQueue<ByteArray>(600) // 約60秒分
    @Volatile private var stopping = false
    @Volatile private var sink: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var readSide: ParcelFileDescriptor? = null
    private var feeder: Thread? = null

    /** 録音スレッドから呼ばれる。認識が詰まっても録音を止めないよう、あふれた分は捨てる。 */
    fun feed(pcm: ByteArray) {
        if (!queue.offer(pcm)) {
            queue.poll()
            queue.offer(pcm)
        }
    }

    fun start() = main.post {
        if (active) return@post
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            onStatus("端末内の音声認識が使えません（録音だけ続けます）")
            return@post
        }
        if (language.tags.size > 1 && Build.VERSION.SDK_INT < 34) {
            onStatus("この端末は言語の自動切り替えに対応していないため${language.primaryTag}で認識します")
        }
        active = true
        if (pipeMode) feeder = Thread({ feedLoop() }, "transcriber-feed").also { it.start() }
        createRecognizer()
        startSession()
    }

    /** 溜まった音声を渡し切ってから認識を終了し、最後の結果が届いた後に [onDone] を呼ぶ。 */
    fun stop(onDone: () -> Unit) {
        stopping = true
        main.post {
            val wasActive = active
            active = false
            main.removeCallbacks(rotate)
            main.postDelayed({
                feeder?.interrupt()
                feeder = null
                closeSink()
                destroyRecognizer()
                closeRead()
                onDone()
            }, if (!wasActive) 0 else if (pipeMode) 8000 else 1000)
        }
    }

    private fun createRecognizer() {
        destroyRecognizer()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).apply {
            setRecognitionListener(listener)
        }
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun startSession() {
        if (!active) return
        sessionHadResult = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentTag)
            if (languageSwitch && language.tags.size > 1 && Build.VERSION.SDK_INT >= 34) {
                // 日本語と英語が混ざる授業。認識エンジンが話している言語に合わせて切り替える
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(language.tags))
                // 授業のあいだ切り替えを続けられるよう、回数と時間に余裕を持たせる
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_MAX_SWITCHES, 10000)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_INITIAL_ACTIVE_DURATION_TIME_MILLIS, 6 * 60 * 60 * 1000)
                // 話している言語を判定させ、認識中の言語と違えばこちらから切り替える
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(language.tags))
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (pipeMode) {
                closeSink()
                closeRead()
                val pipe = ParcelFileDescriptor.createPipe()
                readSide = pipe[0]
                sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                // セッションの長さを音声の入力側（パイプを閉じるまで）に任せる
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            if (store.useFormatting && Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        // 複数言語のときは、話す言語が変わっても追従できるよう、区切って判定し直させる
        main.removeCallbacks(rotate)
        if (pipeMode && language.tags.size > 1) main.postDelayed(rotate, ROTATE_MS)
        runCatching { recognizer!!.startListening(intent) }.onFailure {
            Log.e(TAG, "startListening failed", it)
            scheduleRestart(recreate = true)
        }
    }

    /** 今の認識セッションを終わらせる。溜まった音声は次のセッションで渡すので取りこぼさない。 */
    private val rotate = Runnable {
        if (active) {
            Log.i(TAG, "rotate session")
            closeSink()
            // 認識側が終了を知らせてこない場合の保険（通常は onEndOfSegmentedSession で張り直す）
            main.postDelayed({
                if (active && sink == null) {
                    Log.w(TAG, "rotate fallback")
                    startSession()
                }
            }, 5000)
        }
    }

    private fun feedLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (stopping && queue.isEmpty()) {
                    // パイプを閉じると認識側は残りを処理してセッションを終える
                    closeSink()
                    return
                }
                val s = sink
                if (s == null) {
                    // セッション張り直し中。音声はキューに残しておく
                    Thread.sleep(50)
                    continue
                }
                val pcm = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    s.write(pcm)
                } catch (e: IOException) {
                    // 認識側がパイプを閉じた（セッション終了）。張り直しはコールバック側で行う
                    Log.w(TAG, "pipe closed: ${e.message}")
                    if (sink === s) sink = null
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun closeSink() {
        val s = sink
        sink = null
        runCatching { s?.close() }
    }

    private fun closeRead() {
        runCatching { readSide?.close() }
        readSide = null
    }

    private fun scheduleRestart(recreate: Boolean) {
        if (!active) return
        closeSink()
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
        main.postDelayed({
            if (!active) return@postDelayed
            if (recreate) createRecognizer()
            startSession()
        }, delay)
    }

    /** 判定された言語に合わせて、次のセッションの言語を変える */
    private fun checkDetectedLanguage(b: Bundle?) {
        if (b == null || language.tags.size < 2 || Build.VERSION.SDK_INT < 33) return
        val detected = b.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: return
        val confidence = b.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN)
        val switchResult = b.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED)
        Log.i(TAG, "detected=$detected confidence=$confidence switchResult=$switchResult current=$currentTag")
        val match = language.tags.firstOrNull { it.startsWith(detected.take(2)) } ?: return
        if (match == currentTag) return
        if (confidence == SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT ||
            confidence == SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN
        ) return
        Log.i(TAG, "switch language $currentTag -> $match")
        currentTag = match
        onStatus("文字起こしの言語を $match に切り替えました")
        main.post { if (active) { closeSink(); main.postDelayed({ if (active && sink == null) startSession() }, 1500) } }
    }

    private fun texts(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun gotResult(text: String) {
        sessionHadResult = true
        failuresWithoutResult = 0
        backoffMs = 1000
        if (text.isNotEmpty()) onFinal(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStatus("文字起こし中")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            checkDetectedLanguage(partialResults)
            val t = texts(partialResults)
            if (t.isNotEmpty()) onPartial(t)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            checkDetectedLanguage(segmentResults)
            gotResult(texts(segmentResults))
        }

        override fun onEndOfSegmentedSession() {
            if (active) main.post { startSession() }
        }

        override fun onResults(results: Bundle?) {
            // 分割セッション非対応のエンジンでは発話ごとにここへ来るので、すぐ次を始める
            checkDetectedLanguage(results)
            gotResult(texts(results))
            if (active) main.post { startSession() }
        }

        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error $error")
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    backoffMs = 500
                    scheduleRestart(recreate = false)
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (languageSwitch && !sessionHadResult) {
                        // 言語の自動切り替えに対応していないことがあるので、外して主な言語だけで試す
                        languageSwitch = false
                        backoffMs = 500
                        scheduleRestart(recreate = true)
                    } else if (store.useFormatting && !sessionHadResult) {
                        // 整形オプションが原因のことがあるので、外して再試行する
                        store.useFormatting = false
                        backoffMs = 500
                        scheduleRestart(recreate = true)
                    } else {
                        onStatus("${language.primaryTag} の端末内認識モデルがありません（録音だけ続けます）")
                        active = false
                        closeSink()
                    }
                }
                else -> {
                    if (!sessionHadResult) failuresWithoutResult++
                    if (failuresWithoutResult == 3 && store.useFormatting) store.useFormatting = false
                    onStatus("文字起こしを再接続中（エラー $error）")
                    scheduleRestart(recreate = true)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
