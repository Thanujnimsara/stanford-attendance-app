package lk.stanford.attendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import lk.stanford.attendance.databinding.ActivityCameraBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * mode = "mark"   : liveness -> face embedding -> server (GPS ekka)
 * mode = "enroll" : liveness -> embeddings 3k -> server
 */
class CameraActivity : AppCompatActivity() {

    private enum class Challenge { BLINK, TURN }
    private enum class Stage { CHALLENGE, CAPTURE, SENDING, DONE }

    private lateinit var b: ActivityCameraBinding
    private lateinit var store: Store
    private lateinit var mode: String
    private var faceNet: FaceNet? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
    }

    @Volatile private var stage = Stage.CHALLENGE
    private var challenge = Challenge.BLINK
    private var sawOpen = false
    private var sawClosed = false
    private var sawTurn = false
    private var lastCapture = 0L
    private val embeddings = mutableListOf<FloatArray>()
    private var photoB64: String? = null

    private val needed get() = if (mode == "enroll") 3 else 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        mode = intent.getStringExtra("mode") ?: "mark"
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish(); return
        }
        try {
            faceNet = FaceNet(this)
        } catch (e: Exception) {
            Log.e(TAG, "Model load fail", e)
            b.tvInstruction.text = "Face model not found. Add mobilefacenet.tflite to the app assets folder"
            return
        }
        resetFlow()
        startCamera()
    }

    private fun resetFlow() {
        embeddings.clear()
        photoB64 = null
        sawOpen = false; sawClosed = false; sawTurn = false
        lastCapture = 0L
        challenge = if (Random.nextBoolean()) Challenge.BLINK else Challenge.TURN
        stage = Stage.CHALLENGE
        b.tvInstruction.text = when (challenge) {
            Challenge.BLINK -> "Look at the camera and blink once"
            Challenge.TURN -> "Turn your head slightly to the left or right, then look back at the camera"
        }
        b.tvStatus.text = if (mode == "enroll") "Face enrollment: 3 captures will be taken" else ""
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || stage == Stage.SENDING || stage == Stage.DONE) {
            proxy.close(); return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        detector.process(InputImage.fromMediaImage(media, rotation))
            .addOnCompleteListener(executor) { task ->
                try {
                    if (task.isSuccessful) handleFaces(task.result, proxy, rotation)
                } catch (e: Exception) {
                    Log.e(TAG, "frame error", e)
                } finally {
                    proxy.close()
                }
            }
    }

    private fun say(msg: String) = runOnUiThread { b.tvStatus.text = msg }

    private fun handleFaces(faces: List<Face>, proxy: ImageProxy, rotation: Int) {
        if (faces.size != 1) {
            say(if (faces.isEmpty()) "Show your face to the camera" else "Only one person should be in front of the camera")
            return
        }
        val f = faces[0]
        val yaw = f.headEulerAngleY
        val pitch = f.headEulerAngleX
        val l = f.leftEyeOpenProbability ?: -1f
        val r = f.rightEyeOpenProbability ?: -1f

        when (stage) {
            Stage.CHALLENGE -> {
                if (challenge == Challenge.BLINK) {
                    if (l < 0 || r < 0) return
                    val open = l > 0.7f && r > 0.7f
                    val closed = l < 0.25f && r < 0.25f
                    if (open) {
                        if (sawClosed) passChallenge() else sawOpen = true
                    } else if (closed && sawOpen) {
                        sawClosed = true
                    }
                } else {
                    if (abs(yaw) > 20f) sawTurn = true
                    else if (abs(yaw) < 10f && sawTurn) passChallenge()
                }
            }

            Stage.CAPTURE -> {
                val frontal = abs(yaw) < 12f && abs(pitch) < 15f && l > 0.6f && r > 0.6f
                if (!frontal) { say("Look straight at the camera with your eyes open"); return }
                val now = SystemClock.elapsedRealtime()
                if (now - lastCapture < 700) return

                val full = upright(proxy.toBitmap(), rotation)
                val face = cropFace(full, f.boundingBox) ?: return
                embeddings.add(faceNet!!.embed(face))
                if (embeddings.size == 1) photoB64 = encodePhoto(full)
                lastCapture = now
                say("Capture ${embeddings.size}/$needed")
                if (embeddings.size >= needed) {
                    stage = Stage.SENDING
                    submit()
                }
            }

            else -> Unit
        }
    }

    private fun passChallenge() {
        stage = Stage.CAPTURE
        runOnUiThread {
            b.tvInstruction.text = "Good! Now look straight at the camera"
            b.tvStatus.text = ""
        }
    }

    private fun upright(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun cropFace(src: Bitmap, box: Rect): Bitmap? {
        val padX = (box.width() * 0.15f).toInt()
        val padY = (box.height() * 0.15f).toInt()
        val left = max(0, box.left - padX)
        val top = max(0, box.top - padY)
        val right = min(src.width, box.right + padX)
        val bottom = min(src.height, box.bottom + padY)
        if (right - left < 40 || bottom - top < 40) return null
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    private fun encodePhoto(src: Bitmap): String {
        val scale = 480f / max(src.width, src.height)
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun embeddingJson(e: FloatArray) = JSONArray().apply { e.forEach { put(it.toDouble()) } }

    private fun submit() {
        runOnUiThread { b.tvInstruction.text = "Sending to the server..."; b.tvStatus.text = "" }
        lifecycleScope.launch {
            val token = store.token
            if (token == null) { goLogin(); return@launch }

            val r: JSONObject
            if (mode == "enroll") {
                val arr = JSONArray()
                embeddings.toList().forEach { arr.put(embeddingJson(it)) }
                r = Api.call("enroll", JSONObject().put("token", token).put("embeddings", arr))
            } else {
                val loc = LocationHelper.current(this@CameraActivity)
                if (loc == null) { done("Could not get your GPS location", false); return@launch }
                r = Api.call(
                    "mark",
                    JSONObject()
                        .put("token", token)
                        .put("lat", loc.latitude)
                        .put("lng", loc.longitude)
                        .put("accuracy", loc.accuracy.toDouble())
                        .put("is_mock", LocationHelper.isMock(loc))
                        .put("embedding", embeddingJson(embeddings[0]))
                        .put("photo", photoB64 ?: "")
                )
            }

            if (r.optInt("http") == 401) { goLogin(); return@launch }
            if (r.optBoolean("ok")) {
                val msg = if (mode == "enroll") {
                    "Face enrolled successfully"
                } else {
                    val label = if (r.optString("type") == "in") "Check-in" else "Check-out"
                    val note = r.optString("note")
                    "$label recorded at ${r.optString("time")}" + if (note.isNotEmpty()) "\n$note" else ""
                }
                done(msg, true)
            } else {
                done(r.optString("error", "Error"), false)
            }
        }
    }

    private fun done(msg: String, ok: Boolean) {
        runOnUiThread {
            b.tvInstruction.text = msg
            b.tvStatus.text = if (ok) "" else "Trying again..."
            if (ok) {
                stage = Stage.DONE
                b.root.postDelayed({ finish() }, 2500)
            } else {
                b.root.postDelayed({ resetFlow() }, 3000)
            }
        }
    }

    private fun goLogin() {
        store.clear()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        detector.close()
        faceNet?.close()
    }

    companion object { private const val TAG = "CameraActivity" }
}
