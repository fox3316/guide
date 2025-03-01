package com.danmo.guide.feature.feedback

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

class FeedbackManager(context: Context) : TextToSpeech.OnInitListener {

    // 初始化上下文和硬件组件
    private val context: Context = context.applicationContext
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    private var isTtsReady = false

    // 语音消息优先级系统
    private enum class MsgPriority { CRITICAL, HIGH, NORMAL }

    // 语音消息数据结构
    private data class SpeechItem(
        val text: String,
        val direction: String,
        val basePriority: MsgPriority,
        val label: String, // 新增label字段
        val timestamp: Long = System.currentTimeMillis(),
        val vibrationPattern: LongArray? = null,
        val id: String = UUID.randomUUID().toString()
    ) : Comparable<SpeechItem> {

        private val ageFactor: Float get() =
            1 - (System.currentTimeMillis() - timestamp).coerceAtMost(5000L) / 5000f

        val dynamicPriority: Int get() = when (basePriority) {
            MsgPriority.CRITICAL -> (1000 * ageFactor).toInt()
            MsgPriority.HIGH -> (800 * ageFactor).toInt()
            MsgPriority.NORMAL -> (500 * ageFactor).toInt()
        }

        override fun compareTo(other: SpeechItem): Int = other.dynamicPriority.compareTo(this.dynamicPriority)
        // 修改1：增强消息唯一性判断条件
        override fun equals(other: Any?): Boolean = (other as? SpeechItem)?.let {
            // 同时校验label、direction和消息文本
            label == it.label && direction == it.direction && text == it.text
        } ?: false


        override fun hashCode(): Int = label.hashCode() + 31 * direction.hashCode()
    }

    // 并发管理组件
    private val speechQueue = PriorityBlockingQueue<SpeechItem>()
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSpeechId: String? = null

    // 批处理系统
    private val pendingDetections = ConcurrentLinkedQueue<Detection>()
    private val batchProcessor = Executors.newSingleThreadScheduledExecutor()

    // 上下文记忆系统
    private data class ObjectContext(
        var lastReportTime: Long = 0,
        var speedFactor: Float = 1.0f
    )
    private val contextMemory = ConcurrentHashMap<String, ObjectContext>()

    // 新增：最近消息记录和同步锁
    private val recentMessages = ConcurrentHashMap<String, Long>()
    private val queueLock = ReentrantLock()

    companion object {
        private const val BATCH_INTERVAL_MS = 50L
        private const val MIN_REPORT_INTERVAL_MS = 800L
        const val CONFIDENCE_THRESHOLD = 0.4f
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck")
    }


    init {
        // 初始化设备特定设置
        executor.execute {
            // 华为设备引擎设置（需要API Level 21+）
            if (Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)) {
                try {
                    tts = TextToSpeech(context, this@FeedbackManager, "com.huawei.hiai.engineservice.tts")
                } catch (e: Exception) {
                    Log.e("TTS", "华为引擎初始化失败", e)
                }
            }
        }

        // 启动批处理任务
        batchProcessor.scheduleWithFixedDelay(
            ::processBatch,
            0, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    override fun onInit(status: Int) {
        executor.submit {
            when {
                status == TextToSpeech.SUCCESS -> {
                    setupTTS()
                    processNextInQueue() // 初始化完成后触发队列处理
                }
                else -> showToast("语音功能初始化失败", true)
            }
        }
    }

    private fun setupTTS() {
        when (tts.setLanguage(Locale.CHINESE)) {
            TextToSpeech.LANG_MISSING_DATA -> handleMissingLanguageData()
            TextToSpeech.LANG_NOT_SUPPORTED -> showToast("不支持中文语音", true)
            else -> {
                tts.setOnUtteranceProgressListener(utteranceListener)
                isTtsReady = true
                Log.d("TTS", "语音引擎初始化成功")
            }
        }
    }

    private fun handleMissingLanguageData() {
        showToast("缺少中文语音数据", true)
        try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TTS", "无法启动语音数据安装", e)
        }
    }

    // region 语音处理逻辑
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            currentSpeechId = utteranceId
        }

        override fun onDone(utteranceId: String?) {
            executor.submit {
                if (currentSpeechId == utteranceId) { // 增加ID匹配校验
                    currentSpeechId = null
                    processNextInQueue()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            executor.submit {
                currentSpeechId = null // 清除当前ID
                processNextInQueue()
            }
        }
    }

    fun handleDetectionResult(result: Detection) {
        pendingDetections.add(result)
    }

    private fun processBatch() {
        val batch = mutableListOf<Detection>().apply {
            while (pendingDetections.isNotEmpty()) pendingDetections.poll()?.let { add(it) }
        }
        val mergedDetections = mergeDetections(batch)  // 明确接收合并结果
        mergedDetections.forEach { processSingleDetection(it) }
    }
    // endregion

    // region 核心业务逻辑
    private fun mergeDetections(detections: List<Detection>): List<Detection> {
        // 增加面积重叠率计算
        fun overlapRatio(a: RectF, b: RectF): Float {
            val interArea = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
                    max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
            return if (unionArea > 0) interArea / unionArea else 0f
        }

        val merged = mutableListOf<Detection>()  // 修复1：显式声明合并列表
        detections.sortedByDescending { it.boundingBox.width() }.forEach { detection ->
            // 修复2：正确引用merged变量
            if (merged.none { existing ->
                    overlapRatio(existing.boundingBox, detection.boundingBox) > 0.6 &&
                            existing.categories.any { it.label == detection.categories.first().label }
                }) {
                merged.add(detection)
            }
        }
        return merged
    }

    private fun processSingleDetection(result: Detection) {
        if (!checkTtsHealth()) return

        result.categories.maxByOrNull { it.score }
            ?.takeIf { it.score >= CONFIDENCE_THRESHOLD }
            ?.let { category ->
                val label = getChineseLabel(category.label)
                buildDetectionMessage(result, label)?.let { (message, dir, pri, lbl) ->
                    mainHandler.post { enqueueMessage(message, dir, pri, lbl) } // 添加第四个参数
                }
            }
    }

    // 新增：构建检测消息方法
    private fun buildDetectionMessage(result: Detection, label: String): Quadruple<String, String, MsgPriority, String>? {
        if (shouldSuppressMessage(label)) return null

        val context = contextMemory.compute(label) { _, v ->
            v?.apply { speedFactor = max(0.5f, speedFactor * 0.9f) } ?: ObjectContext()
        }!!

        return when {
            isCriticalDetection(result) -> Quadruple(
                generateCriticalAlert(label),
                "center",
                MsgPriority.CRITICAL,
                label
            )
            shouldReport(context, generateDirectionMessage(label, calculateDirection(result.boundingBox))) -> {
                context.lastReportTime = System.currentTimeMillis()
                context.speedFactor = min(2.0f, context.speedFactor * 1.1f)
                Quadruple(
                    generateDirectionMessage(label, calculateDirection(result.boundingBox)),
                    "general",
                    MsgPriority.HIGH,
                    label
                )
            }
            else -> null
        }
    }

    // 定义四元组类型
    private data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private fun checkTtsHealth(): Boolean {
        val isHealthy = isTtsReady && tts.voices?.any { it.locale == Locale.CHINESE } == true
        if (!isHealthy) showToast("语音引擎未就绪", true)
        return isHealthy
    }
    // endregion

    // region 语音生成逻辑
    private fun generateCriticalAlert(label: String): String {
        val templates = listOf("注意！正前方发现$label", "危险！$label,接近中", "紧急！$label,靠近")
        return templates.random()
    }

    private fun generateDirectionMessage(label: String, direction: String): String {
        val templates = listOf("您的${direction}方向有$label", "检测到${direction}存在$label", "$label,位于${direction}方位")
        return templates.random()
    }

    fun getChineseLabel(original: String): String {
        return mapOf(
            "person" to "行人", "car" to "汽车", "bus" to "公交车",
            "truck" to "卡车", "bicycle" to "自行车", "motorcycle" to "摩托车"
        )[original.lowercase()] ?: original
    }
    // endregion

    // region 辅助判断方法
    private fun shouldSuppressMessage(label: String): Boolean {
        return label == "unknown" || label.contains("background")
    }

    private fun isCriticalDetection(result: Detection): Boolean {
        return DANGEROUS_LABELS.any { label ->
            result.categories.any { it.label == label && it.score > 0.7 }
        } && result.boundingBox.width() > 0.25f
    }

    private fun shouldReport(context: ObjectContext, newMessage: String): Boolean {
        val baseInterval = when {
            newMessage.contains("注意！") -> MIN_REPORT_INTERVAL_MS / 2
            newMessage.contains("危险！") -> MIN_REPORT_INTERVAL_MS * 2 / 3
            else -> MIN_REPORT_INTERVAL_MS
        }
        return System.currentTimeMillis() - context.lastReportTime > (baseInterval / context.speedFactor).toLong()
    }

    private fun calculateDirection(box: RectF): String {
        return when (box.centerX()) {
            in 0f..0.3f -> "左侧"
            in 0.7f..1f -> "右侧"
            else -> "正前方"
        }
    }
    // endregion

    // 修改2：强化入队过滤逻辑
    private fun enqueueMessage(message: String, direction: String, priority: MsgPriority, label: String) {
        // 组合更多维度作为唯一键
        val messageKey = "${label}_${direction}_${message.hashCode()}"
        // 延长抑制时间为基本间隔的3倍
        val suppressDuration = when (priority) {
            MsgPriority.CRITICAL -> 5000
            MsgPriority.HIGH -> 3000
            MsgPriority.NORMAL -> 2000
        }

        // 严格的三重过滤
        if (recentMessages[messageKey]?.let {
                System.currentTimeMillis() - it < suppressDuration
            } == true) return

        // 增加内存队列检查
        if (speechQueue.any { it.label == label && it.direction == direction && it.text == message }) return

        recentMessages[messageKey] = System.currentTimeMillis()

        val item = SpeechItem(
            text = message,
            direction = direction,
            basePriority = priority,
            label = label,  // 添加label参数
            vibrationPattern = when (priority) {
                MsgPriority.CRITICAL -> longArrayOf(0, 500, 200, 300)
                MsgPriority.HIGH -> longArrayOf(0, 300, 100, 200)
                MsgPriority.NORMAL -> longArrayOf(0, 200)
            }
        )

        queueLock.withLock {
            if (!speechQueue.any { it.label == label && it.direction == direction }) {
                speechQueue.put(item)
                triggerVibration(item)
            }
        }
        processNextInQueue()
    }


    private fun processNextInQueue() {
        queueLock.withLock {
            if (currentSpeechId != null || speechQueue.isEmpty()) return@withLock
            speechQueue.poll()?.let { item ->
                currentSpeechId = item.id
                executor.submit { speak(item) } // 在锁内完成出队和状态更新
            }
        }
    }

    private fun speak(item: SpeechItem) {
        try {
            if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                // 小米设备需要延迟触发
                Handler(Looper.getMainLooper()).postDelayed({
                    doSpeak(item)
                }, 200)
            } else {
                doSpeak(item)
            }
        } catch (e: Exception) {
            Log.e("TTS", "播报失败: ${e.message}")
            processNextInQueue()
        }
    }

    private fun doSpeak(item: SpeechItem) {
        try {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, item.id)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_SYSTEM)
                putFloat("rate", 1.2f)
            }

            tts.speak(item.text, TextToSpeech.QUEUE_ADD, params, item.id)
            showToast(item.text)
        }catch (e: Exception) {
            Log.e("TTS", "播报失败: ${e.message}")
            currentSpeechId = null // 异常时清除状态
            processNextInQueue()
        }

    }
    // endregion

    // region 振动反馈
    private fun triggerVibration(item: SpeechItem) {
        vibrator?.let {
            try {
                item.vibrationPattern?.let { pattern ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION") it.vibrate(pattern, -1)
                    }
                }
            } catch (e: Exception) {
                Log.e("Vibration", "振动失败: ${e.message}")
            }
        }
    }
    // endregion

    // region 工具方法
    private fun showToast(message: String, isLong: Boolean = false) {
        mainHandler.post {
            Toast.makeText(context, message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    fun shutdown() {
        batchProcessor.shutdown()
        executor.shutdown()
        vibrator?.cancel()
        tts.stop()
        tts.shutdown()
        speechQueue.clear()
        contextMemory.clear()
    }
    // endregion
}