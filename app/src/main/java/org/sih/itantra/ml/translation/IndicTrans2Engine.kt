package org.sih.itantra.ml.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sih.itantra.domain.model.Language
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Fully offline neural translation between English and the Indian languages with AI4Bharat's
 * IndicTrans2 (distilled 200M, int8 ONNX, MIT), run by ONNX Runtime.
 *
 *  en -> indic   : models in <dir>/en-indic   (SentencePiece-BPE by score; encoder emits cross K/V)
 *  indic -> en   : models in <dir>/indic-en   (BPE by merge rank; decoder + decoder_with_past)
 *  indic -> indic: pivot through English
 *
 * IndicTrans2 reads and writes every Indian language in Devanagari: input is converted to
 * Devanagari by Unicode offset and output is converted back (indic-nlp's transliterator, with its
 * Tamil exceptions). Every step mirrors tools/indictrans2_reference.py.
 *
 * Pure JVM (no Android APIs) so the same class is unit-tested on the laptop.
 * @param keepLoaded false on low-RAM phones: sessions are closed after each translation.
 */
class IndicTrans2Engine(
    private val modelDir: () -> File?,
    private val keepLoaded: Boolean = true,
    private val threads: Int = 2
) {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var enIndic: EnIndic? = null
    private var indicEn: IndicEn? = null

    fun isAvailable(): Boolean = modelDir()?.let {
        File(it, "en-indic/encoder.onnx").isFile && File(it, "indic-en/encoder_model.onnx").isFile
    } == true

    /** Translated text, or null if the models are missing, the pair is unsupported or inference fails. */
    @Synchronized
    fun translate(text: String, source: Language, target: Language): String? {
        if (text.isBlank() || source == target || !isAvailable()) return null
        if (FLORES[source] == null || FLORES[target] == null) return null
        return try {
            when {
                source == Language.ENGLISH -> enIndic().translate(text, target)
                target == Language.ENGLISH -> indicEn().translate(text, source)
                else -> enIndic().translate(indicEn().translate(text, source), target)
            }.trim().ifBlank { null }
        } catch (t: Throwable) {
            System.err.println("IndicTrans2 failed: ${t.message}")
            null
        } finally {
            if (!keepLoaded) release()
        }
    }

    @Synchronized
    fun release() {
        enIndic?.close(); enIndic = null
        indicEn?.close(); indicEn = null
    }

    private fun enIndic() = enIndic ?: EnIndic(File(modelDir()!!, "en-indic")).also { enIndic = it }
    private fun indicEn() = indicEn ?: IndicEn(File(modelDir()!!, "indic-en")).also { indicEn = it }

    private fun session(file: File): OrtSession {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(file.absolutePath, opts)
    }

    private fun idsTensor(ids: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))

    private fun argmaxLast(t: OnnxTensor): Int {
        val buf = t.floatBuffer
        val shape = t.info.shape
        val vocab = shape.last().toInt()
        val start = buf.limit() - vocab
        var best = start
        for (i in start until buf.limit()) if (buf.get(i) > buf.get(best)) best = i
        return best - start
    }

    // ------------------------------------------------------------------ en -> indic

    private inner class EnIndic(dir: File) {
        private val score = HashMap<String, Float>()
        private val srcDict = HashMap<String, Int>()
        private val tgt: Array<String>
        private val layers: Int
        private val startId: Int
        private val eosId: Int
        private val enc = session(File(dir, "encoder.onnx"))
        private val dec = session(File(dir, "decoder.onnx"))

        init {
            val v = Json.parseToJsonElement(File(dir, "vocab.json").readText()).jsonObject
            val meta = v["meta"]!!.jsonObject
            layers = meta.int("layers")
            startId = meta.int("decoder_start_token_id")
            eosId = meta.int("eos_token_id")
            val pieces = v["src_pieces"]!!.jsonArray
            val scores = v["src_scores"]!!.jsonArray
            val types = v["src_types"]!!.jsonArray
            for (i in pieces.indices) {
                if (types[i].jsonPrimitive.int == 1) score[pieces[i].jsonPrimitive.content] = scores[i].jsonPrimitive.float
            }
            for ((k, id) in v["src_dict"]!!.jsonObject) srcDict[k] = id.jsonPrimitive.int
            tgt = v["tgt_tokens"]!!.jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
        }

        fun encode(text: String, target: Language): LongArray {
            val norm = Normalizer.normalize(tokenizeEn(punctNorm(text)), Normalizer.Form.NFKC)
            val ids = ArrayList<Long>()
            ids += srcDict.getValue("eng_Latn").toLong()
            ids += srcDict.getValue(FLORES.getValue(target)).toLong()
            for (w in norm.split(" ")) {
                if (w.isEmpty()) continue
                for (p in bpe("▁$w") { a, b -> score[a + b]?.let { -it } }) ids += (srcDict[p] ?: 3).toLong()
            }
            ids += 2L
            return ids.toLongArray()
        }

        fun translate(text: String, target: Language): String {
            val ids = encode(text, target)
            val input = idsTensor(ids)
            val mask = idsTensor(LongArray(ids.size) { 1L })
            val encRes = enc.run(mapOf("input_ids" to input, "attention_mask" to mask))
            val out = ArrayList<Int>()
            var prev: OrtSession.Result? = null
            try {
                val cross = HashMap<String, OnnxTensor>()
                for (i in 0 until layers) {
                    cross["cross_k_$i"] = encRes.get("cross_k_$i").get() as OnnxTensor
                    cross["cross_v_$i"] = encRes.get("cross_v_$i").get() as OnnxTensor
                }
                val empty = encRes.get("empty_cache").get() as OnnxTensor
                var tok = startId
                for (step in 0 until minOf(ids.size * 2 + 10, 256)) {
                    val stepIds = idsTensor(longArrayOf(tok.toLong()))
                    val feed = HashMap<String, OnnxTensor>(cross)
                    feed["input_ids"] = stepIds
                    feed["encoder_attention_mask"] = mask
                    for (i in 0 until layers) {
                        feed["self_k_$i"] = (prev?.get("present_k_$i")?.get() as? OnnxTensor) ?: empty
                        feed["self_v_$i"] = (prev?.get("present_v_$i")?.get() as? OnnxTensor) ?: empty
                    }
                    val res = dec.run(feed)
                    stepIds.close()
                    prev?.close()
                    prev = res
                    tok = argmaxLast(res.get("logits").get() as OnnxTensor)
                    if (tok == eosId) break
                    out += tok
                }
            } finally {
                prev?.close()
                encRes.close()
                input.close()
                mask.close()
            }
            val deva = out.filter { it > 3 }
                .joinToString("") { tgt[it] }.replace('▁', ' ').trim()
            var res = transliterate(deva, "hi", target.code)
            if (target == Language.ODIA) res = res.replace("ଯ଼", "ୟ")
            return detok(res)
        }

        fun close() { enc.close(); dec.close() }
    }

    // ------------------------------------------------------------------ indic -> en

    private inner class IndicEn(dir: File) {
        private val vocab = HashMap<String, Int>()
        private val merges = HashMap<String, Int>()
        private val tgt: Array<String>
        private val special: JsonObject
        private val srcDictSize: Int
        private val unkId: Int
        private val eosId: Int
        private val startId: Int
        private val enc = session(File(dir, "encoder_model.onnx"))
        private val dec0 = session(File(dir, "decoder_model.onnx"))
        private val dec = session(File(dir, "decoder_with_past_model.onnx"))

        init {
            val meta = Json.parseToJsonElement(File(dir, "it2_meta.json").readText()).jsonObject
            special = meta["src_special"]!!.jsonObject
            srcDictSize = meta.int("src_dict_size")
            unkId = meta.int("unk_id")
            eosId = meta.int("eos_id")
            startId = meta.int("decoder_start_id")
            File(dir, "src_vocab.txt").useLines { lines -> lines.forEachIndexed { i, l -> vocab.putIfAbsent(l, i) } }
            File(dir, "src_merges.txt").useLines { lines ->
                lines.forEachIndexed { i, l -> if (l.count { it == ' ' } == 1) merges.putIfAbsent(l, i) }
            }
            tgt = File(dir, "tgt_vocab.txt").readLines().toTypedArray()
        }

        fun encode(text: String, source: Language): LongArray {
            var t = text
            if (source != Language.HINDI && source != Language.MARATHI) t = transliterate(t, source.code, "hi")
            t = tokenizeIndic(punctNorm(t))
            val ids = ArrayList<Long>()
            ids += special.int(FLORES.getValue(source)).toLong()
            ids += special.int("eng_Latn").toLong()
            for (raw in t.split(" ")) {
                if (raw.isEmpty()) continue
                val w = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                for (p in bpe("▁$w") { a, b -> merges["$a $b"]?.toFloat() }) {
                    val id = vocab[p] ?: unkId
                    ids += (if (id < srcDictSize) id else unkId).toLong()
                }
            }
            ids += eosId.toLong()
            return ids.toLongArray()
        }

        fun translate(text: String, source: Language): String {
            val ids = encode(text, source)
            val input = idsTensor(ids)
            val mask = idsTensor(LongArray(ids.size) { 1L })
            val encRes = enc.run(mapOf("input_ids" to input, "attention_mask" to mask))
            val out = ArrayList<Int>()
            val results = ArrayList<OrtSession.Result>()
            try {
                val hidden = encRes.get("last_hidden_state").get() as OnnxTensor
                val first = idsTensor(longArrayOf(startId.toLong()))
                val res = dec0.run(mapOf("input_ids" to first, "encoder_attention_mask" to mask, "encoder_hidden_states" to hidden))
                first.close()
                results += res
                // Current KV cache by name (present.* of the latest step; encoder cache from step 0)
                val cache = HashMap<String, OnnxTensor>()
                fun absorb(r: OrtSession.Result) {
                    for ((name, value) in r) if (name.startsWith("present.")) {
                        cache["past_key_values." + name.removePrefix("present.")] = value as OnnxTensor
                    }
                }
                absorb(res)
                var logits = res.get("logits").get() as OnnxTensor
                for (step in 0 until minOf(ids.size * 2 + 10, 256)) {
                    val tok = argmaxLast(logits)
                    if (tok == eosId) break
                    out += tok
                    val stepIds = idsTensor(longArrayOf(tok.toLong()))
                    val feed = HashMap<String, OnnxTensor>(cache)
                    feed["input_ids"] = stepIds
                    feed["encoder_attention_mask"] = mask
                    val next = dec.run(feed)
                    stepIds.close()
                    absorb(next)
                    logits = next.get("logits").get() as OnnxTensor
                    results += next
                    // Every step re-emits the full cache (decoder and encoder parts), so only the
                    // latest result is still referenced; older ones can be freed.
                    while (results.size > 1) results.removeAt(0).close()
                }
            } finally {
                results.forEach { it.close() }
                encRes.close()
                input.close()
                mask.close()
            }
            return detok(out.filter { it > 3 }.joinToString("") { tgt[it] }.replace('▁', ' ').trim())
        }

        fun close() { enc.close(); dec0.close(); dec.close() }
    }

    companion object {
        val FLORES = mapOf(
            Language.ENGLISH to "eng_Latn", Language.HINDI to "hin_Deva", Language.MARATHI to "mar_Deva",
            Language.BENGALI to "ben_Beng", Language.PUNJABI to "pan_Guru", Language.GUJARATI to "guj_Gujr",
            Language.ODIA to "ory_Orya", Language.TAMIL to "tam_Taml", Language.TELUGU to "tel_Telu",
            Language.KANNADA to "kan_Knda", Language.MALAYALAM to "mal_Mlym"
        )
        private val SCRIPT_BASE = mapOf(
            "hi" to 0x0900, "mr" to 0x0900, "bn" to 0x0980, "pa" to 0x0A00, "gu" to 0x0A80, "or" to 0x0B00,
            "ta" to 0x0B80, "te" to 0x0C00, "kn" to 0x0C80, "ml" to 0x0D00
        )
        private val DIGIT_BLOCKS = SCRIPT_BASE.values.toSet()
        private val PUNCT = Regex("""([.,!?;:"()\[\]{}।॥|])""")
        private val SPACES = Regex("""\s+""")

        private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int

        /** indic-nlp UnicodeIndicTransliterator, including its Tamil exceptions. */
        fun transliterate(text: String, src: String, tgt: String): String {
            val sb = SCRIPT_BASE[src] ?: return text
            val tb = SCRIPT_BASE[tgt] ?: return text
            if (src == tgt) return text
            val out = StringBuilder(text.length)
            for (c in text) {
                var off = c.code - sb
                if (off in 0..0x6F && c != '।' && c != '॥') {
                    if (tgt == "ta") off = tamilOffset(off)
                    out.append((tb + off).toChar())
                } else {
                    out.append(c)
                }
            }
            return out.toString()
        }

        private fun tamilOffset(o: Int): Int {
            var off = o
            if (off in 0x15..0x28 && off != 0x1C && !((off - 0x15) % 5 == 0 || (off - 0x15) % 5 == 4)) {
                off = 0x15 + 5 * ((off - 0x15) / 5)
            }
            if (off == 0x2B || off == 0x2C || off == 0x2D) off = 0x2A
            if (off == 0x36) off = 0x37
            return off
        }

        fun punctNorm(text: String): String = text
            .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
            .replace("–", "-").replace("—", " - ").replace("…", "...")
            .replace(SPACES, " ").trim()

        fun tokenizeEn(text: String): String = text
            .replace(PUNCT, " $1 ")
            .replace(Regex("""(\w)'(\w)"""), "$1 '$2")
            .replace(SPACES, " ").trim()

        fun tokenizeIndic(text: String): String {
            val sb = StringBuilder(text.length)
            for (c in text) {
                val digit = DIGIT_BLOCKS.firstOrNull { c.code - it - 0x66 in 0..9 }
                sb.append(if (digit != null) ('0' + (c.code - digit - 0x66)) else c)
            }
            return Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
                .replace(PUNCT, " $1 ")
                .replace(SPACES, " ").trim()
        }

        fun detok(text: String): String = text
            .replace(Regex("""\s+([.,!?;:)\]}।॥])"""), "$1")
            .replace(Regex("""([(\[{])\s+"""), "$1")
            .replace(Regex("""(\w) '(\w)"""), "$1'$2")
            .replace(SPACES, " ").trim()

        /** Greedy BPE over code points: merge the adjacent pair with the lowest rank until none applies. */
        fun bpe(word: String, rank: (String, String) -> Float?): List<String> {
            val sym = ArrayList<String>()
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                sym += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
            while (sym.size > 1) {
                var best: Float? = null
                var bi = -1
                for (j in 0 until sym.size - 1) {
                    val r = rank(sym[j], sym[j + 1]) ?: continue
                    if (best == null || r < best) { best = r; bi = j }
                }
                if (bi < 0) break
                sym[bi] = sym[bi] + sym[bi + 1]
                sym.removeAt(bi + 1)
            }
            return sym
        }
    }
}
