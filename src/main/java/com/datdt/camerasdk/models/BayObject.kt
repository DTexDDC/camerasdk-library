package com.datdt.camerasdk.models

import com.google.ar.core.Anchor

data class BayObject(
    var id: Int = 0,
    var endpointLeft: Anchor? = null,
    var endpointRight: Anchor? = null
)