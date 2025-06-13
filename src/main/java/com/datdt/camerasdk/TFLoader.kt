package com.datdt.camerasdk

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.widget.Toast
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class TFLoader(private val context: Context) {
    fun loadModel(fileName: String): ByteBuffer {
        lateinit var modelBuffer: ByteBuffer
        var file: AssetFileDescriptor? = null
        try {
            file = context.assets.openFd(fileName)
            val inputStream = FileInputStream(file.fileDescriptor)
            val fileChannel = inputStream.channel
            modelBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                file.startOffset,
                file.declaredLength
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Error loading model", Toast.LENGTH_SHORT).show()
        } finally {
            file?.close()
        }
        return modelBuffer
    }

    fun loadLabels(fileName: String): List<String> {
        var labels = listOf<String>()
        var inputStream: InputStream? = null
        try {
            inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            labels = reader.readLines()
        } catch (e: Exception) {
            Toast.makeText(context, "Error loading model", Toast.LENGTH_SHORT).show()
        } finally {
            inputStream?.close()
        }
        return labels
    }
}