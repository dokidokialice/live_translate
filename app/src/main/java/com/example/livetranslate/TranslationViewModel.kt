package com.example.livetranslate

import android.app.Application
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class TranslationViewModel(app: Application) : AndroidViewModel(app) {

    enum class SourceLanguage(val displayName: String, val mlkitCode: String) {
        ENGLISH("EN", TranslateLanguage.ENGLISH),
        CHINESE("中文", TranslateLanguage.CHINESE),
        KOREAN("한국어", TranslateLanguage.KOREAN),
        JAPANESE("日本語", TranslateLanguage.JAPANESE)
    }

    enum class ModelStatus { DOWNLOADING, READY, ERROR }

    data class TextOverlay(
        val translated: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    data class UiState(
        val modelStatus: ModelStatus = ModelStatus.DOWNLOADING,
        val errorMessage: String? = null,
        val sourceLanguage: SourceLanguage = SourceLanguage.ENGLISH,
        val overlays: List<TextOverlay> = emptyList(),
        val imageWidth: Int = 1,
        val imageHeight: Int = 1
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val recognizers: Map<SourceLanguage, TextRecognizer> = mapOf(
        SourceLanguage.ENGLISH  to TextRecognition.getClient(TextRecognizerOptions.Builder().build()),
        SourceLanguage.CHINESE  to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
        SourceLanguage.KOREAN   to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        SourceLanguage.JAPANESE to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    )

    // EN/ZH/KO → JA、JA → EN
    private val translators: Map<SourceLanguage, Translator> = mapOf(
        SourceLanguage.ENGLISH  to buildTranslator(TranslateLanguage.ENGLISH,  TranslateLanguage.JAPANESE),
        SourceLanguage.CHINESE  to buildTranslator(TranslateLanguage.CHINESE,  TranslateLanguage.JAPANESE),
        SourceLanguage.KOREAN   to buildTranslator(TranslateLanguage.KOREAN,   TranslateLanguage.JAPANESE),
        SourceLanguage.JAPANESE to buildTranslator(TranslateLanguage.JAPANESE, TranslateLanguage.ENGLISH)
    )

    // 処理中フラグ (フレームのドロップ判定用)
    @Volatile private var isProcessing = false

    // 翻訳キャッシュ: 同一テキストは再翻訳しない
    private val translationCache = HashMap<String, String>()

    // アンカー: 位置と翻訳テキストを固定し、ぶれ・揺らぎを吸収する
    private data class AnchorState(
        val overlay: TextOverlay,       // 現在表示中のオーバーレイ
        val missedFrames: Int = 0,
        val pendingTranslated: String? = null,  // 更新候補テキスト
        val pendingCount: Int = 0               // 候補が連続検出されたフレーム数
    )
    private var anchors: List<AnchorState> = emptyList()

    init {
        viewModelScope.launch { downloadModels() }
    }

    private fun buildTranslator(src: String, target: String) = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(target)
            .build()
    )

    private suspend fun downloadModels() {
        _uiState.update { it.copy(modelStatus = ModelStatus.DOWNLOADING) }
        try {
            val conditions = DownloadConditions.Builder().build()
            translators.values.forEach { it.downloadModelIfNeeded(conditions).await() }
            _uiState.update { it.copy(modelStatus = ModelStatus.READY) }
        } catch (e: Exception) {
            _uiState.update { it.copy(modelStatus = ModelStatus.ERROR, errorMessage = e.message) }
        }
    }

    fun setSourceLanguage(lang: SourceLanguage) {
        translationCache.clear()
        anchors = emptyList()
        _uiState.update { it.copy(sourceLanguage = lang, overlays = emptyList()) }
    }

    @OptIn(ExperimentalGetImage::class)
    fun processImage(imageProxy: ImageProxy) {
        // モデル未準備 or 前フレーム処理中はスキップ
        if (_uiState.value.modelStatus != ModelStatus.READY || isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        // 回転を考慮した論理サイズ (ML Kit の bounding box はこの座標系)
        val (logW, logH) = if (rotation == 90 || rotation == 270) {
            imageProxy.height to imageProxy.width
        } else {
            imageProxy.width to imageProxy.height
        }
        Log.d(TAG, "frame: proxy=${imageProxy.width}x${imageProxy.height} rot=$rotation logical=${logW}x${logH}")

        isProcessing = true
        val currentLang = _uiState.value.sourceLanguage
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        recognizers[currentLang]!!.process(inputImage)
            .addOnSuccessListener { visionText ->
                viewModelScope.launch {
                    val translator = translators[currentLang]!!
                    val rawOverlays = visionText.textBlocks.mapNotNull { block ->
                        val box = block.boundingBox ?: return@mapNotNull null
                        val text = block.text

                        // ゴミ検出フィルタ
                        val boxW = box.width().toFloat()
                        val boxH = box.height().toFloat()
                        val aspect = if (boxH > 0) boxW / boxH else 0f
                        // 3文字未満、または極端に細長いボックスは除外
                        if (text.trim().length < 3) return@mapNotNull null
                        if (aspect < 0.15f) return@mapNotNull null
                        // 各element の confidence 平均が低ければ除外
                        val confidences = block.lines.flatMap { it.elements }.mapNotNull { it.confidence }
                        val avgConf = if (confidences.isEmpty()) 1f else confidences.average().toFloat()
                        Log.d(TAG, "block conf=$avgConf text=\"${text.take(20)}\"")
                        if (avgConf < MIN_CONFIDENCE) return@mapNotNull null
                        val translated = translationCache[text] ?: try {
                            val result = translator.translate(text).await()
                            translationCache[text] = result
                            result
                        } catch (e: Exception) {
                            return@mapNotNull null
                        }
                        TextOverlay(
                            translated = translated,
                            left = box.left.toFloat(),
                            top = box.top.toFloat(),
                            right = box.right.toFloat(),
                            bottom = box.bottom.toFloat()
                        )
                    }

                    // アンカー方式: 位置を固定し、カメラぶれを吸収
                    val overlays = updateAnchors(rawOverlays)

                    _uiState.update {
                        it.copy(overlays = overlays, imageWidth = logW, imageHeight = logH)
                    }
                    isProcessing = false
                }
            }
            .addOnFailureListener {
                isProcessing = false
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * 新しい検出結果を既存アンカーにマッチングする。
     * - 近い位置に既存アンカーがあれば位置はアンカー側を維持（ぶれ吸収）
     * - マッチしなかったアンカーは MAX_HOLD_FRAMES まで表示を持続
     * - 新規検出はその位置に新しいアンカーを作成
     */
    private fun updateAnchors(newOverlays: List<TextOverlay>): List<TextOverlay> {
        val matched = BooleanArray(anchors.size)
        val nextAnchors = mutableListOf<AnchorState>()

        for (newOv in newOverlays) {
            val nCX = (newOv.left + newOv.right) / 2f
            val nCY = (newOv.top + newOv.bottom) / 2f

            // 最も近いアンカーを探す
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            anchors.forEachIndexed { i, a ->
                if (!matched[i]) {
                    val aCX = (a.overlay.left + a.overlay.right) / 2f
                    val aCY = (a.overlay.top + a.overlay.bottom) / 2f
                    val d = Math.hypot((nCX - aCX).toDouble(), (nCY - aCY).toDouble()).toFloat()
                    if (d < bestDist) { bestDist = d; bestIdx = i }
                }
            }

            if (bestIdx >= 0 && bestDist < ANCHOR_RADIUS) {
                // 既存アンカーにマッチ → 位置は固定
                matched[bestIdx] = true
                val anchor = anchors[bestIdx]
                val newText = newOv.translated

                when {
                    newText == anchor.overlay.translated -> {
                        // 表示中と同じ → そのまま維持
                        nextAnchors.add(anchor.copy(missedFrames = 0, pendingTranslated = null, pendingCount = 0))
                    }
                    newText == anchor.pendingTranslated -> {
                        // 同じ候補がまた来た → カウントアップ
                        val count = anchor.pendingCount + 1
                        if (count >= CONFIRM_FRAMES) {
                            // 確定: テキストを更新
                            nextAnchors.add(AnchorState(anchor.overlay.copy(translated = newText)))
                        } else {
                            nextAnchors.add(anchor.copy(missedFrames = 0, pendingCount = count))
                        }
                    }
                    else -> {
                        // 別の候補 → 新たにカウント開始
                        nextAnchors.add(anchor.copy(missedFrames = 0, pendingTranslated = newText, pendingCount = 1))
                    }
                }
            } else {
                // 新規検出 → 新しいアンカーを作成
                nextAnchors.add(AnchorState(newOv))
            }
        }

        // マッチしなかったアンカーは MAX_HOLD_FRAMES まで表示を維持
        anchors.forEachIndexed { i, a ->
            if (!matched[i] && a.missedFrames < MAX_HOLD_FRAMES) {
                nextAnchors.add(a.copy(missedFrames = a.missedFrames + 1))
            }
        }

        anchors = nextAnchors
        return nextAnchors.map { it.overlay }
    }

    override fun onCleared() {
        recognizers.values.forEach { it.close() }
        translators.values.forEach { it.close() }
        super.onCleared()
    }

    companion object {
        private const val TAG = "LiveTranslate"
        private const val MIN_CONFIDENCE = 0.4f
        // アンカー半径: この距離以内の再検出はアンカー位置を維持 (OCR座標系px)
        private const val ANCHOR_RADIUS = 60f
        // 検出が途切れてもこのフレーム数は表示を維持 (~1秒)
        private const val MAX_HOLD_FRAMES = 8
        // 同じ翻訳がこのフレーム数連続したら表示を更新 (~0.5秒)
        private const val CONFIRM_FRAMES = 4
    }
}
