/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datdt.camerasdk
//
//import android.content.Context
//import android.graphics.Bitmap
//import android.graphics.ImageFormat
//import android.graphics.Rect
//import android.media.Image
//import android.renderscript.Allocation
//import android.renderscript.Element
//import android.renderscript.RenderScript
//import android.renderscript.ScriptIntrinsicYuvToRGB
//import android.renderscript.Type
//
///**
// * Helper class used to efficiently convert a [Media.Image] object from [ImageFormat.YUV_420_888]
// * format to an RGB [Bitmap] object.
// *
// * The [yuvToRgb] method is able to achieve the same FPS as the CameraX image analysis use case on a
// * Pixel 3 XL device at the default analyzer resolution, which is 30 FPS with 640x480.
// *
// * NOTE: This has been tested in a limited number of devices and is not considered production-ready
// * code. It was created for illustration purposes, since this is not an efficient camera pipeline
// * due to the multiple copies required to convert each frame.
// */
//class YuvToRgbConverter(context: Context) {
//  private val rs = RenderScript.create(context)
//  private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
//
//  private var allocatedPixelCount: Int = -1
//  private lateinit var yuvBuffer: ByteArray
//  private lateinit var inputAllocation: Allocation
//  private lateinit var outputAllocation: Allocation
//
//  @Synchronized
//  fun yuvToRgb(image: Image, output: Bitmap) {
//    val pixelCount = image.width * image.height
//    // Ensure that the intermediate output byte buffer is allocated to the correct pixel count
//    if (pixelCount != allocatedPixelCount) {
//      // Bits per pixel is an average for the whole image, so it's useful to compute the size
//      // of the full buffer but should not be used to determine pixel offsets
//      val pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)
//      yuvBuffer = ByteArray(pixelCount * pixelSizeBits / 8)
//    }
//
//    // Get the YUV data in byte array form using NV21 format
//    imageToByteArray(image, yuvBuffer, pixelCount)
//
//    // Ensure that the RenderScript inputs and outputs are allocated to the correct pixel count
//    if (pixelCount != allocatedPixelCount) {
//      // Explicitly create an element with type NV21, since that's the pixel format we use
//      val elemType = Type.Builder(rs, Element.YUV(rs)).setYuvFormat(ImageFormat.NV21).create()
//      inputAllocation = Allocation.createSized(rs, elemType.element, yuvBuffer.size)
//      outputAllocation = Allocation.createFromBitmap(rs, output)
//    }
//
//    // Convert NV21 format YUV to RGB
//    inputAllocation.copyFrom(yuvBuffer)
//    scriptYuvToRgb.setInput(inputAllocation)
//    scriptYuvToRgb.forEach(outputAllocation)
//    outputAllocation.copyTo(output)
//    allocatedPixelCount = pixelCount
//  }
//
//  private fun imageToByteArray(image: Image, outputBuffer: ByteArray, pixelCount: Int) {
//    assert(image.format == ImageFormat.YUV_420_888)
//
//    val imageCrop = Rect(0, 0, image.width, image.height)
//    val imagePlanes = image.planes
//
//    imagePlanes.forEachIndexed { planeIndex, plane ->
//      // How many values are read in input for each output value written
//      // Only the Y plane has a value for every pixel, U and V have half the resolution i.e.
//      //
//      // Y Plane            U Plane    V Plane
//      // ===============    =======    =======
//      // Y Y Y Y Y Y Y Y    U U U U    V V V V
//      // Y Y Y Y Y Y Y Y    U U U U    V V V V
//      // Y Y Y Y Y Y Y Y    U U U U    V V V V
//      // Y Y Y Y Y Y Y Y    U U U U    V V V V
//      // Y Y Y Y Y Y Y Y
//      // Y Y Y Y Y Y Y Y
//      // Y Y Y Y Y Y Y Y
//      val outputStride: Int
//
//      // The index in the output buffer the next value will be written at
//      // For Y it's zero, for U and V we start at the end of Y and interleave them i.e.
//      //
//      // First chunk        Second chunk
//      // ===============    ===============
//      // Y Y Y Y Y Y Y Y    V U V U V U V U
//      // Y Y Y Y Y Y Y Y    V U V U V U V U
//      // Y Y Y Y Y Y Y Y    V U V U V U V U
//      // Y Y Y Y Y Y Y Y    V U V U V U V U
//      // Y Y Y Y Y Y Y Y
//      // Y Y Y Y Y Y Y Y
//      // Y Y Y Y Y Y Y Y
//      var outputOffset: Int
//
//      when (planeIndex) {
//        0 -> {
//          outputStride = 1
//          outputOffset = 0
//        }
//        1 -> {
//          outputStride = 2
//          // For NV21 format, U is in odd-numbered indices
//          outputOffset = pixelCount + 1
//        }
//        2 -> {
//          outputStride = 2
//          // For NV21 format, V is in even-numbered indices
//          outputOffset = pixelCount
//        }
//        else -> {
//          // Image contains more than 3 planes, something strange is going on
//          return@forEachIndexed
//        }
//      }
//
//      val planeBuffer = plane.buffer
//      val rowStride = plane.rowStride
//      val pixelStride = plane.pixelStride
//
//      // We have to divide the width and height by two if it's not the Y plane
//      val planeCrop =
//        if (planeIndex == 0) {
//          imageCrop
//        } else {
//          Rect(imageCrop.left / 2, imageCrop.top / 2, imageCrop.right / 2, imageCrop.bottom / 2)
//        }
//
//      val planeWidth = planeCrop.width()
//      val planeHeight = planeCrop.height()
//
//      // Intermediate buffer used to store the bytes of each row
//      val rowBuffer = ByteArray(plane.rowStride)
//
//      // Size of each row in bytes
//      val rowLength =
//        if (pixelStride == 1 && outputStride == 1) {
//          planeWidth
//        } else {
//          // Take into account that the stride may include data from pixels other than this
//          // particular plane and row, and that could be between pixels and not after every
//          // pixel:
//          //
//          // |---- Pixel stride ----|                    Row ends here --> |
//          // | Pixel 1 | Other Data | Pixel 2 | Other Data | ... | Pixel N |
//          //
//          // We need to get (N-1) * (pixel stride bytes) per row + 1 byte for the last pixel
//          (planeWidth - 1) * pixelStride + 1
//        }
//
//      for (row in 0 until planeHeight) {
//        // Move buffer position to the beginning of this row
//        planeBuffer.position((row + planeCrop.top) * rowStride + planeCrop.left * pixelStride)
//
//        if (pixelStride == 1 && outputStride == 1) {
//          // When there is a single stride value for pixel and output, we can just copy
//          // the entire row in a single step
//          planeBuffer.get(outputBuffer, outputOffset, rowLength)
//          outputOffset += rowLength
//        } else {
//          // When either pixel or output have a stride > 1 we must copy pixel by pixel
//          planeBuffer.get(rowBuffer, 0, rowLength)
//          for (col in 0 until planeWidth) {
//            outputBuffer[outputOffset] = rowBuffer[col * pixelStride]
//            outputOffset += outputStride
//          }
//        }
//      }
//    }
//  }
//}

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import android.content.Context

/**
 * Helper class used to efficiently convert a [Media.Image] object from [ImageFormat.YUV_420_888]
 * format to an RGB [Bitmap] object.
 *
 * This version uses [YuvImage] and [BitmapFactory] for compatibility with Android 12+ and above.
 */
class YuvToRgbConverter(@Suppress("UNUSED_PARAMETER") context: Context) {

  @Synchronized
  fun yuvToRgb(image: Image, output: Bitmap) {
    val bitmap = imageToBitmap(image)

    // Draw the resulting bitmap into the output if dimensions match
    if (bitmap.width == output.width && bitmap.height == output.height) {
      val canvas = android.graphics.Canvas(output)
      canvas.drawBitmap(bitmap, 0f, 0f, null)
    } else {
      // Resize bitmap to fit into output if dimensions differ
      val resized = Bitmap.createScaledBitmap(bitmap, output.width, output.height, true)
      val canvas = android.graphics.Canvas(output)
      canvas.drawBitmap(resized, 0f, 0f, null)
    }
  }

  private fun imageToBitmap(image: Image): Bitmap {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    // Combine Y, U, and V planes into NV21 format
    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    // Convert to JPEG and then decode into Bitmap
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val outStream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, outStream)
    val jpegBytes = outStream.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
  }
}
