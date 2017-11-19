package grando.natan.arexample

import android.Manifest
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.google.ar.core.*
import com.tbruyelle.rxpermissions2.RxPermissions
import java.util.concurrent.ArrayBlockingQueue
import grando.natan.arexample.utils.rendering.*
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity: AppCompatActivity(), GLSurfaceView.Renderer {

    private val TAG = "MainActivity"

    private var defaultConfig: Config? = null
    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private var loadingMessageSnackbar: Snackbar? = null

    private val virtualObject = ObjectRenderer()
    private val virtualObjectShadow = ObjectRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloud = PointCloudRenderer()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)

    // Tap handling and UI.
    private val queuedSingleTaps = ArrayBlockingQueue<MotionEvent>(16)
    private val touches = ArrayList<PlaneAttachment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupView()
        requestCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        requestCameraPermission()
    }

    public override fun onPause() {
        super.onPause()
        // Note that the order matters - GLSurfaceView is paused first so that it does not try
        // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
        // still call session.update() and get a SessionPausedException.
        surfaceView.onPause()
        session?.pause()
    }

    private fun setupView() {
        session = Session(this)

        // Create default config, check is supported, create session from that config.
        defaultConfig = Config.createDefaultConfig()
        if (session?.isSupported(defaultConfig) != true) {
            Toast.makeText(this, "This device does not support AR", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Set up tap listener.
        val gestureDetector = GestureDetector(this, object: GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onSingleTap(e)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        surfaceView.setOnTouchListener({ _, event -> gestureDetector.onTouchEvent(event) })

        // Set up renderer.
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun requestCameraPermission() {
        RxPermissions(this).request(Manifest.permission.CAMERA)
                .subscribe {
                    if (it) {
                        onCameraPermissionGranted()
                    } else {
                        Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
    }

    private fun onCameraPermissionGranted() {
        showLoadingMessage()
        // Note that order matters - see the note in onPause(), the reverse applies here.
        session?.resume(defaultConfig)
        surfaceView.onResume()
    }


    //region code from example

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Standard Android full-screen functionality.
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun onSingleTap(e: MotionEvent) {
        // Queue tap if there is space. Tap is lost if queue is full.
        queuedSingleTaps.offer(e)
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Create the texture and pass it to ARCore session to be filled during update().
        backgroundRenderer.createOnGlThread(/*context=*/this)
        session?.setCameraTextureName(backgroundRenderer.textureId)

        // Prepare the other rendering objects.
        try {
            virtualObject.createOnGlThread(/*context=*/this, "andy.obj", "andy.png")
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)

            virtualObjectShadow.createOnGlThread(/*context=*/this,
                    "andy_shadow.obj", "andy_shadow.png")
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read obj file")
        }

        try {
            planeRenderer.createOnGlThread(/*context=*/this, "trigrid.png")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read plane texture")
        }

        pointCloud.createOnGlThread(/*context=*/this)
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        session?.setDisplayGeometry(width.toFloat(), height.toFloat())
    }

    override fun onDrawFrame(gl: GL10) {
        val session = session ?: return

        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        try {
            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session.update()

            // Handle taps. Handling only one tap per frame, as taps are usually low frequency
            // compared to frame rate.
            val tap = queuedSingleTaps.poll()
            if (tap != null && frame?.trackingState == Frame.TrackingState.TRACKING) {
                for (hit in frame.hitTest(tap)) {
                    // Check if any plane was hit, and if it was hit inside the plane polygon.
                    if (hit is PlaneHitResult && hit.isHitInPolygon) {
                        // Cap the number of objects created. This avoids overloading both the
                        // rendering system and ARCore.
                        if (touches.size >= 16) {
                            session.removeAnchors(Arrays.asList(touches[0].anchor))
                            touches.removeAt(0)
                        }
                        // Adding an Anchor tells ARCore that it should track this position in
                        // space. This anchor will be used in PlaneAttachment to place the 3d model
                        // in the correct position relative both to the world and to the plane.

                        touches.add(PlaneAttachment(
                                hit.plane,
                                session.addAnchor(hit.getHitPose())))

                        // Hits are sorted by depth. Consider only closest hit on a plane.
                        break
                    }
                }
            }

            // Draw background.
            backgroundRenderer.draw(frame)

            // If not tracking, don't draw 3d objects.
            if (frame.trackingState == Frame.TrackingState.NOT_TRACKING) {
                return
            }

            // Get projection matrix.
            val projmtx = FloatArray(16)
            session.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

            // Get camera matrix and draw.
            val viewmtx = FloatArray(16)
            frame.getViewMatrix(viewmtx, 0)

            // Compute lighting from average intensity of the image.
            val lightIntensity = frame.lightEstimate.pixelIntensity

            // Visualize tracked points.
            pointCloud.update(frame.pointCloud)
            pointCloud.draw(frame.pointCloudPose, viewmtx, projmtx)

            // Check if we detected at least one plane. If so, hide the loading message.
            if (loadingMessageSnackbar != null) {
                for (plane in session.getAllPlanes()) {
                    if (plane.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING && plane.trackingState == Plane.TrackingState.TRACKING) {
                        hideLoadingMessage()
                        break
                    }
                }
            }

            // Visualize planes.
            planeRenderer.drawPlanes(session.allPlanes, frame.pose, projmtx)

            // Visualize anchors created by touch.
            val scaleFactor = 1.0f
            for (planeAttachment in touches) {
                if (!planeAttachment.isTracking) {
                    continue
                }
                // Get the current combined pose of an Anchor and Plane in world space. The Anchor
                // and Plane poses are updated during calls to session.update() as ARCore refines
                // its estimate of the world.
                planeAttachment.pose.toMatrix(anchorMatrix, 0)

                // Update and draw the model and its shadow.
                virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObject.draw(viewmtx, projmtx, lightIntensity)
                virtualObjectShadow.draw(viewmtx, projmtx, lightIntensity)
            }

        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }

    }

    private fun showLoadingMessage() {
        runOnUiThread {
            loadingMessageSnackbar = Snackbar.make(
                    this@MainActivity.findViewById(android.R.id.content),
                    "Searching for surfaces...", Snackbar.LENGTH_INDEFINITE)
            loadingMessageSnackbar?.view?.setBackgroundColor(-0x40cdcdce)
            loadingMessageSnackbar?.show()
        }
    }

    private fun hideLoadingMessage() {
        runOnUiThread {
            loadingMessageSnackbar?.dismiss()
            loadingMessageSnackbar = null
        }
    }

    //endregion
}
