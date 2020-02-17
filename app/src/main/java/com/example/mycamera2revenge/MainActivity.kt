package com.example.mycamera2revenge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import java.io.FileOutputStream


private const val REQUEST_CODE_PERMISSIONS = 10
private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA
)

class MainActivity : AppCompatActivity() {

    private var cameraDevice: CameraDevice? = null
    private var captureRequest: CaptureRequest? = null
    private val imageReader = ImageReader.newInstance(720, 1280, ImageFormat.JPEG, 1)
    private val handler = Handler()
    private val drawhander = Handler()
    private var cascadeDetector: CascadeClassifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        imageReader.setOnImageAvailableListener(imageAvailableListener, null)
        OpenCVLoader.initDebug()

        //カスケード分類機の生成 {{{
        val inStream = this.resources.openRawResource(R.raw.haarcascade_frontalface_alt2)
        val cascadeDir = getDir("cascade", Context.MODE_PRIVATE)
        var fileName = resources.getResourceName(R.raw.haarcascade_frontalface_alt2) + ".xml"
        val regex = """.*:raw/""".toRegex()
        fileName = regex.replace(fileName,"")

        val cascadeFile = File(cascadeDir,fileName)

        val outStream =FileOutputStream(cascadeFile)
        val buf= ByteArray(2048)

        do{
            val rdBytes=inStream.read(buf)
            if(rdBytes==-1){
                break
            }
            outStream.write(buf,0,rdBytes)
        }while(true)
        outStream.close()
        inStream.close()
        this.cascadeDetector = CascadeClassifier(cascadeFile.absolutePath)
        // }}}
    }
    override fun onResume() {
        super.onResume()
        texture_view.surfaceTextureListener = surfaceTextureListener
    }
    private val surfaceTextureListener = object: TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {}
        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean = true
        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {}
    }
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager.openCamera("0", cameraDeviceStateCallback, handler)
    }
    private val cameraDeviceStateCallback = object: CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
            window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
        override fun onDisconnected(p0: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
        }
        override fun onError(p0: CameraDevice, p1: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }
    private fun createCameraPreviewSession () {
        val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(imageReader.surface)

        captureRequest = previewRequestBuilder.build()
        cameraDevice?.createCaptureSession(listOf(imageReader.surface), captureSessionStateCallback, drawhander)
    }
    private val captureSessionStateCallback = object: CameraCaptureSession.StateCallback () {
        override fun onConfigured(session: CameraCaptureSession) {
            session.setRepeatingRequest(captureRequest!!, null, null)
        }
        override fun onConfigureFailed(p0: CameraCaptureSession) {}
    }
    // ImageReader
    private val imageAvailableListener = ImageReader.OnImageAvailableListener {

        val image = imageReader.acquireLatestImage()
        val buffer = image.planes[0].buffer

        val imageByte = ByteArray(buffer.remaining())
        buffer.get(imageByte)
        image.close()

        var options = BitmapFactory.Options()
        options.inMutable = true
        var bitmap = BitmapFactory.decodeByteArray(imageByte,0, imageByte.size, options)

        val centerX = image_view.width / 2f
        val centerY = image_view.height / 2f
        val matrix = Matrix()
        val degree = when(windowManager.defaultDisplay.rotation) {
            0 -> 90
            1 -> 0
            3 -> 180
            else -> 0
        }
        matrix.preRotate(degree.toFloat(), centerX, centerY)
        // matrix.preScale(-1f, 1f) // インカメのとき
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)

        val scaleFactor = 4.0
        val floatScaleFactor = scaleFactor.toFloat()
        var org_mat = Mat()
        var resized = Mat()
        val faceRect = MatOfRect()

        Utils.bitmapToMat(bitmap, org_mat)
        Imgproc.resize(org_mat, resized, Size(bitmap.width / scaleFactor, bitmap.height / scaleFactor))
        cascadeDetector!!.detectMultiScale(resized, faceRect, 2.2, 1, 0, Size(10.0, 10.0))

        if (faceRect.toArray().size > 0){
            val paint = Paint()
            val canvas = Canvas(bitmap)
            paint.setColor(Color.argb(255, 255, 0, 255))
            paint.setStrokeWidth(2f)
            paint.setStyle(Paint.Style.STROKE)
            for (rect in faceRect.toArray()){
                val x = rect.x * floatScaleFactor
                val y = rect.y * floatScaleFactor
                val width = rect.width * floatScaleFactor
                val height = rect.height * floatScaleFactor
                canvas.drawRect(x, y, x + width, y + height, paint)
                Log.d("Faces", "${rect}")
            }
        }
        image_view.setImageBitmap(bitmap)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}