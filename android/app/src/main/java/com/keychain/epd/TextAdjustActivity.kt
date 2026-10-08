package com.keychain.epd

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.Executors

/**
 * Full-screen multi-layer text editor: add/select/drag/pinch-scale+rotate any number
 * of independent text layers into the 250x122 canvas, then hand the rendered bitmap
 * back to MainActivity via the companion holder below (same in-memory pattern as
 * PhotoAdjustActivity, avoids TransactionTooLargeException).
 */
class TextAdjustActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TextAdjustActivity"
        var resultBitmap: Bitmap? = null

        /** Optional photo to preload as the canvas background (e.g. carried over from MainActivity). */
        var backgroundBitmap: Bitmap? = null
    }

    private lateinit var textCanvasView: TextCanvasView
    private lateinit var editTextContent: EditText
    private lateinit var btnDelete: Button
    private var suppressTextListener = false
    private var lastUsedColor = Color.BLACK
    private val bgImageExecutor = Executors.newSingleThreadExecutor()

    private val pickBackgroundImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadBackgroundImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_adjust)

        // Keep editor controls below the status bar/notch.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        textCanvasView = findViewById(R.id.textCanvasView)
        editTextContent = findViewById(R.id.editTextContent)
        btnDelete = findViewById(R.id.btnDelete)
        textCanvasView.setBackgroundFillColor(Color.WHITE)

        val preloadedBackground = backgroundBitmap
        backgroundBitmap = null
        if (preloadedBackground != null) {
            textCanvasView.setBackgroundImage(preloadedBackground)
        }

        textCanvasView.onSelectionChanged = { item ->
            btnDelete.isEnabled = item != null
            editTextContent.isEnabled = item != null
            suppressTextListener = true
            editTextContent.setText(item?.text.orEmpty())
            item?.let { editTextContent.setSelection(it.text.length) }
            suppressTextListener = false
        }
        btnDelete.isEnabled = false
        editTextContent.isEnabled = false

        editTextContent.doAfterTextChanged {
            if (!suppressTextListener) {
                textCanvasView.setSelectedText(it?.toString().orEmpty())
            }
        }

        findViewById<Button>(R.id.btnAddText).setOnClickListener {
            textCanvasView.addItem(lastUsedColor)
            editTextContent.requestFocus()
            editTextContent.post {
                getSystemService<InputMethodManager>()?.showSoftInput(editTextContent, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        btnDelete.setOnClickListener {
            textCanvasView.deleteSelected()
        }

        findViewById<View>(R.id.textColorBlack).setOnClickListener { setSelectedColor(Color.BLACK) }
        findViewById<View>(R.id.textColorWhite).setOnClickListener { setSelectedColor(Color.WHITE) }
        findViewById<View>(R.id.textColorRed).setOnClickListener { setSelectedColor(Color.RED) }

        findViewById<View>(R.id.bgColorBlack).setOnClickListener { textCanvasView.setBackgroundFillColor(Color.BLACK) }
        findViewById<View>(R.id.bgColorWhite).setOnClickListener { textCanvasView.setBackgroundFillColor(Color.WHITE) }
        findViewById<View>(R.id.bgColorRed).setOnClickListener { textCanvasView.setBackgroundFillColor(Color.RED) }
        findViewById<Button>(R.id.btnBgImage).setOnClickListener {
            pickBackgroundImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<Button>(R.id.btnUseText).setOnClickListener {
            resultBitmap = textCanvasView.renderResult()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun setSelectedColor(color: Int) {
        lastUsedColor = color
        textCanvasView.setSelectedColor(color)
    }

    private fun loadBackgroundImage(uri: Uri) {
        bgImageExecutor.execute {
            try {
                val bitmap = decodeDownsampledAndOrientedBitmap(contentResolver, uri)
                runOnUiThread { textCanvasView.setBackgroundImage(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "loadBackgroundImage failed", e)
                runOnUiThread { Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bgImageExecutor.shutdown()
    }
}
