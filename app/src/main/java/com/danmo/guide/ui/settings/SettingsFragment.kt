package com.danmo.guide.ui.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.danmo.guide.R
import com.danmo.guide.feature.feedback.FeedbackManager

class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        // 使用更安全的preferenceManager获取sharedPreferences
        preferenceManager.sharedPreferences?.apply {
            registerOnSharedPreferenceChangeListener(this@SettingsFragment)
        }
    }

    override fun onPause() {
        super.onPause()
        // 添加空安全判断
        preferenceManager.sharedPreferences?.apply {
            unregisterOnSharedPreferenceChangeListener(this@SettingsFragment)
        }
    }

    @SuppressLint("RestrictedApi") // 处理平台类型警告
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "speech_enabled" -> {
                FeedbackManager.speechEnabled = sharedPreferences?.getBoolean(key, true) ?: true
                if (!FeedbackManager.speechEnabled) {
                    context?.let { FeedbackManager.getInstance(it).clearQueue() }
                }
            }
            "speech_language" -> {
                val lang = sharedPreferences?.getString(key, "zh") ?: "zh"
                context?.let { FeedbackManager.getInstance(it).updateLanguage(lang) }
                context?.let { FeedbackManager.getInstance(it).clearQueue() }
            }
            "speech_rate" -> {
                val rawValue = sharedPreferences?.getInt(key, 12) ?: 12
                val rate = rawValue / 10f
                FeedbackManager.speechRate = rate.coerceIn(0.5f, 2.0f)
            }
            "danger_sensitivity" -> {
                val level = sharedPreferences?.getString(key, "medium") ?: "medium"
                updateSensitivity(level)
            }
        }
    }

    private fun updateSensitivity(level: String) {
        FeedbackManager.confidenceThreshold = when (level) {
            "high" -> 0.3f
            "medium" -> 0.4f
            "low" -> 0.5f
            else -> 0.4f
        }
    }
}