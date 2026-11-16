# VNI Tone Row and Tone-Aware Swipe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans (inline default). Select bounded delegation or parallel waves only under task-tiering-volio-style's execution-mode policy. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** VNI tone keys show the mark they type, a number-row toggle splits the tone row from the real number row, and swiping across tone keys ranks the matching accented word first.

**Architecture:** A pure Kotlin helper (`VniSwipe`) maps a swipe's points to the tone keys crossed and re-orders decoder candidates by their Vietnamese mark set. `SwipeDecoderDictionary.getSuggestions` strips tone-key points before the neural model and re-ranks the results. The layout YAML (separate repo) gets two tone rows keyed on `numRowMode`.

**Tech Stack:** Kotlin (app, `java/src`), Gradle/AGP, JUnit 4 (JVM unit test), layout YAML in `futo-keyboard-layouts`.

**Spec:** `docs/volio/specs/2026-11-13-vni-tone-row-and-swipe-design.md`

## Global Constraints

- **No auto-commit:** Do not include `git commit` / "Step N: Commit" in any task. Commit only after **all tasks are complete** and the **user agrees** (ask once at the end).
- **No native C++ change, no model retraining, no trie change.**
- **Typing is unchanged:** tone keys still send fullwidth `U+FF11..U+FF19`; `VNICombiner` is not modified; ASCII digits stay literal.
- **Tone key set** = keys whose `swipeCode` is in `U+FF11..U+FF19`. No tone keys on the keyboard means the feature is skipped, so other layouts behave exactly as today.
- **`MIN_POINTS_ON_KEY` = 2** consecutive points on one tone key for it to count as crossed.
- **Mark digits:** acute→1, grave→2, hook above→3, tilde→4, dot below→5, circumflex→6, horn→7, breve→8, `đ`→9. `０` is not used for ranking.
- **Re-rank rule:** no crossed tones → untouched; otherwise matching-mark-set candidates first (stable); if none match → untouched; never return fewer candidates.
- **Never throw from `getSuggestions` because of this feature.**
- **Test policy:** no unit tests for gesture behaviour. Unit tests only for the pure helpers in Task 1. Everything else is compile-verified plus the manual checklist.
- **Layout repo:** edit `/run/media/trongnlh/T7/AppDevelopment/futo-keyboard-layouts`. The `java/assets/layouts` submodule in the app repo is not initialised here; do not bump the submodule pointer.

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `java/src/org/futo/inputmethod/latin/VniSwipe.kt` | Create | Pure helpers: tone-code test, mark digits of a word, crossed-tone detection, re-rank. No Android imports. |
| `tests/unit/org/futo/inputmethod/latin/VniSwipeTest.kt` | Create | JVM unit tests for `VniSwipe`. |
| `build.gradle` | Modify (`sourceSets`, ~line 329) | Add `test` source set pointing at `tests/unit`. |
| `java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt` | Modify (`getSuggestions`, ~line 395-520) | Strip tone-key points, collect crossed tones, re-rank results. |
| `futo-keyboard-layouts/LatinScript/Asia/vietnamese_vni_tone_row.yaml` | Create | New layout "VNI (tone row)": number row configurable; combined and separate tone rows with mark labels. The original `vietnamese_vni.yaml` is unchanged. |
| `futo-keyboard-layouts/mapping.yaml` | Modify | Register `vietnamese_vni_tone_row` under `vi`, after `vietnamese_vni`. |

Dependencies: Task 1 → Task 2. Task 3 is independent. Execute inline in order 1, 2, 3, 4.

---

### Task 1: `VniSwipe` pure helpers and unit tests

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/VniSwipe.kt`
- Create: `tests/unit/org/futo/inputmethod/latin/VniSwipeTest.kt`
- Modify: `build.gradle` (the `sourceSets { ... }` block, right after the `androidTest { ... }` entry)

**Interfaces:**
- Produces (used by Task 2):
  - `internal object VniSwipe`
  - `const val MIN_POINTS_ON_KEY: Int`
  - `fun isToneCode(code: Int): Boolean`
  - `fun toneDigit(code: Int): Int` (1..9 for a tone code)
  - `fun markDigits(word: String): Set<Int>`
  - `fun toneDigitsAt(xs: IntArray, ys: IntArray, length: Int, toneAt: (Int, Int) -> Int): IntArray` (0 = not on a tone key)
  - `fun crossedTones(digitsPerPoint: IntArray): Set<Int>`
  - `fun <T> rerank(candidates: List<T>, crossed: Set<Int>, wordOf: (T) -> String): List<T>`

- [ ] **Step 0: Check that Gradle can run at all**

Run: `cd /run/media/trongnlh/T7/AppDevelopment/futo-android-keyboard && ./gradlew help --offline -q 2>&1 | tail -15`
Expected: finishes without "Could not resolve" errors. If it fails because `libs/` or other submodules are uninitialised, stop and tell the user (they must run `git submodule update --init` with network access); do not work around it.

- [ ] **Step 1: Add the JVM `test` source set**

In `build.gradle`, inside `sourceSets { ... }`, after the `androidTest { ... }` block add:

```groovy
        test {
            java.srcDirs = ['tests/unit']
        }
```

- [ ] **Step 2: Write the failing test**

Create `tests/unit/org/futo/inputmethod/latin/VniSwipeTest.kt`:

```kotlin
package org.futo.inputmethod.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VniSwipeTest {
    @Test
    fun toneCodesAreFullwidthDigitsOneToNine() {
        assertTrue(VniSwipe.isToneCode(0xFF11))
        assertTrue(VniSwipe.isToneCode(0xFF19))
        assertFalse(VniSwipe.isToneCode(0xFF10)) // fullwidth zero is not a ranking tone
        assertFalse(VniSwipe.isToneCode('1'.code)) // ASCII digit
        assertFalse(VniSwipe.isToneCode('a'.code))
        assertEquals(1, VniSwipe.toneDigit(0xFF11))
        assertEquals(9, VniSwipe.toneDigit(0xFF19))
    }

    @Test
    fun markDigitsOfWords() {
        assertEquals(setOf<Int>(), VniSwipe.markDigits("viet"))
        assertEquals(setOf(5, 6), VniSwipe.markDigits("việt"))
        assertEquals(setOf(5, 6), VniSwipe.markDigits("Việt"))
        assertEquals(setOf(1, 6), VniSwipe.markDigits("vết"))
        assertEquals(setOf(9), VniSwipe.markDigits("đi"))
        assertEquals(setOf(2), VniSwipe.markDigits("mà"))
        assertEquals(setOf(3), VniSwipe.markDigits("hỏi"))
        assertEquals(setOf(4), VniSwipe.markDigits("ngã"))
        assertEquals(setOf(1), VniSwipe.markDigits("á"))
        assertEquals(setOf(8), VniSwipe.markDigits("ă"))
        assertEquals(setOf(5, 7, 9), VniSwipe.markDigits("được"))
    }

    @Test
    fun crossedTonesNeedConsecutivePointsOnTheSameKey() {
        assertEquals(setOf<Int>(), VniSwipe.crossedTones(intArrayOf()))
        assertEquals(setOf(6), VniSwipe.crossedTones(intArrayOf(0, 0, 6, 6, 0, 5, 0)))
        assertEquals(setOf(1), VniSwipe.crossedTones(intArrayOf(1, 1, 1)))
        assertEquals(setOf<Int>(), VniSwipe.crossedTones(intArrayOf(2, 0, 2))) // grazed twice, never two in a row
        assertEquals(setOf(5, 6), VniSwipe.crossedTones(intArrayOf(6, 6, 5, 5)))
    }

    @Test
    fun toneDigitsAtUsesTheLookup() {
        val xs = intArrayOf(10, 20, 30, 40)
        val ys = intArrayOf(0, 0, 0, 0)
        val result = VniSwipe.toneDigitsAt(xs, ys, 3) { x, _ -> if (x >= 20) 7 else 0 }
        assertEquals(listOf(0, 7, 7), result.toList()) // only the first `length` points
    }

    @Test
    fun rerankMovesMatchingMarkSetFirstAndKeepsOrder() {
        val words = listOf("viet", "việt", "vết", "vịt")
        assertEquals(listOf("việt", "viet", "vết", "vịt"), VniSwipe.rerank(words, setOf(5, 6)) { it })
        assertEquals(listOf("vịt", "viet", "việt", "vết"), VniSwipe.rerank(words, setOf(5)) { it })
    }

    @Test
    fun rerankLeavesListAloneWhenNothingCrossedOrNothingMatches() {
        val words = listOf("viet", "việt", "vết")
        assertEquals(words, VniSwipe.rerank(words, emptySet()) { it })
        assertEquals(words, VniSwipe.rerank(words, setOf(9)) { it })
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd /run/media/trongnlh/T7/AppDevelopment/futo-android-keyboard && ./gradlew :testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.VniSwipeTest' --offline 2>&1 | tail -25`
Expected: compilation FAIL with `Unresolved reference: VniSwipe`.

- [ ] **Step 4: Write the minimal implementation**

Create `java/src/org/futo/inputmethod/latin/VniSwipe.kt`:

```kotlin
package org.futo.inputmethod.latin

import java.text.Normalizer

/**
 * Helpers that let swipe typing understand the VNI tone keys.
 *
 * The VNI layout types tones/diacritics with fullwidth digits U+FF11..U+FF19 (see VNICombiner).
 * The swipe model only knows letters, so tone-key points are removed from the path and the
 * tone keys the user crossed are used to re-rank the model's candidates instead.
 *
 * Kept free of Android types so it can be unit tested on the JVM.
 */
internal object VniSwipe {
    /** A tone key only counts as crossed when this many consecutive points land on it. */
    const val MIN_POINTS_ON_KEY = 2

    private const val FULLWIDTH_ZERO = 0xFF10

    fun isToneCode(code: Int): Boolean = code in (FULLWIDTH_ZERO + 1)..(FULLWIDTH_ZERO + 9)

    fun toneDigit(code: Int): Int = code - FULLWIDTH_ZERO

    /** The VNI digits needed to type the marks of [word], e.g. "việt" -> {5, 6}. */
    fun markDigits(word: String): Set<Int> {
        val digits = mutableSetOf<Int>()
        for (ch in Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)) {
            when (ch) {
                '́' -> digits.add(1) // acute
                '̀' -> digits.add(2) // grave
                '̉' -> digits.add(3) // hook above
                '̃' -> digits.add(4) // tilde
                '̣' -> digits.add(5) // dot below
                '̂' -> digits.add(6) // circumflex
                '̛' -> digits.add(7) // horn
                '̆' -> digits.add(8) // breve
                'đ' -> digits.add(9) // stroke
            }
        }
        return digits
    }

    /** For each of the first [length] points, the tone digit of the key it is on, or 0. */
    fun toneDigitsAt(xs: IntArray, ys: IntArray, length: Int, toneAt: (Int, Int) -> Int): IntArray =
        IntArray(length) { toneAt(xs[it], ys[it]) }

    /** Tone digits with at least [MIN_POINTS_ON_KEY] consecutive points on the same key. */
    fun crossedTones(digitsPerPoint: IntArray): Set<Int> {
        val crossed = mutableSetOf<Int>()
        var run = 0
        for (i in digitsPerPoint.indices) {
            val digit = digitsPerPoint[i]
            run = if (i > 0 && digit == digitsPerPoint[i - 1]) run + 1 else 1
            if (digit != 0 && run >= MIN_POINTS_ON_KEY) crossed.add(digit)
        }
        return crossed
    }

    /**
     * Moves candidates whose mark set equals [crossed] to the front, keeping relative order.
     * Returns [candidates] unchanged when nothing was crossed or nothing matches.
     */
    fun <T> rerank(candidates: List<T>, crossed: Set<Int>, wordOf: (T) -> String): List<T> {
        if (crossed.isEmpty()) return candidates
        val (matching, others) = candidates.partition { markDigits(wordOf(it)) == crossed }
        return if (matching.isEmpty()) candidates else matching + others
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /run/media/trongnlh/T7/AppDevelopment/futo-android-keyboard && ./gradlew :testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.VniSwipeTest' --offline 2>&1 | tail -25`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

---

### Task 2: Wire tone handling into `SwipeDecoderDictionary.getSuggestions`

**Files:**
- Modify: `java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt`

**Interfaces:**
- Consumes: `VniSwipe.isToneCode`, `VniSwipe.toneDigit`, `VniSwipe.toneDigitsAt`, `VniSwipe.crossedTones`, `VniSwipe.rerank` (Task 1); existing `Key.swipeCode` (line 58), `Key.isOnKey(x: Int, y: Int)`, `InputPointers.GestureSegment(pointerId)` with `x`, `y`, `t` as `ResizableIntArray` (`add`, `get`, `length`, `primitiveArray`).
- Produces: nothing used elsewhere.

Gesture tests are out of scope by policy; this task is verified by compilation (Task 4) and the manual checklist.

- [ ] **Step 1: Add the tone-point stripping helper**

In `SwipeDecoderDictionary.kt`, immediately above `class SwipeDecoderDictionary`, add (the file already imports `Key` and `InputPointers`):

```kotlin
/**
 * Removes the points that lie on VNI tone keys from [segments], and returns the tone digits the
 * gesture crossed. The tone digits are computed from the unfiltered points.
 */
private fun stripTonePoints(
    segments: List<InputPointers.GestureSegment>,
    toneKeys: List<Key>
): Pair<List<InputPointers.GestureSegment>, Set<Int>> {
    val toneAt = { x: Int, y: Int ->
        toneKeys.firstOrNull { it.isOnKey(x, y) }?.let { VniSwipe.toneDigit(it.swipeCode) } ?: 0
    }

    val crossed = mutableSetOf<Int>()
    val stripped = segments.map { seg ->
        val n = seg.x.length
        val xs = seg.x.primitiveArray
        val ys = seg.y.primitiveArray
        val digits = VniSwipe.toneDigitsAt(xs, ys, n, toneAt)
        crossed.addAll(VniSwipe.crossedTones(digits))

        InputPointers.GestureSegment(seg.pointerId).also { kept ->
            for (i in 0 until n) {
                if (digits[i] != 0) continue
                kept.x.add(xs[i])
                kept.y.add(ys[i])
                kept.t.add(seg.t.get(i))
            }
        }
    }.filter { it.x.length > 0 }

    return stripped to crossed
}
```

- [ ] **Step 2: Strip tone points before the model**

In `getSuggestions(composedData, ngramContext, useHighBeam, trieWeights)`, replace this existing block:

```kotlin
        val pointers = composedData.mInputPointers
        val segments = pointers.gestureSegments.toList().filter { it.x.length > 0 }

        val count = segments.size
        //Log.d("BatchInputSwipeDecoderDictionary", "total count is $count out of ${pointers.gestureSegments.size}")
        if(count == 0) return null

        val kb = prevKeyboard ?: run {
            Log.e("SwipeDecoderDictionary", "Could not determine keyboard!")
            return null
        }
```

with:

```kotlin
        val kb = prevKeyboard ?: run {
            Log.e("SwipeDecoderDictionary", "Could not determine keyboard!")
            return null
        }

        val pointers = composedData.mInputPointers
        val rawSegments = pointers.gestureSegments.toList().filter { it.x.length > 0 }

        // VNI tone keys: their points are not fed to the letters-only model; the digits crossed
        // are used afterwards to re-rank candidates.
        val toneKeys = kb.sortedKeys.filter { VniSwipe.isToneCode(it.swipeCode) }
        val (segments, crossedTones) =
            if(toneKeys.isEmpty()) rawSegments to emptySet<Int>()
            else stripTonePoints(rawSegments, toneKeys)

        val count = segments.size
        //Log.d("BatchInputSwipeDecoderDictionary", "total count is $count out of ${pointers.gestureSegments.size}")
        if(count == 0) return null
```

- [ ] **Step 3: Re-rank the results and keep scores consistent**

Scores decide the displayed order downstream, so reordering the list alone is not enough. Replace this existing block at the end of `getSuggestions`:

```kotlin
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

    data class PendingLayoutInfo(
```

with:

```kotlin
        // Re-rank by VNI tone keys crossed. The best scores are handed out in the new order so the
        // reorder survives any later sort by score.
        val ranked = VniSwipe.rerank(results.toList(), crossedTones) { it.word }
        val scores = results.map { it.score }.sortedDescending()

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

    data class PendingLayoutInfo(
```

Do not touch the `get_prediction` early return or the debug logging above it.

- [ ] **Step 4: Compile**

Run: `cd /run/media/trongnlh/T7/AppDevelopment/futo-android-keyboard && ./gradlew :compileUnstableDebugKotlin --offline 2>&1 | tail -25`
Expected: `BUILD SUCCESSFUL`. If `results.map { it.score }` or `.toList()` does not type-check against the `SwipeDecoder` result type (that class lives in the uninitialised `libs` module), adapt only the conversion (for example `results.asList()`), never the ranking logic.

---

### Task 3: VNI layout with mark labels and a number-row toggle

**Files:**
- Create: `/run/media/trongnlh/T7/AppDevelopment/futo-keyboard-layouts/LatinScript/Asia/vietnamese_vni_tone_row.yaml` (new layout; `name: VNI (tone row)`; the file below is its content)
- Modify: `/run/media/trongnlh/T7/AppDevelopment/futo-keyboard-layouts/mapping.yaml` (add `- vietnamese_vni_tone_row` after `- vietnamese_vni` under `vi:`)

**Interfaces:**
- Consumes: key spec syntax `label|output` (a single-code-point output becomes the key code, see `KeySpecParser.getCode`), `hint`, and row `numRowMode: Hideable | Filler` (see `LayoutSpec.md` and `Special/symbols.yaml`).
- Produces: tone keys with code `U+FF11..U+FF19` (what Task 2 detects) and `U+FF10` for the zero key.

- [ ] **Step 1: Replace the file content**

Replace the entire file with:

```yaml
name: VNI
languages: vi
combiners:
  - VNI
rows:
  # Number row is off (default): this row is both the number row and the tone row.
  # Keys type fullwidth digits, which the VNI combiner turns into tone marks/diacritics, while
  # the normal ASCII digits (the real number row) are left untouched by the combiner.
  # The small hint shows the mark that the key types.
  # U+00B4 acute, U+0060 grave, U+25CC U+0309 hook above, U+02DC tilde, U+25CC U+0323 dot below,
  # U+02C6 circumflex, U+25CC U+031B horn, U+02D8 breve, U+0111 d with stroke.
  - numRowMode: Hideable
    letters:
      - {type: base, spec: "1|１", hint: "´"}
      - {type: base, spec: "2|２", hint: "`"}
      - {type: base, spec: "3|３", hint: "◌̉"}
      - {type: base, spec: "4|４", hint: "˜"}
      - {type: base, spec: "5|５", hint: "◌̣"}
      - {type: base, spec: "6|６", hint: "ˆ"}
      - {type: base, spec: "7|７", hint: "◌̛"}
      - {type: base, spec: "8|８", hint: "˘"}
      - {type: base, spec: "9|９", hint: "đ"}
      - "0|０"
  # Number row is on: the real number row is shown above, and the tone row is separate and shows
  # only the marks.
  - numRowMode: Filler
    letters:
      - "´|１"
      - "`|２"
      - "◌̉|３"
      - "˜|４"
      - "◌̣|５"
      - "ˆ|６"
      - "◌̛|７"
      - "˘|８"
      - "đ|９"
      - "0|０"
  - letters: q w e r t y u i o p
  - letters: a s d f g h j k l
  - letters: z x c v b n m
```

(`numberRowMode` is intentionally removed so it falls back to the default `UserConfigurable`.)

- [ ] **Step 2: Validate the YAML parses and the codes are right**

Run:
```bash
python3 -I - <<'EOF'
import yaml
d = yaml.safe_load(open("/run/media/trongnlh/T7/AppDevelopment/futo-keyboard-layouts/LatinScript/Asia/vietnamese_vni.yaml"))
assert "numberRowMode" not in d
combined, separate = d["rows"][0], d["rows"][1]
assert combined["numRowMode"] == "Hideable" and separate["numRowMode"] == "Filler"
def code(item):
    spec = item["spec"] if isinstance(item, dict) else item
    return ord(spec.split("|")[1])
for row in (combined, separate):
    codes = [code(i) for i in row["letters"]]
    assert codes == [0xFF11 + n for n in range(9)] + [0xFF10], codes
print("ok", len(d["rows"]), "rows")
EOF
```
Expected: `ok 5 rows`.

---

### Task 4: Verification and manual checklist

**Files:** none changed.

- [ ] **Step 1: Fresh unit test run**

Run: `cd /run/media/trongnlh/T7/AppDevelopment/futo-android-keyboard && ./gradlew :testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.VniSwipeTest' --offline 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 2: Fresh compile of the app module**

Run: `cd /run/media/trongnlh/T7/AppDevelopment/futo-android-keyboard && ./gradlew :compileUnstableDebugKotlin --offline 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`. If Gradle cannot run because submodules are missing (Task 1, Step 0), report exactly that to the user and state that Tasks 1-2 were not compile-verified.

- [ ] **Step 3: Confirm the tree has only the intended changes**

Run: `git -C /run/media/trongnlh/T7/AppDevelopment/futo-android-keyboard status --short && git -C /run/media/trongnlh/T7/AppDevelopment/futo-keyboard-layouts status --short`
Expected: app repo shows `build.gradle`, `SwipeDecoderDictionary.kt`, `VniSwipe.kt`, `tests/unit/...`, and the `docs/volio` files; layouts repo shows only the new `LatinScript/Asia/vietnamese_vni_tone_row.yaml` and the `mapping.yaml` change.

- [ ] **Step 4: Hand the user this manual test checklist (on a device build with the updated layouts submodule)**

1. Select the VNI layout with number row off: one row on top, each key shows its digit and a small mark hint (`1 ´`, `6 ˆ`, `9 đ`, and so on).
2. Turn the number row on: the real digit row appears above a mark-only tone row.
3. Type `a` then tap `1`: `á`. Type `viet` then `6` then `5`: `việt`. Plain ASCII digits from the real number row type digits.
4. Swipe `v-i-e-t`, passing through `6` and `5`: the top suggestion is `việt`.
5. Swipe `m-a` through `2`: the top suggestion is `mà`.
6. Swipe a word without touching the tone row: the same suggestions as before the change.
7. Swipe in English and in Telex: unchanged.
8. Swipe so the path only grazes a tone key on the way to a letter: no tone is applied. If real crossings are missed or grazes are counted, tune `VniSwipe.MIN_POINTS_ON_KEY`.

- [ ] **Step 5: Ask about committing**

Do not commit. Report results and ask the user once whether to commit (app repo and the layouts repo are separate, and the submodule pointer bump is their call).
