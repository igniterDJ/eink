package com.keychain.epd

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Lets the user pinch-zoom/drag/rotate a photo into the 250x122 crop frame before
 * it's handed to ImageProcessor. Source/result bitmaps are passed via the companion
 * holder below rather than Intent extras, since a raw Bitmap in an Intent risks
 * TransactionTooLargeException; both activities run in the same process so an
 * in-memory handoff is safe.
 */
class PhotoAdjustActivity : ComponentActivity() {

    companion object {
        var sourceBitmap: Bitmap? = null
        var resultBitmap: Bitmap? = null
    }

    private lateinit var cropFrameView: CropFrameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_adjust)

        // Avoid bottom controls sitting under the status bar/notch.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        cropFrameView = findViewById(R.id.cropFrameView)

        val bitmap = sourceBitmap
        sourceBitmap = null // consumed; avoid leaking a stale Bitmap via the static holder
        if (bitmap == null) {
            finish()
            return
        }
        cropFrameView.setBitmap(bitmap)

        findViewById<Button>(R.id.btnRotateLeft).setOnClickListener { cropFrameView.rotate(-90f) }
        findViewById<Button>(R.id.btnRotateRight).setOnClickListener { cropFrameView.rotate(90f) }
        findViewById<Button>(R.id.btnReset).setOnClickListener { cropFrameView.resetTransform() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnUsePhoto).setOnClickListener {
            resultBitmap = cropFrameView.renderResult()
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cropFrameView.sourceBitmap?.recycle()
    }
}
