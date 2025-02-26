package com.danmo.guide.feature.feedback

import android.content.Context
import android.graphics.RectF
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.math.pow
import kotlin.math.sqrt

class FeedbackManager(context: Context) : TextToSpeech.OnInitListener {
    private val context: Context = context.applicationContext
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    private var isTtsReady = false
    private var lastVibrationTime = 0L

    // 消息优先级系统
    private enum class MsgPriority { CRITICAL, HIGH, NORMAL }
    private data class SpeechItem(
        val text: String,
        val priority: MsgPriority,
        val vibrationPattern: LongArray? = null,
        val id: String = UUID.randomUUID().toString()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SpeechItem

            if (text != other.text) return false
            if (priority != other.priority) return false
            if (vibrationPattern != null) {
                if (other.vibrationPattern == null) return false
                if (!vibrationPattern.contentEquals(other.vibrationPattern)) return false
            } else if (other.vibrationPattern != null) return false
            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = 31 * result + priority.hashCode()
            result = 31 * result + (vibrationPattern?.contentHashCode() ?: 0)
            result = 31 * result + id.hashCode()
            return result
        }
    }

    // 队列管理系统
    private val speechQueue = LinkedHashMap<String, SpeechItem>()
    private var currentSpeechId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val cooldownMap = mutableMapOf<String, Long>()
    private val activeMessages = mutableSetOf<String>()

    private val warningTemplates = listOf(
        { label: String -> "注意！正前方发现$label，请小心" },
        { label: String -> "危险！$label，接近中" },  // 移除多余的+
        { label: String -> "请留意，附近有$label" }
    )

    private val directionTemplates = listOf(
        { label: String, dir: String -> "您的${dir}方向有$label" },
        { label: String, dir: String -> "检测到${dir}存在$label" },
        { label: String, dir: String -> "$label，位于${dir}方位" }
    )

    private val distanceAdverbs = mapOf(
        "NEAR" to listOf("非常接近", "距离很近", "就在附近"),
        "MID" to listOf("约三米处", "前方中等距离", "稍远位置"),
        "FAR" to listOf("较远位置", "远处方向", "远端区域")
    )

    // 上下文记忆系统
    private data class ObjectContext(
        var lastReportTime: Long = 0,
        var reportCount: Int = 0,
        var lastDirection: String = ""
    )

    private val contextMemory = mutableMapOf<String, ObjectContext>()

    companion object {
        private const val MIN_COOLDOWN = 3000L
        private const val PRIORITY_COOLDOWN = 1500L
        const val CONFIDENCE_THRESHOLD = 0.4f
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck")
        private const val DANGEROUS_SIZE_RATIO = 0.25f
        private const val MEMORY_TIMEOUT = 30000L
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            isTtsReady = when (result) {
                TextToSpeech.LANG_MISSING_DATA -> {
                    Log.w("TTS", "缺少中文语言数据")
                    false
                }

                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Log.w("TTS", "不支持中文语音")
                    false
                }

                else -> {
                    tts.setOnUtteranceProgressListener(utteranceListener)
                    true
                }
            }
        } else {
            Log.e("TTS", "语音引擎初始化失败")
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            currentSpeechId = utteranceId
            handler.post {
                speechQueue.remove(utteranceId)?.let { item ->
                    triggerVibration(item)
                    activeMessages.remove(item.text)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            handler.post {
                currentSpeechId = null
                processNextInQueue()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            handler.post {
                currentSpeechId = null
                processNextInQueue()
                Log.e("TTS", "语音播报失败: $utteranceId")
            }
        }
    }

    fun handleDetectionResult(result: Detection, previewWidth: Int, previewHeight: Int) {
        if (!isTtsReady) return

        result.categories.maxByOrNull { it.score }?.let { category ->
            if (category.score < CONFIDENCE_THRESHOLD) return@let

            val label = getChineseLabel(category.label)
            val message = buildDetectionMessage(result, previewWidth, previewHeight, label)
            message?.let { queueMessage(it, determinePriority(category.label)) }
        }
    }

    private fun buildDetectionMessage(
        result: Detection,
        width: Int,
        height: Int,
        label: String
    ): String? {
        return when {
            isDangerousObject(result, width, height) ->
                generateCriticalAlert(label)

            shouldSuppressMessage(label) ->
                null

            else ->
                generateDirectionalMessage(result.boundingBox, width, height, label)
        }
    }

    private fun isDangerousObject(result: Detection, width: Int, height: Int): Boolean {
        val box = result.boundingBox
        val boxArea = (box.right - box.left) * (box.bottom - box.top)
        return boxArea > width * height * DANGEROUS_SIZE_RATIO
    }

    private fun generateCriticalAlert(label: String): String {
        return warningTemplates.random()(label)
    }

    private fun generateDirectionalMessage(
        box: RectF,
        width: Int,
        height: Int,
        label: String
    ): String? {
        val (direction, distance) = calculateDirection(box, width, height)
        val context = contextMemory.getOrPut(label) { ObjectContext() }

        return if (shouldReport(context, direction)) {
            context.update(direction)
            directionTemplates.random()(label, "$direction${getDistanceAdverb(distance)}")
        } else {
            null
        }
    }

    private fun calculateDirection(box: RectF, width: Int, height: Int): Pair<String, String> {
        val centerX = width / 2f
        val centerY = height / 2f
        val boxCenterX = (box.left + box.right) / 2
        val boxCenterY = (box.top + box.bottom) / 2

        // 三维距离计算
        val screenDiagonal = sqrt(width.toDouble().pow(2) + height.toDouble().pow(2))
        val boxDiagonal = sqrt(box.width().toDouble().pow(2) + box.height().toDouble().pow(2))
        val distance = when (boxDiagonal / screenDiagonal) {
            in 0.3..1.0 -> "NEAR"
            in 0.1..0.3 -> "MID"
            else -> "FAR"
        }

        // 九宫格方向判断
        val direction = when {
            boxCenterX < centerX * 0.4 && boxCenterY < centerY * 0.4 -> "左前方"
            boxCenterX > centerX * 1.6 && boxCenterY < centerY * 0.4 -> "右前方"
            boxCenterX < centerX * 0.4 && boxCenterY > centerY * 1.6 -> "左后方"
            boxCenterX > centerX * 1.6 && boxCenterY > centerY * 1.6 -> "右后方"
            boxCenterX < centerX -> "左侧"
            boxCenterX > centerX -> "右侧"
            else -> "正前方"
        }

        return direction to distance
    }

    private fun shouldReport(context: ObjectContext, newDirection: String): Boolean {
        val timeElapsed = System.currentTimeMillis() - context.lastReportTime
        return when {
            timeElapsed > MEMORY_TIMEOUT -> true
            context.lastDirection != newDirection -> true
            context.reportCount < 3 -> true
            else -> false
        }
    }

    private fun ObjectContext.update(newDirection: String) {
        lastReportTime = System.currentTimeMillis()
        reportCount = (reportCount % 2) + 1
        lastDirection = newDirection
    }

    private fun getDistanceAdverb(distance: String) =
        distanceAdverbs[distance]?.random() ?: ""

    private fun determinePriority(label: String) = when {
        label in DANGEROUS_LABELS -> MsgPriority.HIGH
        else -> MsgPriority.NORMAL
    }

    private fun shouldSuppressMessage(label: String) =
        label == "unknown" || label.contains("背景")

    // 队列管理系统
    private fun queueMessage(message: String, priority: MsgPriority) {
        val now = System.currentTimeMillis()

        if (cooldownMap[message]?.let { now - it < MIN_COOLDOWN } == true) return
        if (activeMessages.contains(message)) return
        if (priority == MsgPriority.CRITICAL && now - (cooldownMap.values.maxOrNull()
                ?: 0) < PRIORITY_COOLDOWN
        ) return

        val item = SpeechItem(
            text = message,
            priority = priority,
            vibrationPattern = when (priority) {
                MsgPriority.CRITICAL -> longArrayOf(0, 500, 200, 300)
                MsgPriority.HIGH -> longArrayOf(0, 200)
                else -> null
            }
        )

        manageQueue(item)
        cooldownMap[message] = now
        activeMessages.add(message)
    }

    private fun manageQueue(item: SpeechItem) {
        when (item.priority) {
            MsgPriority.CRITICAL -> {
                speechQueue.clear()
                speechQueue[item.id] = item
                interruptCurrentSpeech()
            }

            MsgPriority.HIGH -> {
                speechQueue.values.removeAll { it.priority == MsgPriority.NORMAL }
                speechQueue[item.id] = item
                if (currentSpeechId == null) processNextInQueue()
            }

            MsgPriority.NORMAL -> {
                if (!speechQueue.values.any { it.text == item.text }) {
                    speechQueue[item.id] = item
                    if (currentSpeechId == null) processNextInQueue()
                }
            }
        }
    }

    private fun processNextInQueue() {
        if (currentSpeechId != null || speechQueue.isEmpty()) return

        val item = speechQueue.values.maxWith(
            compareBy(
                { it.priority.ordinal },
                { -speechQueue.keys.indexOf(it.id) }
            )
        )
        item.let {
            speechQueue.remove(it.id)
            speak(it)
        }
    }

    private fun speak(item: SpeechItem) {
        try {
            // 新版本保留基础参数
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, item.id)
                putInt(TextToSpeech.Engine.KEY_PARAM_PAN, calculatePan(item.text))
            }
            tts.speak(item.text, TextToSpeech.QUEUE_ADD, params, item.id)

            // 全局语速设置（影响后续所有语音）
            tts.setSpeechRate(1.1f.takeIf { item.priority == MsgPriority.CRITICAL } ?: 0.9f)

            showToast(item.text)
        } catch (e: IllegalStateException) {
            Log.e("TTS", "播报失败", e)
            processNextInQueue()
        }
    }


    private fun interruptCurrentSpeech() {
        currentSpeechId?.let {
            try {
                tts.stop()
            } catch (e: IllegalStateException) {
                Log.e("TTS", "中断当前语音失败", e)
            }
            currentSpeechId = null
        }
        processNextInQueue()
    }

    private fun triggerVibration(item: SpeechItem) {
        if (System.currentTimeMillis() - lastVibrationTime < 1000) return

        item.vibrationPattern?.let { pattern ->
            vibrator?.let {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        it.vibrate(pattern, -1)
                    }
                    lastVibrationTime = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e("Vibration", "振动反馈失败", e)
                }
            }
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: IllegalStateException) {
            Log.e("TTS", "关闭语音引擎失败", e)
        }
        handler.removeCallbacksAndMessages(null)
        speechQueue.clear()
        activeMessages.clear()
        contextMemory.clear()
    }

    // 中文标签系统（完整版）
    private val labelTranslations = mapOf(
        "person" to "行人",
        "bicycle" to "自行车",
        "car" to "汽车",
        "motorcycle" to "摩托车",
        "airplane" to "飞机",
        "bus" to "公交车",
        "train" to "火车",
        "truck" to "卡车",
        "boat" to "船只",
        "traffic light" to "交通灯",
        "fire hydrant" to "消防栓",
        "stop sign" to "停车标志",
        "parking meter" to "停车计时器",
        "bench" to "长椅",
        "bird" to "鸟类",
        "cat" to "猫",
        "dog" to "狗",
        "horse" to "马",
        "sheep" to "羊",
        "cow" to "牛",
        "elephant" to "大象",
        "bear" to "熊",
        "zebra" to "斑马",
        "giraffe" to "长颈鹿",
        "backpack" to "背包",
        "umbrella" to "雨伞",
        "handbag" to "手提包",
        "tie" to "领带",
        "suitcase" to "行李箱",
        "frisbee" to "飞盘",
        "skis" to "滑雪板",
        "snowboard" to "滑雪单板",
        "sports ball" to "运动球类",
        "kite" to "风筝",
        "baseball bat" to "棒球棒",
        "baseball glove" to "棒球手套",
        "skateboard" to "滑板",
        "surfboard" to "冲浪板",
        "tennis racket" to "网球拍",
        "bottle" to "瓶子",
        "wine glass" to "酒杯",
        "cup" to "杯子",
        "fork" to "叉子",
        "knife" to "刀具",
        "spoon" to "勺子",
        "bowl" to "碗",
        "banana" to "香蕉",
        "apple" to "苹果",
        "sandwich" to "三明治",
        "orange" to "橙子",
        "broccoli" to "西兰花",
        "carrot" to "胡萝卜",
        "hot dog" to "热狗",
        "pizza" to "披萨",
        "donut" to "甜甜圈",
        "cake" to "蛋糕",
        "chair" to "椅子",
        "couch" to "沙发",
        "potted plant" to "盆栽",
        "bed" to "床",
        "dining table" to "餐桌",
        "toilet" to "马桶",
        "tv" to "电视",
        "laptop" to "笔记本",
        "mouse" to "鼠标",
        "remote" to "遥控器",
        "keyboard" to "键盘",
        "cell phone" to "手机",
        "microwave" to "微波炉",
        "oven" to "烤箱",
        "toaster" to "烤面包机",
        "sink" to "水槽",
        "refrigerator" to "冰箱",
        "book" to "书籍",
        "clock" to "时钟",
        "vase" to "花瓶",
        "scissors" to "剪刀",
        "teddy bear" to "玩偶",
        "hair drier" to "吹风机",
        "toothbrush" to "牙刷",
        "door" to "门",
        "window" to "窗户",
        "stairs" to "楼梯",
        "curtain" to "窗帘",
        "mirror" to "镜子"
    )

    fun getChineseLabel(original: String): String {
        val normalized = original
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z ]"), "")
            .trim()

        return labelTranslations[normalized] ?: normalized.split(" ")
            .joinToString("") { part ->
                labelTranslations[part] ?: part
            }.takeIf { it.isNotBlank() } ?: "物体"
    }

    private fun calculatePan(text: String): Int {
        return when {
            text.contains("左") -> -1 // 左声道
            text.contains("右") -> 1  // 右声道
            else -> 0               // 居中
        }
    }
}