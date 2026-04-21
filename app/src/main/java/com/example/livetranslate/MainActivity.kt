package com.example.livetranslate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: TranslationViewModel
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var btnEn: Button
    private lateinit var btnZh: Button
    private lateinit var btnKo: Button
    private lateinit var btnJa: Button

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.error_camera_permission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[TranslationViewModel::class.java]

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        btnEn = findViewById(R.id.btnEn)
        btnZh = findViewById(R.id.btnZh)
        btnKo = findViewById(R.id.btnKo)
        btnJa = findViewById(R.id.btnJa)

        btnEn.setOnClickListener { viewModel.setSourceLanguage(TranslationViewModel.SourceLanguage.ENGLISH) }
        btnZh.setOnClickListener { viewModel.setSourceLanguage(TranslationViewModel.SourceLanguage.CHINESE) }
        btnKo.setOnClickListener { viewModel.setSourceLanguage(TranslationViewModel.SourceLanguage.KOREAN) }
        btnJa.setOnClickListener { viewModel.setSourceLanguage(TranslationViewModel.SourceLanguage.JAPANESE) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    overlayView.update(state.overlays, state.imageWidth, state.imageHeight)
                    updateStatus(state)
                    updateLanguageButtons(state)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateStatus(state: TranslationViewModel.UiState) {
        when (state.modelStatus) {
            TranslationViewModel.ModelStatus.DOWNLOADING -> {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_downloading)
            }
            TranslationViewModel.ModelStatus.ERROR -> {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_error, state.errorMessage)
            }
            TranslationViewModel.ModelStatus.READY -> {
                statusText.visibility = View.GONE
            }
        }
    }

    private fun updateLanguageButtons(state: TranslationViewModel.UiState) {
        val isReady = state.modelStatus == TranslationViewModel.ModelStatus.READY
        btnEn.isEnabled = isReady
        btnZh.isEnabled = isReady
        btnKo.isEnabled = isReady
        btnJa.isEnabled = isReady

        val activeColor = ContextCompat.getColor(this, android.R.color.holo_blue_dark)
        val inactiveColor = ContextCompat.getColor(this, android.R.color.darker_gray)

        btnEn.setBackgroundColor(if (state.sourceLanguage == TranslationViewModel.SourceLanguage.ENGLISH)  activeColor else inactiveColor)
        btnZh.setBackgroundColor(if (state.sourceLanguage == TranslationViewModel.SourceLanguage.CHINESE)  activeColor else inactiveColor)
        btnKo.setBackgroundColor(if (state.sourceLanguage == TranslationViewModel.SourceLanguage.KOREAN)   activeColor else inactiveColor)
        btnJa.setBackgroundColor(if (state.sourceLanguage == TranslationViewModel.SourceLanguage.JAPANESE) activeColor else inactiveColor)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor) { imageProxy ->
                        viewModel.processImage(imageProxy)
                    }
                }

            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
