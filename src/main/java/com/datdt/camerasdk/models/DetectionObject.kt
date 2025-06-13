package com.datdt.camerasdk.models

import android.graphics.Point
import android.graphics.RectF
import com.google.ar.core.Anchor

data class DetectionObject(
    val score: Float,
    val label: String,
    val labelDisplay: String,
    val boundingBox: RectF,
    val cropString: String,
    var worldPosition: Anchor? = null,
    var shelf: Int = 0,
    var facing: Int = 0,
    var bay: Int = 0
//    val centerCoordinate: Point
)