package org.sih.itantra.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sih.itantra.domain.model.Language
import java.io.File

/**
 * Locates the open-source speech model packs on the device.
 *
 * Layout (same as `models/packs/` on the laptop, see tools/download_language_packs.py):
 *
 *     packs/common/silero_vad.onnx
 *     packs/<lang>/stt/model.int8.onnx + tokens.txt      AI4Bharat IndicConformer / NeMo CTC
 *     packs/<lang>/tts/model.onnx      + tokens.txt      Meta MMS-TTS (VITS)
 *
 * Packs live in the app's external files dir (INSTALL_LANGUAGE_PACKS.bat pushes them there over
 * USB) or in internal storage (pulled from the base station over Wi-Fi). Nothing is fetched from
 * the internet and nothing is bundled in the APK.
 */
class SpeechModelPacks(private val context: Context) {

    data class LanguageStatus(val stt: Boolean, val tts: Boolean)

    private val _mtReady = MutableStateFlow(false)
    /** True when the offline IndicTrans2 translation pack is installed. */
    val mtReady: StateFlow<Boolean> = _mtReady.asStateFlow()

    private val _status = MutableStateFlow<Map<Language, LanguageStatus>>(emptyMap())
    val status: StateFlow<Map<Language, LanguageStatus>> = _status.asStateFlow()

    init {
        refresh()
    }

    /** Where packs are looked for, in order. The first one is the USB push target. */
    val roots: List<File>
        get() = listOfNotNull(context.getExternalFilesDir("packs"), File(context.filesDir, "packs"))

    /** Writable root used when downloading packs from the base station. */
    val internalRoot: File get() = File(context.filesDir, "packs")

    fun refresh() {
        _status.value = Language.entries.associateWith { LanguageStatus(sttFiles(it) != null, ttsFiles(it) != null) }
        _mtReady.value = mtDir() != null
    }

    fun vadModel(): File? = roots.map { File(it, "common/silero_vad.onnx") }.firstOrNull { it.isUsable() }

    /** (model, tokens) for speech recognition, or null if this language's STT pack is missing. */
    fun sttFiles(lang: Language): Pair<File, File>? = roots.firstNotNullOfOrNull { root ->
        val model = File(root, "${lang.code}/stt/model.int8.onnx")
        val tokens = File(root, "${lang.code}/stt/tokens.txt")
        if (model.isUsable() && tokens.isUsable()) model to tokens else null
    }

    /** (model, tokens) for speech synthesis, or null if this language's TTS voice is missing. */
    fun ttsFiles(lang: Language): Pair<File, File>? = roots.firstNotNullOfOrNull { root ->
        val model = File(root, "${lang.code}/tts/model.onnx")
        val tokens = File(root, "${lang.code}/tts/tokens.txt")
        if (model.isUsable() && tokens.isUsable()) model to tokens else null
    }

    /** Folder holding the IndicTrans2 pack (mt/en-indic + mt/indic-en), or null if not installed. */
    fun mtDir(): File? = roots.map { File(it, "mt") }.firstOrNull {
        MT_FILES.all { f -> File(it, f).isUsable() }
    }

    fun hasStt(lang: Language) = sttFiles(lang) != null && vadModel() != null
    fun hasTts(lang: Language) = ttsFiles(lang) != null

    /** Total bytes of installed speech models (for the efficiency/footprint metric). */
    fun installedBytes(): Long = roots.sumOf { root ->
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".onnx") }.sumOf { it.length() }
    }

    private fun File.isUsable() = isFile && length() > 0

    companion object {
        /** Files of the offline translation pack, relative to packs/mt. */
        val MT_FILES = listOf(
            "en-indic/encoder.onnx", "en-indic/decoder.onnx", "en-indic/vocab.json",
            "indic-en/encoder_model.onnx", "indic-en/decoder_model.onnx", "indic-en/decoder_with_past_model.onnx",
            "indic-en/src_vocab.txt", "indic-en/src_merges.txt", "indic-en/tgt_vocab.txt", "indic-en/it2_meta.json"
        )
    }
}
