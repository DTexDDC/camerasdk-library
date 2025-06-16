/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datdt.camerasdk

import java.io.IOException
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.compose.animation.core.animateDpAsState
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.datdt.camerasdk.java.common.helpers.TrackingStateHelper
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.google.ar.core.Session

import com.datdt.camerasdk.java.common.helpers.DisplayRotationHelper
import com.datdt.camerasdk.java.common.samplerender.SampleRender
import com.datdt.camerasdk.java.common.samplerender.arcore.BackgroundRenderer
import com.datdt.camerasdk.render.LabelRender
import com.datdt.camerasdk.render.PointCloudRender
import com.datdt.camerasdk.models.DetectionObject
import com.datdt.camerasdk.models.ModelType
import com.google.ar.core.TrackingFailureReason
import kotlinx.coroutines.CoroutineScope
import androidx.core.graphics.createBitmap
import com.datdt.camerasdk.java.common.samplerender.Framebuffer
import com.datdt.camerasdk.java.common.samplerender.GLError
import com.datdt.camerasdk.java.common.samplerender.Mesh
import com.datdt.camerasdk.java.common.samplerender.Shader
import com.datdt.camerasdk.java.common.samplerender.Texture
import com.datdt.camerasdk.java.common.samplerender.VertexBuffer
import com.datdt.camerasdk.java.common.samplerender.arcore.PlaneRenderer
import com.datdt.camerasdk.java.common.samplerender.arcore.SpecularCubemapFilter
import com.datdt.camerasdk.models.BayObject
import com.datdt.camerasdk.models.LabelObject
import com.datdt.camerasdk.models.ShelfObject
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Trackable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.sqrt

data class DetectionPayload(
  val detections: List<DetectionObject>,
  val overviewImage: String // overview image of the whole scene
)

/** Renders the ML application into using our sample Renderer. */
class AppRenderer(val activity: MainActivity, modelType: ModelType) : DefaultLifecycleObserver, SampleRender.Renderer {
  companion object {
    val TAG = "MLAppRenderer"

    // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
    // constants.
    private val sphericalHarmonicFactors =
      floatArrayOf(
        0.282095f,
        -0.325735f,
        0.325735f,
        -0.325735f,
        0.273137f,
        -0.273137f,
        0.078848f,
        -0.273137f,
        0.136569f
      )

    private val Z_NEAR = 0.01f
    private val Z_FAR = 100f

    // Assumed distance from the device camera to the surface on which user will try to place
    // objects.
    // This value affects the apparent scale of objects while the tracking method of the
    // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
    // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
    // values for AR experiences where users are expected to place objects on surfaces close to the
    // camera. Use larger values for experiences where the user will likely be standing and trying
    // to
    // place an object on the ground or floor in front of them.
    val APPROXIMATE_DISTANCE_METERS = 0.5f

    val CUBEMAP_RESOLUTION = 16
    val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
  }

  lateinit var view: MainActivityView
  private val coroutineScope = MainScope()

  val displayRotationHelper = DisplayRotationHelper(activity)

  // Rendering components
  lateinit var render: SampleRender
  lateinit var planeRenderer: PlaneRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false
  lateinit var backgroundRenderer: BackgroundRenderer
  val pointCloudRender = PointCloudRender()
  val labelRenderer = LabelRender()

  lateinit var pointCloudVertexBuffer: VertexBuffer
  lateinit var pointCloudMesh: Mesh
  lateinit var pointCloudShader: Shader

  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  var lastPointCloudTimestamp: Long = 0

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectAlbedoTexture: Texture
  lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

  // Environmental HDR
  lateinit var dfgTexture: Texture
  lateinit var cubemapFilter: SpecularCubemapFilter


  // Matrices for reuse in order to prevent reallocations every frame.
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val viewProjectionMatrix = FloatArray(16)
  val modelMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model


  val arLabeledAnchors = Collections.synchronizedList(mutableListOf<ARLabeledAnchor>())
  var objectResultsAll = Collections.synchronizedList(mutableListOf<DetectionObject>())
  var labelResultsAll = Collections.synchronizedList(mutableListOf<LabelObject>())
  var shelfResultsAll = Collections.synchronizedList(mutableListOf<ShelfObject>())
  var bayResultsAll = Collections.synchronizedList(mutableListOf<BayObject>())
  var bayResultsPoints = Collections.synchronizedList(mutableListOf<Anchor>())
  private val wrappedAnchors = Collections.synchronizedList(mutableListOf<WrappedAnchor>())

  var scanButtonWasPressed = false
  var lastAcquireTime: Long = 0
  var scanPressTime: Long = 0
  var minInterval: Long = 5000
  var overviewImage: String = ""

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
  val viewInverseMatrix = FloatArray(16)
  val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
  val viewLightDirection = FloatArray(4) // view x world light direction

  val session
    get() = activity.arCoreSessionHelper.session

  val trackingStateHelper = TrackingStateHelper(activity)

  //  val mlKitAnalyzer = MLKitObjectDetector(activity)
//  val gcpAnalyzer = run {
//    // API key used to authenticate with Google Cloud Vision API. See README for steps on how to
//    // obtain a valid API key.
//    val applicationInfo =
//      activity.packageManager.getApplicationInfo(activity.packageName, PackageManager.GET_META_DATA)
//    val apiKey = applicationInfo.metaData.getString("com.google.ar.core.examples.kotlin.ml.API_KEY")
//    if (apiKey == null) null else GoogleCloudVisionDetector(activity, apiKey)
//  }
  var modelType = modelType;
  val context = activity;
  var currentAnalyzer: ObjectDetectorHelper? = null;

  val rgbConverter: YuvToRgbConverter = YuvToRgbConverter(activity)


  var screenWidth: Int? = null
  var screenHeight: Int? = null

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  /** Binds UI elements for ARCore interactions. */
  fun bindView(view: MainActivityView) {
    this.view = view

    view.scanButton.setOnClickListener {
      // frame.acquireCameraImage is dependent on an ARCore Frame, which is only available in
      // onDrawFrame.
      // Use a boolean and check its state in onDrawFrame to interact with the camera image.
      scanButtonWasPressed = !scanButtonWasPressed
      scanPressTime = SystemClock.uptimeMillis()
      Log.d(TAG, "Scan button pressed")
      view.setScanningActive(scanButtonWasPressed)

      if (!scanButtonWasPressed) {
//        Log.d("Shelf_Details", "${shelfResultsAll.size}")
        val shelfy: List<ShelfObject> = shelfResultsAll.sortedBy { it.worldPosition?.pose?.ty() }
        val shelfy_len = shelfy.size
        for (i in 0 until shelfy_len) {
          shelfy[i].id = shelfy_len - i
//          Log.d("Shelf_Details", "${shelfy[i].worldPosition?.pose?.ty()!!}, ${shelfy[i].id}")
        }

        for (obj in objectResultsAll) {
          val y = obj.worldPosition?.pose?.ty()!!
//          Log.d("Shelf_Details", "${obj.worldPosition?.pose?.ty()!!}")
          for (s in 0 until shelfy_len) {
            if (s == shelfy_len - 1) {
              obj.shelf = shelfy[s].id
              break
            }
            if (y < shelfy[s + 1].worldPosition?.pose?.ty()!! && y > shelfy[s].worldPosition?.pose?.ty()!!) {
              obj.shelf = shelfy[s].id
              break
            }
          }
        }
        // 1) Group items by their shelf ID
        val byShelf: Map<Int, List<DetectionObject>> = objectResultsAll.groupBy { it.shelf }

        // 2) For each shelf, sort by x and assign facing = (index + 1)
        byShelf.forEach { (_, group) ->
          group
            .sortedBy { it.worldPosition?.pose?.tx()!! }         // left (small x) → right (large x)
            .forEachIndexed { idx, item ->
              item.facing = idx + 1
            }
        }
        val orderedPoints = bayResultsPoints.sortedBy { it.pose.tx() }
        for (i in orderedPoints.indices step 2) {
          if (i >= orderedPoints.size || i+1 >= orderedPoints.size) {
            break
          }
          bayResultsAll.add(BayObject(bayResultsAll.size+1, orderedPoints[i], orderedPoints[i+1]))
        }
        bayResultsPoints.clear()

        for (obj in objectResultsAll) {
          for (b in bayResultsAll) {
            val objx = obj.worldPosition!!.pose.tx()
            val bLeft = b.endpointLeft!!.pose.tx()
            val bRight = b.endpointRight!!.pose.tx()
            if (bLeft == null || bRight == null) {
              showSnackbar(
                "Both bay endpoints not identified" + "\n" + "Please Rescan"
              )
            } else {
              if (objx > bLeft && objx < bRight) {
                obj.bay = b.id
                Log.d("Shelf_Details", "Bay exists, ${bLeft}, ${bRight}")
              }
            }
          }
          Log.d("Shelf_Details", "${obj.shelf}, ${obj.facing}, ${obj.bay}")
        }
        DetectionManager.updateDetections(DetectionPayload(objectResultsAll, overviewImage))
      } else {
        synchronized(arLabeledAnchors) { arLabeledAnchors.clear() }
        synchronized(objectResultsAll) {objectResultsAll.clear()}
//        synchronized(bayResultsAll) {bayResultsAll.clear()}
        synchronized(shelfResultsAll) {shelfResultsAll.clear()}
        synchronized(labelResultsAll) {labelResultsAll.clear()}
        synchronized(wrappedAnchors) {wrappedAnchors.clear()}
      }

      hideSnackbar()
    }

    var modelInfo = modelType.getModelInfo()
    currentAnalyzer = ObjectDetectorHelper(context = context, modelInfo = modelInfo, resultViewSize = Size(640, 640));
    if (currentAnalyzer != null) {
      Log.d(TAG, "Model Initialised Properly")
    }
//    val gcpConfigured = gcpAnalyzer != null
//    val configuredAnalyzer = gcpAnalyzer ?: mlKitAnalyzer
//    view.useCloudMlSwitch.setOnCheckedChangeListener { _, isChecked ->
//      currentAnalyzer = if (isChecked) configuredAnalyzer else mlKitAnalyzer
//    }

//    view.useCloudMlSwitch.isChecked = gcpConfigured
//    view.useCloudMlSwitch.isEnabled = gcpConfigured
//    currentAnalyzer = if (gcpConfigured) configuredAnalyzer else mlKitAnalyzer
//
//    if (!gcpConfigured) {
//      showSnackbar(
//        "Google Cloud Vision isn't configured (see README). The Cloud ML switch will be disabled."
//      )
//    }
    view.finishButton.setOnClickListener {
      Log.d(TAG, "Finish button pressed")
      DetectionManager.updateDetections(DetectionPayload(emptyList(), "end"))
    }

    view.resetButton.setOnClickListener {
      Log.d(TAG, "Reset button pressed")
      synchronized(arLabeledAnchors) { arLabeledAnchors.clear() }
      synchronized(objectResultsAll) {objectResultsAll.clear()}
      synchronized(shelfResultsAll) {shelfResultsAll.clear()}
      synchronized(labelResultsAll) {labelResultsAll.clear()}
      synchronized(wrappedAnchors) {wrappedAnchors.clear()}
      view.resetButton.isEnabled = false
      hideSnackbar()
    }
  }

  override fun onSurfaceCreated(render: SampleRender) {
    Log.d(TAG, "SurfaceView measured width: ${view.surfaceView.measuredWidth}, height: ${view.surfaceView.measuredHeight}")
//    backgroundRenderer =
//      BackgroundRenderer(render).apply { setUseDepthVisualization(render, false) }
    pointCloudRender.onSurfaceCreated(render)
    labelRenderer.onSurfaceCreated(render)
    Log.e(TAG, "All renderers have been created.")
    screenWidth = view.surfaceView.width
    screenHeight = view.surfaceView.height

    try {
      planeRenderer = PlaneRenderer(render)
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      cubemapFilter =
        SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
      // Load environmental lighting values lookup table
      dfgTexture =
        Texture(
          render,
          Texture.Target.TEXTURE_2D,
          Texture.WrapMode.CLAMP_TO_EDGE,
          /*useMipmaps=*/ false
        )
      // The dfg.raw file is a raw half-float texture with two channels.
      val dfgResolution = 64
      val dfgChannels = 2
      val halfFloatSize = 2

      val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
      activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
      GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        /*level=*/ 0,
        GLES30.GL_RG16F,
        /*width=*/ dfgResolution,
        /*height=*/ dfgResolution,
        /*border=*/ 0,
        GLES30.GL_RG,
        GLES30.GL_HALF_FLOAT,
        buffer
      )
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

      // Point cloud
      pointCloudShader =
        Shader.createFromAssets(
          render,
          "shaders/point_cloud.vert",
          "shaders/point_cloud.frag",
          /*defines=*/ null
        )
          .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
          .setFloat("u_PointSize", 5.0f)

      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
        VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
      val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer)
      pointCloudMesh =
        Mesh(render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers)

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_albedo.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectAlbedoInstantPlacementTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_albedo_instant_placement.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      val virtualObjectPbrTexture =
        Texture.createFromAsset(
          render,
          "models/pawn_roughness_metallic_ao.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.LINEAR
        )
      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/environmental_hdr.vert",
          "shaders/environmental_hdr.frag",
          mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
        )
          .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
          .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
          .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
          .setTexture("u_DfgTexture", dfgTexture)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender?, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
    screenWidth = width
    screenHeight = height
  }

  var objectResultsTemp: List<DetectionObject>? = null
  var objectResultsAdd = mutableListOf<DetectionObject>()
  var bayResultsTemp: List<BayObject>? = null

  override fun onDrawFrame(render: SampleRender) {
//    Log.d(TAG, "This is what's in all objects: ${objectResultsAll}")
    val session = session ?: return
    session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
//    if (!hasSetTextureNames) {
//      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
//      hasSetTextureNames = true
//    }

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
        render,
        activity.depthSettings.depthColorVisualizationEnabled()
      )
      backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
      return
    }

    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showSnackbar("Camera not available. Try restarting the app.")
        return
      }
    val camera = frame.camera

    backgroundRenderer.updateDisplayGeometry(frame)
    val shouldGetDepthImage =
      activity.depthSettings.useDepthForOcclusion() ||
              activity.depthSettings.depthColorVisualizationEnabled()
    if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
      try {
        val depthImage = frame.acquireDepthImage16Bits()
        backgroundRenderer.updateCameraDepthTexture(depthImage)
        depthImage.close()
      } catch (e: NotYetAvailableException) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }
    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
//    val message: String? =
      when {
        camera.trackingState == TrackingState.PAUSED &&
                camera.trackingFailureReason == TrackingFailureReason.NONE ->
            showSnackbar("Tracking State Paused").toString()
        camera.trackingState == TrackingState.PAUSED ->
          TrackingStateHelper.getTrackingFailureReasonString(camera)
//        session.hasTrackingPlane() && wrappedAnchors.isEmpty() ->
//          showSnackbar("No objects identified").toString()
//        session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
        else -> null
      }
//    if (message == null) {
//      activity.view.snackbarHelper.hide(activity)
//    } else {
//      activity.view.snackbarHelper.showMessage(activity, message)
//    }

    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // Get camera and projection matrices.
//    camera.getViewMatrix(viewMatrix, 0)
//    camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100.0f)
//    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

    // -- Draw non-occluded virtual objects (planes, point cloud)

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)
    frame.acquirePointCloud().use { pointCloud ->
      if (pointCloud.timestamp > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.points)
        lastPointCloudTimestamp = pointCloud.timestamp
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(pointCloudMesh, pointCloudShader)
    }

    // Visualize planes.
//    planeRenderer.drawPlanes(
//      render,
//      session.getAllTrackables<Plane>(Plane::class.java),
//      camera.displayOrientedPose,
//      projectionMatrix
//    )

    // Handle tracking failures.
//    if (camera.trackingState != TrackingState.TRACKING) {
//      val trackFailReason = TrackingStateHelper.getTrackingFailureReasonString(camera)
//      Log.d(TAG, "Tracking Failure Reason: $trackFailReason")
//
//          // Add more logs as needed...
//      return
//    }

    // Draw point cloud.
//    frame.acquirePointCloud().use { pointCloud ->
//      pointCloudRender.drawPointCloud(render, pointCloud, viewProjectionMatrix)
//    }

    // -- Draw occluded virtual objects

    // Update lighting parameters in the shader
    updateLightEstimation(frame.lightEstimate, viewMatrix)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    // Frame.acquireCameraImage must be used on the GL thread.
    // Check if the button was pressed last frame to start processing the camera image.
    val now = SystemClock.uptimeMillis()
    if (scanButtonWasPressed && now - lastAcquireTime >minInterval) {
      lastAcquireTime = now
      Log.e(TAG, "Scan button has been pressed")
//      scanButtonWasPressed = false
      frame.tryAcquireCameraImage()?.use { cameraImage -> //1080x2168 Image
        // Call our ML model on an IO thread.
        val cameraRGB: Bitmap = createBitmap(cameraImage.width, cameraImage.height)
        rgbConverter.yuvToRgb(cameraImage, cameraRGB)
        if (SystemClock.uptimeMillis() <= scanPressTime+100) {
          coroutineScope.launch(Dispatchers.IO) {
            val stream = ByteArrayOutputStream()
            cameraRGB.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val cropCompressedArray = stream.toByteArray()
            overviewImage = Base64.encodeToString(cropCompressedArray, Base64.NO_WRAP)
          }
        }
        coroutineScope.launch(Dispatchers.IO) {
          val cameraId = session.cameraConfig.cameraId
          val imageRotation = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId)
//          screenWidth = 640
//          screenHeight = 480
          Log.d(TAG, "The camera image size is ${screenWidth} x ${screenHeight}")
          objectResultsTemp =
            try {
              currentAnalyzer?.analyze(cameraRGB, screenWidth!!, screenHeight!!, imageRotation)
            } catch (exception: Exception) {
              showSnackbar(
                "Exception thrown analyzing input frame: " +
                        exception.message +
                        "\n" +
                        "See adb log for details."
              )
              Log.e(TAG, "Exception thrown analyzing input frame", exception)
              null
            }
        }
      }
    }

//    if (objectResultsTemp != null) {
//      var intersects = false
//      for (item in objectResultsTemp!!) {
//        val bBox = item.boundingBox
//        if (objectResultsAll.isEmpty()) {
//          synchronized(objectResultsAll) {
//            objectResultsAll = objectResultsTemp as MutableList<DetectionObject>
//            objectResultsAll = objectResultsAll.toList()
//          }
//          objectResultsAdd = objectResultsTemp as MutableList<DetectionObject>
//          break
//        }
//        synchronized(objectResultsAll) {
//          for (obj in objectResultsAll) {
//            val bBoxExisting = obj.boundingBox
//            if (RectF.intersects(bBox, bBoxExisting)
//            ) {
//              intersects = true
//              break
//            }
//          }
//        }
//        if (intersects == false) {
//          synchronized(objectResultsAll) {
//            objectResultsAll.add(item)
//          }
//          objectResultsAdd.add(item)
//        } else {
//          intersects = false
//        }
//      }
//      Log.d(TAG, "Hey We got the following: ${objectResultsAdd}")
//    }
//    Log.e(TAG, "ML Model Processing Complete")
    /** If results were completed this frame, create [Anchor]s from model results. */
//    coroutineScope.launch(Dispatchers.IO) {
//    Log.d(TAG, "Hey We got the following: ${objectResultsAdd}")
    val objects = objectResultsTemp?.toList()
    if (objects != null) {
      Log.d("Count obj", "${objects!!.size}")
      if (objects.isNotEmpty()) {
//      Log.i(TAG, "$currentAnalyzer got objects: $objects")
        val anchors: List<ARLabeledAnchor> = objects.mapNotNull { obj ->
          // Call your helper; if it returns null, skip this obj
          val results: Array<Any?> = createAnchor(
            (obj.boundingBox.left+obj.boundingBox.right)/2f,
            (obj.boundingBox.top + obj.boundingBox.bottom) / 2f,
            frame
          ) ?: return@mapNotNull null

          // Destructure and safe‑cast
          val arAnchor    = results.getOrNull(0) as? Anchor      ?: return@mapNotNull null
          val trackable   = results.getOrNull(1) as? Trackable   ?: return@mapNotNull null
          obj.worldPosition = arAnchor
          val newx = arAnchor.pose.tx()
          val newy = arAnchor.pose.ty()
          Log.i(TAG, "Created anchor ${arAnchor.pose}")
//          var intersects = false
//            if (objectResultsAll.isEmpty()) {
//              synchronized(objectResultsAll) {
//                objectResultsAll.add(obj)
//              }
//            }
          var overlap = false
          var overlap_shelf = false
          if (obj.label == "bay") {
            var yesBay = false
            val posX = obj.worldPosition!!.pose.tx()
            synchronized(bayResultsPoints) {
              for (item in bayResultsPoints) {
                val itemX = item.pose.tx()
                if (posX < itemX*0.8 || posX > itemX*1.2) {
                } else {
                  yesBay = true
                  break
                }
              }
              if (!yesBay) {
                bayResultsPoints.add(obj.worldPosition)
                Log.d("Bay Update", "Second endpoint added")
              }
            }
            overlap = true
          }
          else if (obj.label == "label") {
            synchronized(labelResultsAll) {
              for (item in labelResultsAll) {
                val itemAnchor = item.worldPosition
                if (itemAnchor != null) {
                  val itemx = itemAnchor.pose.tx()
                  val itemy = itemAnchor.pose.ty()
                  val dx = itemx - newx
                  val dy = itemy - newy
                  val distance = sqrt(dx * dx + dy * dy)
                  if (distance < 0.08) {
                    overlap = true
                  }
                  if (sqrt(dy * dy) < 0.08) {
                    overlap_shelf = true
                  }
                }
              }
              if (!overlap) {
                labelResultsAll.add(LabelObject(obj.boundingBox, obj.worldPosition))
              }
            }
            synchronized(shelfResultsAll) {
              if (!overlap_shelf) {
                shelfResultsAll.add(ShelfObject(obj.boundingBox, obj.worldPosition))
              }
            }
//            synchronized(shelfResultsAll) {
//              for (obj in shelfResultsAll) {
//                val bBoxExisting = obj.boundingBox
//                if ((bBoxExisting.top - bBox.top) > 10 || (bBoxExisting.top - bBox.top) < -10) {
//                  shelfResultsAll.add(ShelfObject(bBox))
//                }
//              }
//            }
          } else if (obj.label == "shelf stripping") {
            overlap = true
          } else {
//            synchronized(objectResultsAll) {
              for (item in objectResultsAll) {
                val itemAnchor = item.worldPosition
                if (itemAnchor != null) {
                  val itemx = itemAnchor.pose.tx()
                  val itemy = itemAnchor.pose.ty()
                  val dx = itemx - newx
                  val dy = itemy - newy
                  val distance = sqrt(dx * dx + dy * dy)
                  if (distance < 0.08) {
                    overlap = true
                  }
                }
              }
              if (!overlap) {
                objectResultsAll.add(obj)
              }
//            }
//          } else {
//            if (obj.label == "label") {
//              synchronized(labelResultsAll) {
//                labelResultsAll.add(LabelObject(obj.boundingBox, obj.worldPosition))
//              }
//              synchronized(shelfResultsAll) {
//                shelfResultsAll.add(ShelfObject(obj.boundingBox, obj.worldPosition))
//              }
//            } else if (obj.label == "bay") {
//              Log.d("Bay Update", "First Endpoint")
//              synchronized(bayResultsPoints) {
//                bayResultsPoints.add(obj.worldPosition)
//              }
//              overlap = true
//            } else {
//              synchronized(objectResultsAll) {
//              objectResultsAll.add(obj)
//                }
//            }
          }
          if (!overlap) {
            handleBox(frame, camera, arAnchor, trackable)
            objectResultsAdd.add(obj)
          } else {
            return@mapNotNull null
          }
          // Finally, return exactly an ARLabeledAnchor
          ARLabeledAnchor(arAnchor, obj.labelDisplay)
        }
        Log.d("Count obj", "${anchors!!.size}")
        arLabeledAnchors.addAll(anchors)

        view.post {
          view.resetButton.isEnabled = arLabeledAnchors.isNotEmpty()
//          view.setScanningActive(false)
          when {
//            currentAnalyzer == mlKitAnalyzer &&
//            !mlKitAnalyzer.hasCustomModel() ->
//            showSnackbar(
//              "Default ML Kit classification model returned no results. " +
//                "For better classification performance, see the README to configure a custom model."
//            )
            objects.isEmpty() -> showSnackbar("Classification model returned no results.")
            anchors.size < objectResultsAdd.size ->
              showSnackbar(
                "Objects were classified, but could not be attached to an anchor. " +
                        "Try moving your device around to obtain a better understanding of the environment."
              )
          }
        }
        objectResultsAdd.clear()
        objectResultsTemp = null
      }
    }

//    Log.d(TAG, "We got the following: ${objectResultsAll}")

    // Draw labels at their anchor position.
    synchronized(arLabeledAnchors) {
      for (arDetectedObject in arLabeledAnchors) {
        val anchor = arDetectedObject.anchor
        if (anchor.trackingState != TrackingState.TRACKING) continue
        labelRenderer.draw(
          render,
          viewProjectionMatrix,
          anchor.pose,
          camera.pose,
          arDetectedObject.label,
          virtualSceneFramebuffer
        )
      }
    }

    // Visualize anchors created by touch.
    for ((anchor, trackable) in
    wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
      // Get the current pose of an Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      Matrix.setIdentityM(modelMatrix, 0)

      // translate to the anchor’s world position
      val t = anchor.pose.translation
      Matrix.translateM(modelMatrix, 0, t[0], t[1], t[2])

      // rotate so the object “faces” +Z in world space:
      // e.g. tilt 45° about the X axis, then spin 180° about Y:
      Matrix.rotateM(modelMatrix, 0, 0f, 1f, 0f, 0f)  // pitch
      Matrix.rotateM(modelMatrix, 0, 180f, 0f, 1f, 0f) // yaw

// 2) Scale that matrix in place:
      val scale = 0.6f
      Matrix.scaleM(modelMatrix, 0,
        scale, scale, scale)

// 3) Build modelView:
      val modelViewMatrix = FloatArray(16)
      Matrix.multiplyMM(
        modelViewMatrix, 0,
        viewMatrix,      0,
        modelMatrix, 0
      )

// 4) Build MVP:
      val modelViewProjectionMatrix = FloatArray(16)
      Matrix.multiplyMM(
        modelViewProjectionMatrix, 0,
        projectionMatrix,   0,
        modelViewMatrix,    0
      )

      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      val texture =
        if ((trackable as? InstantPlacementPoint)?.trackingMethod ==
          InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
        ) {
          virtualObjectAlbedoInstantPlacementTexture
        } else {
          virtualObjectAlbedoTexture
        }
      virtualObjectShader.setTexture("u_AlbedoTexture", texture)
      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
    }
    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private fun handleBox(frame: Frame, camera: Camera, anchor: Anchor, trackable: Trackable) {
    if (camera.trackingState != TrackingState.TRACKING) return
//    val tap = activity.view.tapHelper.poll() ?: return

//    val hitResultList =
//      if (activity.instantPlacementSettings.isInstantPlacementEnabled) {
//        frame.hitTestInstantPlacement(x, y, APPROXIMATE_DISTANCE_METERS)
//      } else {
//        return
//      }
//
//    // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, Depth Point,
//    // or Instant Placement Point.
//    val firstHitResult =
//      hitResultList.firstOrNull { hit ->
//        when (val trackable = hit.trackable!!) {
//          is Plane ->
//            trackable.isPoseInPolygon(hit.hitPose) &&
//                    PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
//          is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
//          is InstantPlacementPoint -> true
//          // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
//          is DepthPoint -> true
//          else -> false
//        }
//      }
//
//    if (firstHitResult != null) {
      // Cap the number of objects created. This avoids overloading both the
      // rendering system and ARCore.
    synchronized(wrappedAnchors) {
      if (wrappedAnchors.size >= 80) {
        wrappedAnchors[0].anchor.detach()
        wrappedAnchors.removeAt(0)
      }

      // Adding an Anchor tells ARCore that it should track this position in
      // space. This anchor is created on the Plane to place the 3D model
      // in the correct position relative both to the world and to the plane.
      wrappedAnchors.add(WrappedAnchor(anchor, trackable))
    }
      // For devices that support the Depth API, shows a dialog to suggest enabling
      // depth-based occlusion. This dialog needs to be spawned on the UI thread.
//      activity.runOnUiThread { activity.view.showOcclusionDialogIfNeeded() }
    }

private fun Session.hasTrackingPlane() =
  getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

/** Update state based on the current frame's light estimation. */
private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
  if (lightEstimate.state != LightEstimate.State.VALID) {
    virtualObjectShader.setBool("u_LightEstimateIsValid", false)
    return
  }
  virtualObjectShader.setBool("u_LightEstimateIsValid", true)
  Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
  virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
  updateMainLight(
    lightEstimate.environmentalHdrMainLightDirection,
    lightEstimate.environmentalHdrMainLightIntensity,
    viewMatrix
  )
  updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
  cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
}
  private fun updateMainLight(
    direction: FloatArray,
    intensity: FloatArray,
    viewMatrix: FloatArray
  ) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0]
    worldLightDirection[1] = direction[1]
    worldLightDirection[2] = direction[2]
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
    virtualObjectShader.setVec3("u_LightIntensity", intensity)
  }

  private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
    require(coefficients.size == 9 * 3) {
      "The given coefficients array must be of length 27 (3 components per 9 coefficients"
    }

    // Apply each factor to every component of each coefficient
    for (i in 0 until 9 * 3) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
    }
    virtualObjectShader.setVec3Array(
      "u_SphericalHarmonicsCoefficients",
      sphericalHarmonicsCoefficients
    )
  }

  /**
   * Utility method for [Frame.acquireCameraImage] that maps [NotYetAvailableException] to `null`.
   */
  fun Frame.tryAcquireCameraImage() =
    try {
      acquireCameraImage()
    } catch (e: NotYetAvailableException) {
      null
    } catch (e: Throwable) {
      throw e
    }

  private fun showSnackbar(message: String): Unit =
    activity.view.snackbarHelper.showMessageWithDismiss(activity, message)

  private fun hideSnackbar() = activity.view.snackbarHelper.hide(activity)

  /** Temporary arrays to prevent allocations in [createAnchor]. */
  private val convertFloats = FloatArray(4)
  private val convertFloatsOut = FloatArray(4)

  /**
   * Create an anchor using (x, y) coordinates in the [Coordinates2d.IMAGE_PIXELS] coordinate space.
   */
  fun createAnchor(xImage: Float, yImage: Float, frame: Frame): Array<Any?>? {
    // IMAGE_PIXELS -> VIEW
    convertFloats[0] = xImage
    convertFloats[1] = yImage
//    frame.transformCoordinates2d(
//      Coordinates2d.IMAGE_PIXELS,
//      convertFloats,
//      Coordinates2d.VIEW,
//      convertFloatsOut
//    )

    // Conduct a hit test using the VIEW coordinates
    val hits = frame.hitTest(convertFloats[0], convertFloats[1])
    val result = hits.getOrNull(0) ?: return null
//    Log.d(TAG, "Input: ${convertFloats[0]}x${convertFloats[1]}, Output: ${result.trackable.createAnchor(result.hitPose)}")
    return arrayOf(result.trackable.createAnchor(result.hitPose), result.trackable)
  }
  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}

private data class WrappedAnchor(
  val anchor: Anchor,
  val trackable: Trackable,
)

data class ARLabeledAnchor(val anchor: Anchor, val label: String)
