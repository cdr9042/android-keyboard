# VNI tone row labels and tone-aware swipe

Status: draft for review (T3). Not committed.

## Problem

1. The VNI layout (`futo-keyboard-layouts/LatinScript/Asia/vietnamese_vni.yaml`) uses a row of fullwidth digits `１ … ０` as tone/diacritic keys. The keys are labelled with digits only, so users cannot tell which key types which mark.
2. Swiping over a tone key does nothing useful. The swipe decoder (`SwipeDecoderDictionary.kt`) is a letters-only neural model; the trie maps accented characters to base letters, so `viet` resolves to whichever Vietnamese word is most frequent, regardless of the tone keys the user crossed. Points on the digit row also pollute the path fed to the model.

## Goals

- Tone keys show the mark they type.
- A layout option splits the tone row from the real number row.
- Swiping across tone keys filters those points out of the model input and prefers candidates whose marks match the tone keys crossed.

## Non-goals

- No native (C++) change, no model retraining, no change to the trie.
- No change to typing behaviour: tone keys still send `U+FF11..FF19`, and the `VNICombiner` is unchanged. ASCII digits stay literal.
- `０` (remove tone) is not used for ranking in this iteration.
- Telex layout is untouched.

## Design

### 1. Layout (repo `futo-keyboard-layouts`)

This is a **new layout** `vietnamese_vni_tone_row.yaml` (display name "VNI (tone row)"), registered after `vietnamese_vni` under `vi` in `mapping.yaml`. The existing `vietnamese_vni.yaml` is left untouched. Its contents:

- `numberRowMode: UserConfigurable` (was `AlwaysDisabled`). The real number row types ASCII digits.
- Two tone rows, both typing fullwidth digits (codes unchanged):
  - **Combined row** (`numRowMode: Hideable`, shown when the number row is off, the default): each key shows its digit plus mark hint: `1 ´`, `2 ̀`, `3 ̉`, `4 ̃`, `5 ̣`, `6 ^`, `7 ̛`, `8 ̆`, `9 đ`, `0`. It acts as both number row and tone row.
  - **Separate row** (`numRowMode: Filler`, shown when the number row is on): same keys, labelled with the mark only, sitting under the real number row.
- Exact keyspec/label-flag syntax (hint label vs. label, combining marks on `◌`) is settled in the plan by trying it in the app; the contract is only "label shows the mark, code stays `U+FF1x`".

The layout lives in a separate repo, which is the `java/assets/layouts` submodule of the app. The submodule is not initialised in this checkout. The layout change is committed in `futo-keyboard-layouts`; bumping the submodule pointer in the app repo is a separate step the user decides.

### 2. Swipe (app repo, Kotlin only)

New file `java/src/org/futo/inputmethod/latin/VniSwipe.kt` holding pure helpers, plus edits in `SwipeDecoderDictionary.getSuggestions`.

**Tone key set.** `toneKeys(keyboard)` = keys with `swipeCode` in `U+FF11..U+FF19`. If empty, the whole feature is skipped, so no other layout is affected. ASCII digit keys are never tone keys. When the number row is on, its real digits are ignored by swipe, as they are today.

**Pre-model filter.** Before `transformSegment`, drop gesture points that fall inside a tone key (`Key.isOnKey`). Letter keys are unaffected. Segments left with no points are discarded.

**Crossed tones.** From the *unfiltered* points, compute the set of tone digits whose key contains at least `MIN_POINTS_ON_KEY` (= 2) consecutive points. The threshold avoids counting keys merely grazed on the way to a letter. It is a named constant that can be tuned after manual testing. Order is not used; only the set (digits 1-9).

**Mark set of a candidate.** `viMarkDigits(word)`: NFD-decompose and map: acute→1, grave→2, hook above→3, tilde→4, dot below→5, circumflex→6, horn→7, breve→8, `đ`→9. Result is a set of digits. Example: `việt` → {5, 6}; `đi` → {9}; `viet` → {}.

**Re-rank.** Stable partition of the model's candidates:

1. If no tone key was crossed, leave the list untouched (current behaviour).
2. Otherwise move candidates whose mark set equals the crossed set to the front, keeping relative order inside each group. If none matches, leave the list untouched; never return fewer candidates.

Applied after `decoder.recognize` and before building `SuggestedWordInfo`, for both partial and tail batches (the same code path).

### 3. Data flow

```
gesture points ─┬─ drop points on tone keys ─► model ─► candidates ─┐
                └─ crossed tone digits ────────────────────────────┴─► stable partition ─► suggestions
```

### Error handling

- No tone keys on the keyboard, no points, or an unexpected key lookup failure: fall back to current behaviour. Never throw out of `getSuggestions`.
- Candidate with no letters or odd Unicode: mark set is empty; it simply ranks in the non-matching group.

## Testing

Game/gesture policy applies: no unit tests for gesture behaviour.

- **Unit test** (pure, deterministic): `viMarkDigits` and the partition function in `tests/`, since they are plain string/list logic with no input variables.
- **Compile verification**: Gradle build of the app module.
- **Manual checklist** (delivered after implementation):
  1. VNI layout, number row off: one row, keys show digit + mark.
  2. Number row on: real number row above a mark-only tone row.
  3. Tap `1` after `a`: types `á`; tap `5`: dot below. Typing unchanged.
  4. Swipe `v-i-e-t`, crossing `6` and `5`: top suggestion is `việt`.
  5. Swipe `m-a` crossing `2`: top suggestion is `mà`.
  6. Swipe with no tone keys: same candidates as before.
  7. Swipe in English and Telex layouts: unchanged.
  8. Swipe path grazing a tone key en route to a letter: not counted as tone.

## Risks

- `MIN_POINTS_ON_KEY` may need tuning for fast swipes; only manual testing can set it.
- Re-rank only reorders model candidates, so if the model never proposes the right accented word, it still won't appear. Mitigated because the trie already lists all accent variants of the base letters.
- Layout label rendering of combining marks may need font tweaks; settled in the plan.
