# Upstreaming the Vietnamese keyboard changes

2026-11-20 · TrongNLH

## Scope

The 7 commits in `d59ada1a4..HEAD` (14 files, +156/−33) can go upstream in three groups. Only three changes are strictly Vietnamese: the VNI swipe anchor spellings, the Đ drawing and the built-in `vi` dictionary. One fix in `WordComposer` is core code. The Gboard-style personal-dictionary feature changes behaviour for every language.

These commits sit on earlier fork work that upstream does not have. The fork branched from upstream at `7d61ab413`. The prerequisite commits are: `6c69f47dc` / `e6bfc2405` (tone-row layout), `3cb6fe7bf`, `9d0a84a9c`, `8a78a98d7` (VNI swipe: `VniSwipe.kt` and the VNI branch in `SwipeDecoderDictionary`), `6784d3c45` (edit tones of swiped words) and `55c232aaa` (stroke-mark drawing of đ in `KeyboardView`). Upstream those first, or in the same PR. Leave out `c5781ee9f` (debug), `e73918521` / `671e8766e` (APK split) and the README commits.

| Change | Commit | Files | Affects | Category |
| --- | --- | --- | --- | --- |
| VNI swipe: words spelled from sure letters | `4ce8b1964` | `VniSwipe.kt`, `SwipeDecoderDictionary.kt`, `VniSwipeTest.kt` | vi only | Minimal |
| Draw Đ with a transparent D | `3f1349ec4` | `KeyboardView.java` | vi only | Minimal |
| Built-in Vietnamese dictionary | `fc6200bc2` | `java/res/raw/main_vi.dict` | vi only | Minimal (check license) |
| Tone-row VNI made the default, old layout renamed | `3c3249483` + layouts `9710144` | layouts submodule, READMEs | vi users | Optional |
| Leave swipe mode when editing a swiped word | `dea9d3333`, `2326266ff` | `WordComposer.java` | all languages, all combiners | Shared fix |
| Typed word → personal dictionary, unblocks offensive filter, left-slot verbatim | `0a396277e` | `GeneralIME.kt`, `SuggestionBlacklist.kt`, `DictionaryFacilitator(Impl).java`, `ActionBar.kt` | all languages | Shared QoL, optional |

Uncommitted edits to `build.gradle` and `gradle.properties` are not part of this range. Keep them out of upstream branches.

## 1. Minimal: Vietnamese-only changes

These three changes only run for Vietnamese and can't affect other languages. Together they are the smallest upstream PR on top of the fork base.

### 1a. VNI swipe: also offer words spelled from the sure letters

When a swipe goes up to the tone row, the finger passes over top-row letters (i, o). The model may then offer only words with those extra letters, e.g. diệt or đoạt for đệt. The fix spells words from the anchor letters alone and adds the tone variants that are real words.

1. In `java/src/org/futo/inputmethod/latin/VniSwipe.kt`, add this above `MAX_VARIANTS`:

```kotlin
/**
 * Words spelled with only the letters the swipe surely typed, one per anchor:
 * [ds][e][t] -> det, set. A swipe up to the tone row sweeps over letters of the top row, so
 * the model may offer none of these (diet, doat for det).
 */
fun anchorSpellings(anchors: List<Anchor>): List<String> =
    if (anchors.isEmpty()) emptyList()
    else anchors.fold(listOf("")) { acc, a ->
        acc.flatMap { p -> a.letters.sorted().map { p + it } }.take(MAX_VARIANTS)
    }
```

2. In `SwipeDecoderDictionary.kt` (VNI branch, after `endFixed` is built), add the new candidates with the worst model score, so they never beat a real model hit on score alone:

```kotlin
// The sure letters alone, with the picked tones (đệt when the model only saw diệt, đoạt)
val worst = candidates.minByOrNull { it.pathScore }
val fromAnchors = if(crossedTones.isEmpty() || worst == null) emptyList()
    else VniSwipe.anchorSpellings(anchors)
        .flatMap { VniSwipe.markedVariants(it, crossedTones) }
        .filter(isValidWord)
        .map { VniCandidate(it, worst.score, worst.pathScore) }
```

3. In the same function, add `fromAnchors` to the merged list. If you keep the debug log, add it there too:

```kotlin
val all = (offered + endFixed + fromAnchors).distinctBy { it.word }
```

4. In `tests/unit/org/futo/inputmethod/latin/VniSwipeTest.kt`, add the two tests `anchorSpellingsSpellEachSureLetterOnce` and `theDetSwipeFindsDetWhenTheModelOnlyOffersDiet` (copy them from commit `4ce8b1964`).

Other languages are safe because the new code needs `crossedTones` to be non-empty, and that only happens on a layout with tone keys.

### 1b. Draw Đ the same way as đ

The stroke-mark key label draws the base letter faded and the bar at full strength. For Đ, the bar sticks out to the left of the D, so subtracting D from Đ doesn't line up. The fix fades the whole Đ and redraws only its left overhang (the bar) at full alpha.

1. In `KeyboardView.java`, in the stroke-mark drawing method (added by `55c232aaa`), compute both glyph paths before drawing. Then add an early branch for base `"D"`:

```java
final RectF glyphBounds = new RectF();
final RectF baseBounds = new RectF();
strokePath.computeBounds(glyphBounds, true);
basePath.computeBounds(baseBounds, true);
final float overhang = glyphBounds.width() - baseBounds.width();
if (base.equals("D") && overhang > 0.0f) {
    paint.setAlpha(Math.round(alpha * STROKE_MARK_BASE_ALPHA));
    canvas.drawText(stroked, left, y, paint);
    paint.setAlpha(alpha);

    final Path overhangPath = new Path();
    overhangPath.addRect(glyphBounds.left, glyphBounds.top,
            glyphBounds.left + overhang * 0.8f, glyphBounds.bottom, Path.Direction.CW);
    overhangPath.op(strokePath, Path.Op.INTERSECT);
    overhangPath.computeBounds(strokeBounds, true);
    strokeBounds.left = glyphBounds.left;
    strokeBounds.right = glyphBounds.left + overhang + paint.getTextSize() * 0.12f;
    canvas.save();
    canvas.clipRect(strokeBounds);
    canvas.drawText(stroked, left, y, paint);
    canvas.restore();
    paint.setTextAlign(align);
    return true;
}
```

2. Move the faded `drawText(base, …)` of the old đ path below this branch, and hoist `strokeBounds` above it. The lowercase đ path stays the same.

The file is shared, but this only runs for keys that use stroke-mark labels, and only VNI layouts have those. If upstream doesn't want stroke-mark rendering at all, 1b goes with it.

### 1c. Built-in Vietnamese dictionary

1. Put `main_vi.dict` in `java/res/raw/`. `DictionaryInfoUtils` finds raw dictionaries by the `main_` prefix plus the locale, so no code change is needed.
2. Before opening the PR, write down where the word list came from and its license, and the tool and version used to build the `.dict`. Upstream ships only `main.dict` (English) built in. Ask the maintainers whether 128 KB in the APK is fine, or whether they'd rather offer it as a downloadable dictionary.

**Manual check (no unit test for the UI parts):**

- [ ] Swipe đệt (d-e-t, up to 9, 6 and the dot below): đệt shows up in the suggestions.
- [ ] Normal VNI swipes (việt, thật) rank the same as before.
- [ ] The Đ key label (shift on) shows a faded D with a solid bar, lined up with the D.
- [ ] The lowercase đ label looks unchanged.
- [ ] Fresh install with Vietnamese enabled: suggestions appear without downloading a dictionary.
- [ ] English swipe and typing are unchanged.

## 2. Optional: Vietnamese-only product decisions

Making the tone-row layout the default changes things for existing VNI users, so it's the maintainers' call. Offer it as a separate PR to the layouts repo, never inside the code PR.

**Minimal version (recommended first):** add the tone-row layout and change nothing else.

1. In `futo-keyboard-layouts`, add `LatinScript/Asia/vietnamese_vni_tone_row.yaml` with `name: VNI (tone row)`. Leave `vietnamese_vni.yaml` unchanged.
2. In `mapping.yaml`, add `vietnamese_vni_tone_row` to the end of the `vi:` list, after `vietnamese_vni`.
3. Once the layouts PR is merged, bump the `java/assets/layouts` submodule in the keyboard PR.

**Fork version (layouts commit `9710144`): only if the maintainers agree.**

1. Rename `vietnamese_vni_tone_row.yaml` to `name: VNI`, and `vietnamese_vni.yaml` to `name: VNI (legacy)`.
2. In `mapping.yaml`, move `vietnamese_vni_tone_row` to the top of `vi:`, above `vietnamese_telex`.

Users who already have the old layout keep it by its id. Only its display name changes, so the rename doesn't break saved settings. New users get tone-row VNI by default instead of Telex. That's the part upstream may reject.

Don't send the README and README.vi.md edits from `3c3249483`: they describe the fork.

## 3. Changes that affect other languages

One is a core fix and the other is a behaviour change that touches every language. Neither is needed for the minimal Vietnamese upgrade, except that leaving out 3a keeps the swipe-edit bug in.

### 3a. Leave swipe mode when a swiped word is edited by tapping (shared fix)

After a word is swiped, tapping a letter or backspace still left `WordComposer` in batch mode, so the old gesture was decoded again. Taps go through `applyProcessedEvent`. Combiners (VNI, Telex, Hangul and others) consume the tap in `processEvent` and never reach it, so both paths need the check. Commits: `dea9d3333`, `2326266ff`.

1. In `WordComposer.java`, add a flag next to `mIsBatchMode`:

```java
// True while setBatchInputWord feeds the swiped word in, so those events keep batch mode.
private boolean mIsSettingBatchInputWord;
```

2. Add the helper. It drops batch mode and turns the stored pointers into "no coordinate" entries, so the word is treated as typed:

```java
// The user is editing a swiped word by tapping or deleting: from now on it is a typed word,
// so stop re-decoding the old gesture for it.
private void leaveBatchModeForTypedEdit() {
    if (!mIsBatchMode || mIsSettingBatchInputWord) return;
    mIsBatchMode = false;
    mInputPointers.reset();
    final int length = Math.min(size(), MAX_WORD_LENGTH);
    for (int i = 0; i < length; i++) {
        mInputPointers.addPointerAt(i,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, 0, 0);
    }
}
```

3. Call it in two places. At the end of `processEvent`, just before `return processedEvent;`, add `if (processedEvent.isConsumed()) leaveBatchModeForTypedEdit();`. On the first line of `applyProcessedEvent`, add `leaveBatchModeForTypedEdit();`.
4. In `setBatchInputWord`, set `mIsSettingBatchInputWord = true;` right after `mIsBatchMode = true;`, and set it back to `false` after the loop.

Open this as its own small PR, with a repro in English (swipe a word, tap a letter, check the suggestions) and one in a combiner language. A `WordComposer` unit test is worth adding: it's deterministic and needs no UI.

### 3b. Gboard-style personal dictionary and suggestion order (optional QoL)

One commit (`0a396277e`) with three linked behaviours, all for every language:

- Tapping the typed (verbatim) suggestion adds it to the Android personal dictionary, when no dictionary knows it or the offensive-word filter blocks it.
- Words in the personal dictionary are never blocked as offensive.
- In the suggestion bar, the typed word moves to the left slot, and emoji and other matches fill the sides.

To keep the upstream diff minimal, leave 3b out. If you offer it, send it as a separate PR and suggest putting it behind a setting that is off by default.

1. `DictionaryFacilitator.java`: add `boolean isInUserDictionary(final String word);`. In `DictionaryFacilitatorImpl.java`, implement it:

```java
@Override
public boolean isInUserDictionary(final String word) {
    return isValidWord(word, new String[] { Dictionary.TYPE_USER });
}
```

2. `SuggestionBlacklist.kt`: add a constructor parameter `val isInPersonalDictionary: (String) -> Boolean = { false }`, and in `isWordOk` change the filter line to:

```kotlin
if(shouldBlockWord(mode, word) && !isInPersonalDictionary(word)) return false
```

3. `GeneralIME.kt`: pass `isInPersonalDictionary = { dictionaryFacilitator.isInUserDictionary(it) }` to `SuggestionBlacklist`. Add the helper and a `FREQUENCY_FOR_PERSONAL_DICTIONARY_ADDS = 250` constant in the companion object (the same frequency the personal-dictionary settings use):

```kotlin
private fun addTypedWordToPersonalDictionary(word: String) {
    if(word.isBlank() || settings.current.mInputAttributes.mNoLearning) return
    if(dictionaryFacilitator.isInUserDictionary(word)) return
    if(dictionaryFacilitator.isValidSuggestionWord(word) && suggestionBlacklist.isWordOk(word)) return

    val locale = settings.current.mLocale
    helper.lifecycleScope.launch(Dispatchers.IO) {
        UserDictionaryCompatUtils.addWord(context, word, FREQUENCY_FOR_PERSONAL_DICTIONARY_ADDS, null, locale)
    }
}
```

In the `EVENT_TYPE_SUGGESTION_PICKED` branch, call it before `onPickSuggestionManually` when `event.mSuggestedWordInfo!!.isKindOf(SuggestedWordInfo.KIND_TYPED)`.

4. `ActionBar.kt` (`SuggestionItems`): in both the `autocorrectMatch != null` branch and the `else` branch, build the side slots as `listOfNotNull(verbatim, emojiMatches.firstOrNull()) + matches`. Show `sides[0]` on the left and `sides[1]` on the right. In the `else` branch, hide the verbatim word for `INPUT_STYLE_TAIL_BATCH`, and when it is empty or equals the center word. Copy the exact code from `0a396277e`.

Upstream will likely push back on two points. Some users want the offensive-word filter to win, so the second behaviour may be refused. And adding words to the personal dictionary is permanent, so it shouldn't happen without a setting.

**Manual check:**

- [ ] 3a, English: swipe "hello", tap "s": suggestions follow "hellos", and the gesture isn't decoded again.
- [ ] 3a, VNI and Telex: swipe a word, then tap a tone key or backspace: the tone applies and the word stays typed.
- [ ] 3b: type an unknown word, tap it in the left slot: it appears in Settings → Personal dictionary and is swipeable afterwards.
- [ ] 3b: in an incognito or no-learning field, tapping the typed word adds nothing.

## PR order and branch steps

Open five PRs in this order. Each builds on upstream's current master, not on the fork's master.

| # | PR | Commits to cherry-pick | Repo | Required? |
| --- | --- | --- | --- | --- |
| 1 | VNI tone row + VNI swipe base | `6c69f47dc`, `e6bfc2405`, `3cb6fe7bf`, `9d0a84a9c`, `6784d3c45`, `8a78a98d7`, `55c232aaa` | keyboard + layouts | Yes |
| 2 | Swipe anchor spellings, Đ drawing, `vi` dictionary | `4ce8b1964`, `3f1349ec4`, `fc6200bc2` | keyboard | Yes |
| 3 | Leave swipe mode on typed edit | `dea9d3333`, `2326266ff` | keyboard | Strongly recommended |
| 4 | Tone-row VNI as default | layouts `9710144` | layouts | Optional |
| 5 | Gboard-style personal dictionary | `0a396277e` | keyboard | Optional |

Steps for each keyboard PR:

1. Add FUTO's repo as a remote once: `git remote add upstream <upstream-url>`, then `git fetch upstream`.
2. Branch from upstream: `git switch -c vi/swipe-anchors upstream/master`.
3. Cherry-pick the listed commits in order: `git cherry-pick <sha> …`. Fix conflicts by hand where upstream has moved since `7d61ab413`.
4. Make sure the diff has no fork-only content: `git diff upstream/master --stat` should list only the files named in this doc. Remove submodule bumps to the fork's layouts repo, the README edits, `build.gradle` / `gradle.properties`, and debug-only code (`c5781ee9f`).
5. In each submodule bump, point `java/assets/layouts` at a commit that is already in upstream's layouts repo, not the fork.
6. Run the unit tests (`VniSwipeTest` and any new `WordComposer` test) and go through the manual checks in sections 1 and 3 on a device.

Before opening the PRs:

- [ ] Dictionary source and license written in PR 2's description
- [ ] Each PR builds and runs by itself on top of upstream master
- [ ] PR 3 has an English repro, so it doesn't read as a Vietnamese-only fix
- [ ] PR 5 has a setting (off by default), or the description says why it doesn't
