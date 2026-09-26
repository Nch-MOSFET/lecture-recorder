package local.lecturerecorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

const val SAMPLE_RATE = 16000
private const val BIT_RATE = 32000
private const val TAG = "AudioRecorder"

/**
 * 録音ファイルは MediaRecorder で AAC(ADTS) に書き、同時に AudioRecord で取り込んだ PCM を [onPcm] へ渡す。
 * アプリ内の MediaCodec エンコーダーは Pixel 7a（Android 17）で configure に失敗するため使わない。
 * ADTS は途中で強制終了しても書けた所までは再生できるため、録音中はこの形式で保存し、
 * 停止後に [remuxToM4a] で m4a に変換する。
 */
class AudioRecorder(
    private val context: android.content.Context,
    private val aacFile: File,
    private val onPcm: (ByteArray) -> Unit,
    private val onFatal: (Throwable) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var peakLevel = 0f
        private set

    fun start() {
        running = true
        thread = Thread({ runLoop() }, "audio-recorder").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(5000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        var media: MediaRecorder? = null
        var record: AudioRecord? = null
        try {
            media = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(aacFile)
                setOnErrorListener { _, what, extra ->
                    if (running) {
                        running = false
                        onFatal(IllegalStateException("MediaRecorder error $what/$extra"))
                    }
                }
                prepare()
                start()
            }

            if (!capturesPcm) {
                // Android 12 以前は音声認識へ音声を渡せず、認識エンジンが自分でマイクを使うため、こちらでは取り込まない
                while (running) {
                    peakLevel = media.maxAmplitude / 32768f
                    Thread.sleep(200)
                }
                return
            }

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) error("マイクを初期化できませんでした")
            record.startRecording()

            val chunk = ByteArray(SAMPLE_RATE / 10 * 2) // 100ms
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "read error $n")
                    continue
                }
                val pcm = chunk.copyOf(n)
                peakLevel = peak(pcm)
                onPcm(pcm)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recording failed", t)
            if (running) {
                running = false
                onFatal(t)
            }
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { media?.stop() }
            runCatching { media?.release() }
        }
    }

    private fun peak(pcm: ByteArray): Float {
        var max = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val a = kotlin.math.abs(v.toShort().toInt())
            if (a > max) max = a
            i += 2
        }
        return max / 32768f
    }

    companion object {
        /** 録音中の PCM を音声認識へ渡せるか（EXTRA_AUDIO_SOURCE は Android 13 から） */
        val capturesPcm: Boolean get() = android.os.Build.VERSION.SDK_INT >= 33

        /** ADTS を m4a へ詰め替える。失敗したら false（その場合は .aac のまま保存する）。 */
        fun remuxToM4a(src: File, dst: File): Boolean {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            return try {
                extractor.setDataSource(src.absolutePath)
                if (extractor.trackCount < 1) return false
                extractor.selectTrack(0)
                muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = muxer.addTrack(extractor.getTrackFormat(0))
                muxer.start()
                val buf = ByteBuffer.allocate(64 * 1024)
                val info = MediaCodec.BufferInfo()
                var count = 0
                while (true) {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(track, buf, info)
                    count++
                    extractor.advance()
                }
                muxer.stop()
                count > 0
            } catch (t: Throwable) {
                Log.e(TAG, "remux failed", t)
                dst.delete()
                false
            } finally {
                runCatching { muxer?.release() }
                extractor.release()
            }
        }
    }
}
