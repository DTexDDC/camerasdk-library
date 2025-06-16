package com.datdt.camerasdk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.datdt.camerasdk.AppRenderer.Companion
import com.datdt.camerasdk.java.common.helpers.FullScreenHelper
import com.datdt.camerasdk.java.common.helpers.DepthSettings
import com.datdt.camerasdk.java.common.helpers.InstantPlacementSettings
import com.datdt.camerasdk.java.common.samplerender.SampleRender
import com.datdt.camerasdk.models.DetectionObject
import com.datdt.camerasdk.models.ModelType
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CameraSdk {
    fun startDetection(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
        context.startActivity(intent)
    }
}

object DetectionManager {
    private val _detectionPayload = MutableStateFlow(
        DetectionPayload(emptyList(), "")
    )
    val detectionPayload: StateFlow<DetectionPayload> get() = _detectionPayload

    fun updateDetections(newPayload: DetectionPayload) {
        _detectionPayload.value = newPayload
    }
}

class MainActivity : AppCompatActivity() {
    val TAG = "MainActivity"
    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper

    lateinit var renderer: AppRenderer
    lateinit var view: MainActivityView

    val instantPlacementSettings = InstantPlacementSettings()
    val depthSettings = DepthSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
        // When session creation or session.resume fails, we display a message and log detailed
        // information.
        arCoreSessionHelper.exceptionCallback =
            { exception ->
                val message =
                    when (exception) {
                        is UnavailableArcoreNotInstalledException,
                        is UnavailableUserDeclinedInstallationException -> "Please install ARCore"
                        is UnavailableApkTooOldException -> "Please update ARCore"
                        is UnavailableSdkTooOldException -> "Please update this app"
                        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                        is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                        else -> "Failed to create AR session: $exception"
                    }
                Log.e(TAG, message, exception)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }

//        arCoreSessionHelper.beforeSessionResume =
//            { session ->
//                session.configure(
//                    session.config.apply {
//                        // To get the best image of the object in question, enable autofocus.
//                        focusMode = Config.FocusMode.AUTO
//                        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                            depthMode = Config.DepthMode.AUTOMATIC
//                        }
//                    }
//                )
        arCoreSessionHelper.beforeSessionResume = ::configureSession
        lifecycle.addObserver(arCoreSessionHelper)

//                val filter =
//                    CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK)
//                val configs = session.getSupportedCameraConfigs(filter)
//                val sort =
//                    compareByDescending<CameraConfig> { it.imageSize.width }.thenByDescending {
//                        it.imageSize.height
//                    }
//                val sortedConfigs = configs.sortedWith(sort)
//                if (sortedConfigs.isNotEmpty()) {
//                    session.cameraConfig = sortedConfigs[0]
//                } else {
//                    // Handle the case where no configuration is available.
//                    Log.e(TAG, "There is no available camera config.")
//                }
////                session.cameraConfig = configs.sortedWith(sort)[0]
//            }
//        lifecycle.addObserver(arCoreSessionHelper)

        val modelType = ModelType.OUTPUT_FLOAT32
        renderer = AppRenderer(this, modelType)
        lifecycle.addObserver(renderer)
        view = MainActivityView(this, renderer)
        setContentView(view.root)
        renderer.bindView(view)
        lifecycle.addObserver(view)

//        SampleRender(view.surfaceView, renderer, assets)

        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)

        if (view != null) {
            Log.e(TAG, "Passed Main")
        }
    }

    // Configure the session, using Lighting Estimation, and Depth mode.
    fun configureSession(session: Session) {
        session.configure(
            session.config.apply {
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                focusMode = Config.FocusMode.AUTO
                // Depth API is used if it is configured in Hello AR's settings.
                depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }

                // Instant Placement is used if it is configured in Hello AR's settings.
                instantPlacementMode =
                    if (instantPlacementSettings.isInstantPlacementEnabled) {
                        Config.InstantPlacementMode.LOCAL_Y_UP
                    } else {
                        Config.InstantPlacementMode.DISABLED
                    }

                                val filter =
                    CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK)
                val configs = session.getSupportedCameraConfigs(filter)
                val sort =
                    compareByDescending<CameraConfig> { it.imageSize.width }.thenByDescending {
                        it.imageSize.height
                    }
                val sortedConfigs = configs.sortedWith(sort)
                if (sortedConfigs.isNotEmpty()) {
                    session.cameraConfig = sortedConfigs[0]
                } else {
                    // Handle the case where no configuration is available.
                    Log.e(TAG, "There is no available camera config.")
                }
//                session.cameraConfig = configs.sortedWith(sort)[0]
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        arCoreSessionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }
}