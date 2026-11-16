package lk.stanford.attendance

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * MobileFaceNet (float32) TFLite model eka.
 * Model eka: app/src/main/assets/mobilefacenet.tflite
 * Input size saha output size model eken thamai ganne (112x112 -> 192 wage).
 */
class FaceNet(ctx: Context) {
    private val interpreter: Interpreter
    private val inW: Int
    private val inH: Int
    private val outDim: Int

    init {
        val fd = ctx.assets.openFd("mobilefacenet.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(buf, Interpreter.Options().setNumThreads(2))
        val shape = interpreter.getInputTensor(0).shape()   // [1, H, W, 3]
        inH = shape[1]
        inW = shape[2]
        outDim = interpreter.getOutputTensor(0).shape().last()
    }

    fun embed(face: Bitmap): FloatArray {
        val bmp = Bitmap.createScaledBitmap(face, inW, inH, true)
        val input = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        val px = IntArray(inW * inH)
        bmp.getPixels(px, 0, inW, 0, 0, inW, inH)
        for (p in px) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        val out = Array(1) { FloatArray(outDim) }
        interpreter.run(input, out)

        val v = out[0]
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    fun close() = interpreter.close()
}
