package com.datdt.camerasdk.models

import android.graphics.RectF
import com.google.ar.core.Anchor

data class LabelObject(
    val boundingBox: RectF,
    val worldPosition: Anchor? = null
)