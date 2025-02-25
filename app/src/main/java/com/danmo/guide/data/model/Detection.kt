package com.danmo.guide.data.model

import android.graphics.RectF

data class Detection(
    val boundingBox: RectF,
    val categories: List<Category> // 注意这里是一个列表
)
