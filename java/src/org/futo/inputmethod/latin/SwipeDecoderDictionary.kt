package org.futo.inputmethod.latin

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.futo.inputmethod.keyboard.Key
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.internal.isAlphabet
import org.futo.inputmethod.latin.common.ComposedData
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.settings.SettingsValues
import org.futo.inputmethod.latin.settings.SettingsValuesForSuggestion
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.ml.inference.SwipeDecoder
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ENCODER_ASSET = "futo-swipe/honorable_sturgeon/model_fp32.pte"
private const val ENGLISH_LM_ASSET = "futo-swipe/chosen_planarian/context_lm.pte"
private const val ENGLISH_LM_VOCAB_ASSET = "futo-swipe/chosen_planarian/vocab.txt"
private const val ENGLISH_DECODER_ASSET = "futo-swipe/magic_macaw/model_fp32.pte"
private const val SCORING_ASSET = "futo-swipe/scoring.json"

@Serializable
data class Input(
    val x: Float,
    val y: Float,
    val t: Float
)

@Serializable
data class Inputs(val data: List<Input>)

internal fun getKeyXY(key: Key, keyboard: Keyboard): Pair<Float, Float> {
    var xMid = key.drawX + key.drawWidth/2.0f
    var yMid = key.y + key.verticalGap/2.0f + key.height/2.0f

    xMid /= keyboard.mBaseWidth
    yMid *= (1.0f / (keyboard.mBaseHeight - keyboard.mPadding.bottom)) * (4.0f / 3.0f)

    return xMid to yMid
}

internal fun getKeyYBottom(key: Key, keyboard: Keyboard): Float =
    (key.y + key.verticalGap + key.height) *
            ((1.0f / (keyboard.mBaseHeight - keyboard.mPadding.bottom)) * (4.0f / 3.0f))


val Key.swipeCode: Int get() = swipeCodeOverride ?: code

data class LayoutInfoForModel(
    val letters: String,
    val xs: List<Float>,
    val ys: List<Float>,
    val decoder: String,
    val lm: String,
    val sx: Float, val sy: Float,
    val ox: Float, val oy: Float,
) {
    companion object {
        val DEFAULT = LayoutInfoForModel(
            letters = "",
            xs = emptyList(),
            ys = emptyList(),
            decoder = "",
            lm = "",
            sx = 1.0f, sy = 1.0f,
            ox = 0.0f, oy = 0.0f
        )

        @JvmStatic
        fun buildLayoutInfo(context: Context, keyboard: Keyboard, settingsValues: SettingsValues): LayoutInfoForModel? =
            (context.getSetting(SwipeSpecialDecoderSetting).let {
                if(it) SpecialDecoder.matchLayout(keyboard, settingsValues)
                else null
            } ?: run {
                val keys = keyboard.sortedKeys
                    .filter { settingsValues.isWordCodePoint(it.swipeCode) && !Character.isDigit(it.swipeCode) }
                    .sortedBy { it.swipeCode }
                    .distinctBy { it.swipeCode } // The engine currently can't handle letters existing multiple times. Sorry custom layouts!
                val letters = keys.joinToString(separator="") { Character.toString(Character.toLowerCase(it.swipeCode)) }

                // Guard against non-alphabet keyboards
                if(!keyboard.mId.mElement.kind.isAlphabet || letters.length < 6) return@run null

                val positions = keys.map { getKeyXY(it, keyboard) }

                val yScale = 1.0f / (keys.maxOf { getKeyYBottom(it, keyboard) }.coerceAtLeast(1.0f))

                val xs = positions.map { it.first }
                val ys = positions.map { it.second * yScale }

                return@run LayoutInfoForModel(
                    letters = letters,
                    xs = xs, ys = ys,
                    sx = 1.0f, sy = yScale,
                    ox = 0.0f, oy = 0.0f,
                    decoder = "",
                    lm = SpecialContextLM.match(settingsValues),
                )
            }).let {
                if(!context.getSetting(SwipeLanguageModelSetting)) {
                    it?.copy(lm="")
                } else {
                    it
                }
            }
    }
}

private class SpecialContextLM private constructor(
    val language: String,
    val asset: String
) {
    companion object {
        private val contextLMs = listOf(
            SpecialContextLM("en", ENGLISH_LM_ASSET)
        )

        fun match(settingsValues: SettingsValues): String {
            if(settingsValues.mMultilingualLocales.isNotEmpty()) return ""

            return contextLMs.firstOrNull { it.language == settingsValues.mLocale.language }?.asset ?: ""
        }
    }
}

private class SpecialDecoder private constructor(
    val asset: String,

    val language: String,
    val layoutLetters: String,

    // These are normalized such that the lowest value is always 0, and highest value is always 1
    val layoutXs: List<Float>,
    val layoutYs: List<Float>,

    val xOffset: Float,
    val xScale: Float,
    val yOffset: Float,
    val yScale: Float
) {
    val maxXDeviation = xScale * 0.1
    val maxYDeviation = yScale * 0.1

    companion object {
        private val specialDecoders = listOf(
            SpecialDecoder(
                asset = ENGLISH_DECODER_ASSET,
                language = "en",
                layoutLetters = "abcdefghijklmnopqrstuvwxyz",
                layoutXs = listOf(0.055555555555555566f, 0.611111111111111f, 0.38888888888888895f, 0.2777777777777778f, 0.22222222222222227f, 0.38888888888888895f, 0.5000000000000001f, 0.611111111111111f, 0.7777777777777778f, 0.7222222222222222f, 0.8333333333333334f, 0.9444444444444445f, 0.8333333333333334f, 0.7222222222222222f, 0.888888888888889f, 1.0f, 0.0f, 0.33333333333333337f, 0.1666666666666667f, 0.44444444444444453f, 0.6666666666666667f, 0.5000000000000001f, 0.11111111111111112f, 0.2777777777777778f, 0.5555555555555556f, 0.1666666666666667f),
                layoutYs = listOf(0.5f, 1.0f, 1.0f, 0.5f, 0.0f, 0.5f, 0.5f, 0.5f, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f),
                xOffset = 0.05f,
                xScale = 0.8999999999999999f,
                yOffset = 0.1667f,
                yScale = 0.6666000000000001f,
            )
        )

        internal fun matchLayout(keyboard: Keyboard, settingsValues: SettingsValues): LayoutInfoForModel? {
            if(settingsValues.mMultilingualLocales.isNotEmpty()) return null

            val keys = keyboard.sortedKeys.associate { Character.toLowerCase(it.swipeCode) to it }

            return specialDecoders.firstNotNullOfOrNull { decoder ->
                if(keyboard.mId.mLocale.language != decoder.language) return@firstNotNullOfOrNull null

                // In case the layout has multiple repeated instances of the same letter, let's forget
                // about using a special decoder, since this should never occur in a regular layout.
                if(decoder.layoutLetters.any { letter -> keyboard.sortedKeys.count { key ->
                        Character.toLowerCase(key.swipeCode) == letter.code
                } > 1}) return@firstNotNullOfOrNull null

                // Make sure the letters are a perfect match!
                val expectedLayoutLetters = decoder.layoutLetters.map { it.code }.toSortedSet()
                val ourLayoutLetters = keys.keys.filter { !Character.isDigit(it) && settingsValues.isWordCodePoint(it) }.toSortedSet()
                if(expectedLayoutLetters != ourLayoutLetters) return@firstNotNullOfOrNull null

                val relevantKeysN = decoder.layoutLetters.map { keys[it.code] }
                if(relevantKeysN.any { it == null }) return@firstNotNullOfOrNull null
                val relevantKeys = relevantKeysN.filterNotNull()
                val positionsNotNormalized = relevantKeys.map { getKeyXY(it, keyboard) }

                val minX = positionsNotNormalized.minOf { it.first }
                val minY = positionsNotNormalized.minOf { it.second }

                val maxX = positionsNotNormalized.maxOf { it.first }
                val maxY = positionsNotNormalized.maxOf { it.second }

                val offsetX = minX
                val offsetY = minY
                val scaleX = maxX - offsetX
                val scaleY = maxY - offsetY

                if(scaleX == 0.0f || scaleY == 0.0f) return@firstNotNullOfOrNull null

                val normX = positionsNotNormalized.map { (it.first - offsetX) / scaleX }
                val normY = positionsNotNormalized.map { (it.second - offsetY) / scaleY }

                val matches = decoder.layoutXs.zip(normX).all { (a, b) -> abs(b - a) < decoder.maxXDeviation }
                           && decoder.layoutYs.zip(normY).all { (a, b) -> abs(b - a) < decoder.maxYDeviation }

                if(!matches) return@firstNotNullOfOrNull null

                val dsx = decoder.xScale / scaleX
                val dsy = decoder.yScale / scaleY
                val dox = decoder.xOffset - offsetX * dsx
                val doy = decoder.yOffset - offsetY * dsy
                LayoutInfoForModel(
                    decoder = decoder.asset,
                    letters = decoder.layoutLetters,
                    xs = decoder.layoutXs.map { it * decoder.xScale + decoder.xOffset },
                    ys = decoder.layoutYs.map { it * decoder.yScale + decoder.yOffset },
                    sx = dsx, sy = dsy, ox = dox, oy = doy,
                    lm = SpecialContextLM.match(settingsValues),
                )
            }
        }
    }
}

val LegacySwipeSetting = SettingsKey(booleanPreferencesKey("swipe_mode_legacy"), false)

/** Debug: keep the last swipe trail on screen, unfaded, until the next swipe (for screenshots). */
val KeepSwipeTrailSetting = SettingsKey(booleanPreferencesKey("swipe_debug_keep_trail"), false)
val DisplayTop4Setting = SettingsKey(booleanPreferencesKey("swipe_use_top4_suggestions"), true)

val SwipeSpecialDecoderSetting = SettingsKey(booleanPreferencesKey("__experimental_swipe_special_decoder"), true)
val SwipeLanguageModelSetting = SettingsKey(booleanPreferencesKey("__experimental_swipe_language_model"), true)

@Serializable
data class SwipePoiSer(
    val x: Float, val y: Float, val t: Int
)
@Serializable
data class SwipeSegSer(val data: List<SwipePoiSer>)

/**
 * Debug dump of a VNI gesture: every key's hit box and the raw gesture points, in the same
 * keyboard pixel coordinates, so the swipe can be redrawn offline. Logcat truncates long lines,
 * so the points are split into chunks.
 */
private fun logVniGesture(kb: Keyboard, segments: List<InputPointers.GestureSegment>) {
    val tag = "SwipeDecoderDictionary"
    val keys = kb.sortedKeys.joinToString(";") {
        "${it.swipeCode},${it.hitBox.left},${it.hitBox.top},${it.hitBox.right},${it.hitBox.bottom}"
    }
    Log.d(tag, "VNIDUMP keys ${kb.mBaseWidth}x${kb.mBaseHeight} $keys")
    segments.forEach { seg ->
        (0 until seg.x.length).chunked(100).forEachIndexed { part, indices ->
            val points = indices.joinToString(";") { "${seg.x.get(it)},${seg.y.get(it)},${seg.t.get(it)}" }
            Log.d(tag, "VNIDUMP path ${seg.pointerId} $part $points")
        }
    }
}

/** A swipe candidate; [pathScore] is how well the swipe matches its letters (the model's CTC score). */
private class VniCandidate(val word: String, val score: Float, val pathScore: Float)

/**
 * Cuts the trips to the VNI tone row out of [segments], and returns the tone digits the gesture
 * picked by slowing down on them (see [VniSwipe.splitTones]).
 */
private fun stripTonePoints(
    segments: List<InputPointers.GestureSegment>,
    toneKeys: List<Key>
): Pair<List<InputPointers.GestureSegment>, Set<Int>> {
    val toneAt = { x: Int, y: Int ->
        toneKeys.firstOrNull { it.isOnKey(x, y) }?.let { VniSwipe.toneDigit(it.swipeCode) } ?: 0
    }
    val keyWidth = toneKeys.first().width.toFloat()

    val crossed = mutableSetOf<Int>()
    val stripped = segments.map { seg ->
        val n = seg.x.length
        val xs = seg.x.primitiveArray
        val ys = seg.y.primitiveArray
        val ts = seg.t.primitiveArray
        val split = VniSwipe.splitTones(xs, ys, ts, VniSwipe.toneDigitsAt(xs, ys, n, toneAt), keyWidth)
        crossed.addAll(split.picked)

        keepPoints(seg, split.keep)
    }.filter { it.x.length > 0 }

    return stripped to crossed
}

/** The points of [seg] marked in [keep]. */
private fun keepPoints(seg: InputPointers.GestureSegment, keep: BooleanArray) =
    InputPointers.GestureSegment(seg.pointerId).also { kept ->
        for (i in 0 until seg.x.length) {
            if (!keep[i]) continue
            kept.x.add(seg.x.get(i))
            kept.y.add(seg.y.get(i))
            kept.t.add(seg.t.get(i))
        }
    }

class SwipeDecoderDictionary(val context: Context, val locale: Locale) : Dictionary("swipe", locale) {
    companion object {
        const val SWIPE_MODEL = ENCODER_ASSET

        var debugLogUntil: Long = 0L

        private var prevKeyboard: Keyboard? = null
        var appliedLayoutInfo: LayoutInfoForModel = LayoutInfoForModel.DEFAULT
            private set

        // for debug info
        val appliedScoring = mutableStateOf(SwipeDecoder.Scoring(0.0f, 0.0f, 0.0f, 0.0f))
        var appliedTries: LongArray? = null
        var appliedTrieWeights by mutableStateOf<FloatArray>(FloatArray(0))

        fun metadataFor(pteAsset: String): String
            = pteAsset.substringBeforeLast('/') + "/metadata.json"

        fun vocabFor(pteAsset: String): String {
            if(pteAsset.isEmpty()) return ""
            return pteAsset.substringBeforeLast('/') + "/vocab.txt"
        }

        @Serializable private data class ModelMetadata(val codename: String)
        private val CodenameParseJson = Json { ignoreUnknownKeys = true }
        fun parseMetadataToGetCodename(content: String): String =
            CodenameParseJson.decodeFromString<ModelMetadata>(content).codename

        private val createdFiles = mutableSetOf<String>()
        fun getFilePath(context: Context, assetName: String): String {
            if(assetName.isEmpty()) return ""

            val assets = context.assets
            val tmpDir = context.codeCacheDir
            val modelFile = File(tmpDir, assetName)

            if (modelFile.exists()) {
                if(assetName in createdFiles) return modelFile.absolutePath

                modelFile.delete()
            }
            modelFile.parentFile?.mkdirs()
            assets.open(assetName).use { inputStream ->
                modelFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            createdFiles.add(assetName)

            if(assetName.endsWith(".pte")) {
                // Ensure metadata is also present
                getFilePath(context, metadataFor(assetName))
            }

            return modelFile.absolutePath
        }


        /** Whether the current layout types Vietnamese marks with Telex (see [TelexSwipe]). */
        var telexLayout = false

        @JvmStatic
        fun updateKeyboard(keyboard: Keyboard) {
            prevKeyboard = keyboard
        }

        @JvmStatic
        fun canBeUsed(): Boolean {
            val settings = Settings.getInstance().current
            if(!settings.mGestureInputEnabled) {
                Log.d("SwipeDecoderDictionary", "Inactive because gesture input is disabled.")
                return false
            }

            return true
        }
    }

    var decoder: SwipeDecoder? = null

    object BeamValues {
        const val shortBeam = 32
        const val midBeam = 64
        const val highBeam = 300
        const val highestBeam = 300
    }

    private fun getOrInitDecoder(): SwipeDecoder = decoder ?: run {
        val swipeModelPath = getFilePath(context, SWIPE_MODEL)

        val decoder = SwipeDecoder(
            encoderPath = swipeModelPath,
            beamWidth = BeamValues.highestBeam,
            useExpansion = false, // ITrie contains expanded entries already
        )

        this.decoder = decoder
        applyPendingLayoutInfo()

        return decoder
    }

    override fun getNextValidCodePoints(composedData: ComposedData?): ArrayList<Int> {
        return arrayListOf()
    }

    private fun getPredictions(
        composedData: ComposedData,
        ngramContext: NgramContext?
    ): ArrayList<SuggestedWords.SuggestedWordInfo>? {
        if(true) return null

        val decoder = getOrInitDecoder()
        val wordsContext = ngramContext?.fullContext?.split(' ')?.takeLast(10) ?: emptyList()
        decoder.setContext(wordsContext)

        val results = decoder.predictNext()

        //Log.d("SwipeDecoderDictionary", "getPredictions results=${results}")
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>(results.size)
        results.forEach {
            list.add(SuggestedWords.SuggestedWordInfo(
                it.word, "", (it.score * 1000.0f + 10000.0f).toInt(), SuggestedWords.SuggestedWordInfo.KIND_CORRECTION, this, 0, 0
            ).apply {
                mOriginatesFromSwipeModel = true
            })
        }

        return list
    }

    override fun getSuggestions(
        composedData: ComposedData?,
        ngramContext: NgramContext?,
        proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion?,
        sessionId: Int,
        weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray?
    ): ArrayList<SuggestedWords.SuggestedWordInfo?>? {
        throw UnsupportedOperationException("Use the non-dictionary method instead")
    }

    val whitespaceRegex = Regex("[^\\w']+")
    fun getSuggestions(
        composedData: ComposedData,
        ngramContext: NgramContext?,
        useHighBeam: Boolean,
        trieWeights: FloatArray,
        isValidWord: (String) -> Boolean
    ): ArrayList<SuggestedWords.SuggestedWordInfo>? {
        if(context.getSetting(LegacySwipeSetting) == true) return null

        if(!composedData.mIsBatchMode && composedData.mInputPointers.pointerSize == 0 && composedData.mTypedWord.isEmpty()) {
            return getPredictions(
                composedData,
                ngramContext
            )
        }

        if(!composedData.mIsBatchMode) return null

        val kb = prevKeyboard ?: run {
            Log.e("SwipeDecoderDictionary", "Could not determine keyboard!")
            return null
        }

        val pointers = composedData.mInputPointers
        val rawSegments = pointers.gestureSegments.toList().filter { it.x.length > 0 }

        // VNI tone keys: their points are not fed to the letters-only model; the digits crossed
        // are used afterwards to re-rank candidates.
        val toneKeys = kb.sortedKeys.filter { VniSwipe.isToneCode(it.swipeCode) }
        // Telex: the marks are letters (s f r x j w) and loops (aa ee oo dd), read from one-finger swipes
        val telex = toneKeys.isEmpty() && telexLayout && rawSegments.size == 1
        // Vietnamese marks re-rank the candidates
        val marked = toneKeys.isNotEmpty() || telex
        val keyWidth = toneKeys.firstOrNull()?.width?.toFloat() ?: kb.mMostCommonKeyWidth.toFloat()
        val boxes by lazy {
            kb.sortedKeys.map {
                VniSwipe.KeyBox(
                    if(Character.isLetter(it.code)) it.code.toChar() else null,
                    it.hitBox.left, it.hitBox.top, it.hitBox.right, it.hitBox.bottom
                )
            }
        }
        val telexSplit = if(!telex) null else rawSegments.first().let { seg ->
            TelexSwipe.split(
                seg.x.primitiveArray, seg.y.primitiveArray, seg.t.primitiveArray, seg.x.length, boxes, keyWidth
            )
        }
        val (segments, crossedTones) = when {
            telexSplit != null ->
                listOf(keepPoints(rawSegments.first(), telexSplit.keep)).filter { it.x.length > 0 } to telexSplit.picked
            toneKeys.isEmpty() -> rawSegments to emptySet<Int>()
            else -> stripTonePoints(rawSegments, toneKeys)
        }

        // Letters the gesture surely typed (start, end, rests, sharp turns). Candidates must
        // hold them, so keys merely swept over cannot push out what was clearly swiped.
        val anchors = when {
            telexSplit != null -> telexSplit.anchors
            toneKeys.isEmpty() || rawSegments.size != 1 -> emptyList()
            else -> rawSegments.first().let { seg ->
                VniSwipe.anchors(
                    seg.x.primitiveArray, seg.y.primitiveArray, seg.t.primitiveArray, seg.x.length, boxes, keyWidth
                )
            }
        }

        if(marked && useHighBeam && (BuildConfig.DEBUG || System.currentTimeMillis() < debugLogUntil)) {
            logVniGesture(kb, rawSegments)
        }

        val count = segments.size
        //Log.d("BatchInputSwipeDecoderDictionary", "total count is $count out of ${pointers.gestureSegments.size}")
        if(count == 0) return null

        val keyboardWidth = kb.mBaseWidth
        val keyboardHeight = kb.mBaseHeight - kb.mPadding.bottom

        val earliestTime = segments[0].t.get(0).toFloat()
        val transformSegment = { seg: InputPointers.GestureSegment -> SwipeDecoder.SwipeSeg(
            x = seg.x.primitiveArray.take(seg.x.length).map {
                it.toFloat() / keyboardWidth * appliedLayoutInfo.sx + appliedLayoutInfo.ox
            }.toFloatArray(),
            y = seg.y.primitiveArray.take(seg.y.length).map {
                minOf(1.0f, (it.toFloat() / keyboardHeight) * (4.0f / 3.0f) * appliedLayoutInfo.sy + appliedLayoutInfo.oy )
            }.toFloatArray(),
            t = seg.t.primitiveArray.take(seg.t.length).map { it - earliestTime }.toFloatArray()
        ) }

        val left = mutableListOf<SwipeDecoder.SwipeSeg>()
        val right = mutableListOf<SwipeDecoder.SwipeSeg>()

        if(count == 1) {
            left.add(transformSegment(segments.first()))
        } else {
            left.addAll(segments.filter { it.pointerId == 0 }.map { transformSegment(it) })
            right.addAll(segments.filter { it.pointerId == 1 }.map { transformSegment(it) })
        }

        val wordsContext = ngramContext?.fullContext
            ?.lineSequence()
            ?.lastOrNull()
            ?.splitToSequence(whitespaceRegex)
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?.takeLast(10)
            ?: emptyList()

        val decoder = getOrInitDecoder()
        decoder.setContext(wordsContext)
        appliedTrieWeights = trieWeights

        val isMultiSwipe = (left.size + right.size) > 1
        val beamWidth = when {
            isMultiSwipe && !useHighBeam -> BeamValues.midBeam
            !useHighBeam -> BeamValues.shortBeam
            else -> BeamValues.highBeam
        }

        // More candidates for Vietnamese marks: their base letters are re-spelled with the picked tones
        val topK = if(!useHighBeam) 1 else if(marked) 16 else 4

        val results = synchronized(BinaryDictionary.sTrieUsageLock) {
            if(appliedTries?.isEmpty() != false) {
                Log.e("SwipeDecoderDictionary", "Applied tries are blank! $appliedTries")
                return null
            }
            decoder.recognize(
                 left.toTypedArray(), right.toTypedArray(),
                 topK = topK,
                 beamWidth = beamWidth,
                 trieWeights = trieWeights
            )
        }

        // basically update it at end of swiping
        if(useHighBeam) appliedScoring.value = decoder.scoring

        if(BuildConfig.DEBUG || System.currentTimeMillis() < debugLogUntil) {
            Log.d("SwipeDecoderDictionary", "Beam width: ${beamWidth} (multiswipe=$isMultiSwipe useHighBeam=$useHighBeam)")
            Log.d("SwipeDecoderDictionary", "Timing: ${decoder.lastTiming()}")
            Log.d("SwipeDecoderDictionary", "Left = $left")
            Log.d("SwipeDecoderDictionary", "Right = $right")

            if(left.size == 1) {
                val lf = left.first()
                var lastT = 0
                val ls = (lf.x.zip(lf.y)).zip(lf.t.toList()).map { v ->
                    val x = (v.first.first * 100).roundToInt() / 100.0f
                    val y = (v.first.second * 1000).roundToInt() / 1000.0f
                    val t = v.second.toInt()

                    SwipePoiSer(x, y, t)
                }.filter {
                    if(it.t == 0 || (it.t - lastT) > 33) {
                        lastT = it.t
                        true
                    } else {
                        false
                    }
                }

                val ser = Json.encodeToString(SwipeSegSer.serializer(), SwipeSegSer(ls))
                Log.d("SwipeDecoderDictionary", "json = $ser")
            }

            Log.d("SwipeDecoderDictionary", "Context = $wordsContext")
            Log.d("SwipeDecoderDictionary", "curr scale is  ${appliedLayoutInfo.sx} ${appliedLayoutInfo.sy}")
            Log.d("SwipeDecoderDictionary", "curr offset is ${appliedLayoutInfo.ox} ${appliedLayoutInfo.oy}")
            Log.d("SwipeDecoderDictionary", "outputs = ${results.joinToString { "Word(\"${it.word}\", score=${it.score}, lm=${it.lmScore}, ctc=${it.ctcScore})" }}")
        }

        // VNI: the model often has the right letters with the wrong marks, so each candidate's base
        // letters also get spelled with exactly the picked tones (men -> mến, thiết -> thiệt) when
        // the dictionary knows that spelling (with no tone picked: unmarked, tâm -> tam). Then
        // re-rank by the picked tones. The best scores are handed out in the new order so the
        // reorder survives any later sort by score.
        val candidates = results.map { VniCandidate(it.word, it.score.toFloat(), it.ctcScore.toFloat()) }
        val variants = if(!marked) emptyList() else candidates
            .distinctBy { VniSwipe.baseLetters(it.word) }
            .flatMap { c ->
                VniSwipe.markedVariants(c.word, crossedTones)
                    .filter(isValidWord)
                    .map { VniCandidate(it, c.score, c.pathScore) }
            }
        // No candidate ends on the letter the swipe surely ended on (than, thân for a swipe ending
        // on t): also try their base letters ending there (that -> thật), with the picked tones
        val endAnchor = anchors.lastOrNull()?.takeIf { it.isEnd }
        val offered = candidates + variants
        val endFixed = if(endAnchor == null || offered.any { VniSwipe.fitsAnchors(it.word, anchors) }) emptyList()
            else offered.distinctBy { VniSwipe.baseLetters(it.word) }.flatMap { c ->
                VniSwipe.endFixes(c.word, endAnchor.letters)
                    .flatMap { VniSwipe.markedVariants(it, crossedTones) }
                    .filter(isValidWord)
                    .map { VniCandidate(it, c.score, c.pathScore) }
            }
        // The sure letters alone, with the picked tones (đệt when the model only saw diệt, đoạt)
        val worst = candidates.minByOrNull { it.pathScore }
        val fromAnchors = if(crossedTones.isEmpty() || worst == null) emptyList()
            else VniSwipe.anchorSpellings(anchors)
                .flatMap { VniSwipe.markedVariants(it, crossedTones) }
                .filter(isValidWord)
                .map { VniCandidate(it, worst.score, worst.pathScore) }
        // Drop the candidates missing a letter that was surely typed, unless none has them all
        val all = (offered + endFixed + fromAnchors).distinctBy { it.word }
            .let { list -> list.filter { VniSwipe.fitsAnchors(it.word, anchors) }.ifEmpty { list } }
        val ranked = if(!marked) all else VniSwipe.rerank(all, crossedTones, { it.pathScore }) { it.word }
        val scores = all.map { it.score }.sortedDescending()

        if(marked && (BuildConfig.DEBUG || System.currentTimeMillis() < debugLogUntil)) {
            Log.d("SwipeDecoderDictionary", "VNI toneKeys=${toneKeys.size} telex=$telex crossedTones=${crossedTones.sorted()} " +
                    "anchors=${anchors.joinToString("") { a -> a.letters.sorted().joinToString("", "[", "]") }} " +
                    "model=${results.joinToString { it.word }} added=${(variants + endFixed + fromAnchors).joinToString { it.word }} " +
                    "ranked=${ranked.joinToString { it.word }}")
        }

        val list = ArrayList<SuggestedWords.SuggestedWordInfo>(ranked.size)
        ranked.forEachIndexed { index, it ->
            list.add(SuggestedWords.SuggestedWordInfo(
                it.word, "", (scores[index] * 1000.0f + 10000.0f).toInt(), SuggestedWords.SuggestedWordInfo.KIND_CORRECTION, this, 0, 0
            ).apply {
                mOriginatesFromSwipeModel = true
            })
        }

        return list
    }

    data class PendingLayoutInfo(val layout: LayoutInfoForModel, val tries: List<Long>)
    private var pendingLayoutInfo: PendingLayoutInfo? = null
    private fun applyPendingLayoutInfo() {
        decoder?.let { d ->
            pendingLayoutInfo?.let { pend ->
                //Log.d("SwipeDecoderDictionary", "Applying layout info: $pend")
                d.setMode(
                    letters=pend.layout.letters,
                    cx=pend.layout.xs.toFloatArray(),
                    cy=pend.layout.ys.toFloatArray(),
                    tries=pend.tries.toLongArray(),
                    decoderPath=getFilePath(context, pend.layout.decoder),
                    lmModelPath=getFilePath(context, pend.layout.lm),
                    lmVocabPath=getFilePath(context, vocabFor(pend.layout.lm))
                )
                appliedScoring.value = d.scoring
                appliedLayoutInfo = pend.layout
                appliedTries = pend.tries.toLongArray()
            }
            pendingLayoutInfo = null
        }
    }

    fun updateKeyboard(pendingLayoutInfo: PendingLayoutInfo) {
        this.pendingLayoutInfo = pendingLayoutInfo
        applyPendingLayoutInfo()
    }

    override fun isInDictionary(word: String?): Boolean {
        return false
    }

    fun invalidateTries() {
        if(appliedTries?.isEmpty() != false) return
        decoder?.setMode(tries = emptyList<Long>().toLongArray())
        appliedTries = null
    }
}