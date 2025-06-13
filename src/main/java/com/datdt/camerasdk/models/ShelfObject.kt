package com.datdt.camerasdk.models

import android.graphics.RectF
import com.google.ar.core.Anchor

data class ShelfObject(
    val boundingBox: RectF,
    var worldPosition: Anchor? = null,
    var id: Int = 0
)
