package com.danmo.guide.feature.feedback

import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale

class FeedbackManager(context: Context) : TextToSpeech.OnInitListener {
    private val context = context
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var vibrator: Vibrator? = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    private var isTtsReady = false
    private var lastSpeakTime = 0L
    private val speakCooldown = 3000L

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            isTtsReady = when {
                result == TextToSpeech.LANG_MISSING_DATA -> {
                    Log.w("TTS", "Missing language data")
                    false
                }
                result == TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Log.w("TTS", "Language not supported")
                    false
                }
                else -> true
            }
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.4f // 40%置信度阈值
    }


    // 修改处理检测结果的方法
    fun handleDetectionResult(result: Detection, previewWidth: Int, previewHeight: Int) {
        if (!isTtsReady) return

        // 获取最高置信度类别
        val topCategory = result.categories.maxByOrNull { it.score } ?: return
        if (topCategory.score < CONFIDENCE_THRESHOLD) return // 过滤低置信度结果

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpeakTime < speakCooldown) return

        when {
            isDangerousDistance(result, previewWidth, previewHeight) -> {
                speakSafetyWarning(result)
                vibrate(500)
            }
            else -> {
                speakObjectNameWithDirection(result, previewWidth, previewHeight)
                vibrate(200)
            }
        }
        lastSpeakTime = currentTime
    }


    // 在 FeedbackManager 类中添加标签映射
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
        "traffic light" to "交通信号灯",
        "fire hydrant" to "消防栓",
        "stop sign" to "停车标志",
        "parking meter" to "停车计时器",
        "bench" to "长椅",
        "bird" to "鸟",
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
        "snowboard" to "单板滑雪板",
        "sports ball" to "运动球类",
        "kite" to "风筝",
        "baseball bat" to "棒球棒",
        "baseball glove" to "棒球手套",
        "skateboard" to "滑板",
        "surfboard" to "冲浪板",
        "tennis racket" to "网球拍",
        "bottle" to "瓶子",
        "wine glass" to "红酒杯",
        "cup" to "杯子",
        "fork" to "叉子",
        "knife" to "刀",
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
        "laptop" to "笔记本电脑",
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
        "teddy bear" to "泰迪熊",
        "hair drier" to "吹风机",
        "toothbrush" to "牙刷"
    )

    // 修改获取标签的方法
    fun getChineseLabel(originalLabel: String): String {
        return labelTranslations[originalLabel.toLowerCase()] ?: "未知物体"
    }

    private fun isDangerousDistance(result: Detection, width: Int, height: Int): Boolean {
        val boxArea = result.boundingBox.width() * result.boundingBox.height()
        val screenArea = width * height
        return boxArea > 0.25f * screenArea
    }

    private fun speakSafetyWarning(result: Detection) {
        val category = result.categories.firstOrNull()
        val originalLabel = category?.label ?: "unknown"
        val label = getChineseLabel(originalLabel) // 转换为中文

        val warning = when {
            originalLabel.equals("car", true) -> "危险！前方有汽车接近" // 保持原始判断逻辑
            originalLabel.equals("person", true) -> "注意！前方有行人"
            originalLabel.equals("stair", true) -> "警告！前方有楼梯"
            else -> "请注意！附近有$label"
        }
        speak(warning)
        showToast(warning)
    }

    private fun speakObjectNameWithDirection(result: Detection, width: Int, height: Int) {
        val category = result.categories.firstOrNull()
        val originalLabel = category?.label ?: "unknown"
        val label = getChineseLabel(originalLabel) // 转换为中文

        val direction = getDirection(result.boundingBox, width, height)
        val message = "检测到${label}在${direction}"
        speak(message)
        showToast(message)
    }

    private fun getDirection(boundingBox: RectF, width: Int, height: Int): String {
        val centerX = width / 2
        val centerY = height / 2
        val boxCenterX = (boundingBox.left + boundingBox.right) / 2
        val boxCenterY = (boundingBox.top + boundingBox.bottom) / 2

        return when {
            boxCenterX < centerX / 2 -> "左侧远处"
            boxCenterX > centerX * 3 / 2 -> "右侧远处"
            boxCenterY < centerY / 2 -> "上方"
            boxCenterY > centerY * 3 / 2 -> "下方"
            boxCenterX < centerX -> "左侧"
            boxCenterX > centerX -> "右侧"
            else -> "正前方"
        }
    }


    private fun speak(text: String) {
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } catch (e: IllegalStateException) {
            Log.e("TTS", "Error speaking text", e)
        }
    }

    private fun vibrate(durationMs: Long) {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(durationMs)
            }
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun shutdown() {
        tts.shutdown()
    }
}