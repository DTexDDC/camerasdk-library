package com.datdt.camerasdk.models

enum class ModelType {
    DEFAULT,
    OUTPUT_FLOAT32;

    fun getModelInfo() = when(this) {
        DEFAULT -> ModelInfo(
            modelPath = "output_float32.tflite",
            labelPath = "output_float32_labels.txt",
            labels_displayPath = "labels_display.txt"
        )
        OUTPUT_FLOAT32 -> ModelInfo(
            modelPath = "output_float32.tflite",
            labelPath = "output_float32_labels.txt",
            labels_displayPath = "labels_display.txt"
        )
    }
}