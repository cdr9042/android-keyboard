## 📋 BÀN GIAO: Swipe tiếng Việt với hàng dấu VNI (FUTO Keyboard)

**Status:** 🔄 IN PROGRESS. Đã có layout và debug tool. Phần nhận dấu bằng chỗ rẽ chạy đúng. Gợi ý từ vẫn sai trong nhiều trường hợp.
**Created:** 2026-11-16
**Last Updated:** 2026-11-16
**File Location:** `docs/handovers/2026-11-16-vni-tone-row-swipe.md`
**Branch:** `feature/vni-tone-row-swipe` (app repo). Commits: `6c69f47dc` (code), `e6bfc2405` (bump submodule layouts)

---

### 1. TỔNG QUAN CÔNG VIỆC

**Mục tiêu của người dùng:**
- Có layout VNI mà các phím gõ dấu **hiện đúng dấu** (´ ` ̉ ˜ ̣ ˆ ̛ ˘ đ).
- **Swipe tiếng Việt** phải hiểu rằng vuốt qua phím dấu nghĩa là thêm dấu đó. Ví dụ vuốt `m-e-^-'-n` phải ra "mến".

**Trạng thái:**
- ✅ Layout mới "VNI (tone row)", có hàng dấu. Tách khỏi hoặc gộp với hàng số tùy setting number row.
- ✅ Nhận dấu bằng **chỗ rẽ** của đường vuốt (không phải chỉ cần đi ngang qua phím). Đã xác nhận trên log thật: vuốt 6→1 dọc hàng dấu chỉ nhận {6, 1} và bỏ qua 2..5.
- ✅ Ghép lại dấu: lấy chữ gốc của mọi ứng viên model đưa ra, rồi ghép **đúng** bộ dấu đã nhận, chỉ giữ từ có trong từ điển (thiết→thiệt, dương→đường).
- ✅ Debug: log `VNIDUMP`, script lọc log và vẽ SVG, setting "Keep swipe trail on screen" để chụp màn hình.
- ⚠️ **Chưa kiểm chứng trên máy** sau thay đổi cuối (ghép dấu theo chữ gốc + topK=8). Người dùng chưa gửi kết quả.
- ❌ **Vấn đề gốc chưa xử lý:** đoạn đường vuốt đi lên hàng dấu và quay xuống vẫn quét qua các phím chữ. Model coi đó là chữ (xem mục 9, việc số 1).

---

### 2. CONTEXT & VẤN ĐỀ GIẢI QUYẾT

**Cách VNI hoạt động trong app:**
- `VNICombiner` coi chữ số **fullwidth** U+FF11..U+FF19 là phím dấu. Chữ số ASCII vẫn là số.
- Mapping dấu: 1 sắc, 2 huyền, 3 hỏi, 4 ngã, 5 nặng, 6 mũ (^), 7 móc (ơ ư), 8 trăng (ă), 9 đ, 0 xoá dấu.

**Swipe decoder:**
- File: `SwipeDecoderDictionary.kt`.
- Dùng một **model neural chỉ biết chữ cái** (ExecuTorch `futo-swipe/.../model_fp32.pte`) cộng với trie từ điển. Trie đã map ký tự có dấu về chữ gốc, nên model **tự ra được từ có dấu** (miên, muốn...).
- Model **không biết** phím dấu. Phím có code là chữ số bị loại khỏi `letters` trong `buildLayoutInfo`.
- **Toàn bộ xử lý dấu nằm trong code Kotlin, không đụng tới model.**

**Pipeline hiện tại, trong `getSuggestions`:**
1. `toneKeys` = các phím có `swipeCode` U+FF11..FF19. Không có thì chạy như cũ.
2. `stripTonePoints`:
   - Với mỗi segment, tính chữ số dấu dưới từng điểm.
   - `VniSwipe.pickedTones` nhận dấu tại các **đỉnh rẽ ≥ 60°**, và dấu ở điểm đầu hoặc cuối của đường vuốt.
   - Các điểm nằm trên hàng dấu bị **xoá** khỏi path trước khi đưa vào model.
3. Model decode với `topK = 8` nếu có dấu được nhận (4 nếu không, 1 với gesture chưa xong).
4. Với mỗi ứng viên (distinct theo `baseLetters`): `VniSwipe.markedVariants(word, crossedTones)` sinh mọi cách viết chữ gốc mang **đúng** bộ dấu đó, rồi lọc bằng `isValidWord` (`DictionaryFacilitatorImpl.isValidSuggestionWord`). Biến thể thừa hưởng `score` và `pathScore` (ctc) của ứng viên gốc.
5. `VniSwipe.rerank` xếp hạng:
   - đầu tiên là từ có dấu ⊆ dấu đã nhận, nhiều dấu hơn đứng trước, cùng số dấu thì `pathScore` (ctc) cao hơn đứng trước;
   - tiếp theo là từ không dấu;
   - cuối cùng là từ dùng dấu không được nhận.
   - Điểm số được gán lại theo thứ tự giảm dần để thứ tự mới không bị sort lại.

**Các quyết định chính (người dùng đã chọn):**
- **Layout mới**, không ghi đè layout VNI cũ. Lý do: không phá người dùng cũ.
- Number row **bật**: hàng số thật ở trên, hàng dấu riêng ở dưới (chỉ hiện dấu). Number row **tắt**: một hàng gộp, hiện chữ số kèm hint dấu, và vẫn là phím dấu khi swipe.
- Nhận dấu **bằng chỗ đổi hướng** của đường vuốt. Lý do: vuốt ngang dọc hàng dấu sẽ đi qua nhiều dấu khác.
- **Không chạy build/install tự động.** Người dùng tự build bằng Android Studio vì build quá lâu.

---

### 3. FILES ĐÃ TẠO/SỬA

#### ✅ Files đã tạo
1. **`java/src/org/futo/inputmethod/latin/VniSwipe.kt`** (~181 dòng). Logic thuần, không import Android, nên test được trên JVM.
   - `isToneCode`, `toneDigit` (dòng 22–24)
   - `markDigits(word)`: chữ số VNI cần để gõ các dấu của từ, qua NFD (dòng 27)
   - `toneDigitsAt`: chữ số dấu dưới từng điểm (dòng 46)
   - `pickedTones(xs, ys, digits, minStep)`: thin path theo `minStep`, rồi tính góc rẽ trên cửa sổ 1 và 2 bước, ngưỡng `MIN_TURN_DEGREES = 60` (dòng 57)
   - `rerank(candidates, crossed, pathScoreOf, wordOf)` (dòng 109)
   - `baseLetters(word)`: bỏ hết dấu, đ→d (dòng 135)
   - `markedVariants(word, tones)`: tối đa 1 dấu thanh (1..5); dấu 6 cho a/e/o, 7 cho o/u, 8 cho a, 9 cho d; giữ biến thể có `markDigits == tones`; cap `MAX_VARIANTS = 64` (dòng 145)
2. **`tests/unit/org/futo/inputmethod/latin/VniSwipeTest.kt`** (JUnit4): test cho markDigits, pickedTones (bàn phím giả), rerank, markedVariants, baseLetters.
3. **`java/assets/layouts/LatinScript/Asia/vietnamese_vni_tone_row.yaml`** (trong submodule, commit `cef117b`).
   - Hàng `numRowMode: Hideable`: `"1|１"` … `"0|０"` kèm `hint` là dấu.
   - Hàng `numRowMode: Filler`: chỉ hiện dấu, ví dụ `"´|１"`.
   - Sau đó là các hàng qwerty.
   - Đã đăng ký trong `mapping.yaml` dưới `vi:`, ngay sau `vietnamese_vni`.
4. **`tools/vni-swipe-debug/vni_swipe_debug.sh`** và **`vni_swipe_plot.py`**: lọc log của lần vuốt cuối, in tóm tắt khoảng 3 dòng, vẽ SVG. Bản gốc ở scratchpad đã mất, đây là bản tạo lại và **chưa commit**.
5. **`docs/volio/specs/2026-11-13-vni-tone-row-and-swipe-design.md`** và **`docs/volio/plans/2026-11-13-vni-tone-row-and-swipe.md`**: ⚠️ **đã cũ**. Còn mô tả `MIN_POINTS_ON_KEY` và "crossed tones", chưa có phần nhận dấu bằng chỗ rẽ hay ghép dấu. Chưa commit.

#### ✅ Files đã sửa
- **`java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt`**
  - `KeepSwipeTrailSetting` (dòng 235)
  - `logVniGesture` in `VNIDUMP keys` và `VNIDUMP path` (dòng 253)
  - `VniCandidate` (dòng 273)
  - `stripTonePoints` (dòng 275)
  - `getSuggestions` có thêm tham số `isValidWord: (String) -> Boolean` (dòng 454)
  - `toneKeys` (dòng 477), `topK` (dòng 535)
  - ghép dấu và rerank (khoảng dòng 588–610)
  - log `VNI toneKeys=… crossedTones=… model=… added=… ranked=…`
- **`java/src/org/futo/inputmethod/latin/DictionaryFacilitatorImpl.java:888`**: truyền `this::isValidSuggestionWord` vào swipe decoder.
- **`java/src/org/futo/inputmethod/keyboard/internal/GestureTrailDrawingPoints.java`**: `keepTrail()` đọc `DataStoreHelper.getSetting(KeepSwipeTrailSetting)`. Khi bật, vệt không mờ và không co. Khi bắt đầu nét mới thì xoá vệt cũ (`mTrailStartIndex = downIndex`).
- **`java/src/org/futo/inputmethod/latin/uix/settings/pages/DevSettings.kt`**: thêm toggle "Keep swipe trail on screen" vào mục *Swipe debug*.
- **`build.gradle`**: `sourceSets { test { java.srcDirs = ['tests/unit'] } }` để chạy unit test JVM.
- **Repo riêng `../futo-keyboard-layouts`** (branch `main`): `mapping.yaml` đã sửa và `vietnamese_vni_tone_row.yaml` vẫn **chưa commit**. Đây là bản nguồn của các file đã copy vào submodule.

---

### 4. CÁC THAY ĐỔI QUAN TRỌNG

#### a. Nhận dấu: từ "đi qua phím" chuyển sang "rẽ trên phím"
**Before:** đếm số điểm nằm trên mỗi phím dấu (`MIN_POINTS_ON_KEY`). Vuốt 6→1 thì nhận cả {1..6}.
**After:** `pickedTones` chỉ nhận phím mà đường vuốt rẽ ≥ 60° trên đó, hoặc phím ở điểm đầu/cuối.
**Impact:** log thật của `m-e-^-'-n` cho góc 1=160°, 6=164°, 2=47°, 3/4/5≈0°, nên chỉ nhận {1, 6}. ✅

#### b. Ghép dấu từ chữ gốc
**Before:** chỉ sắp xếp lại 4 từ model đưa ra. "mến" không có trong top 4 (miên, mê-tín, muốn, men) nên không bao giờ ra.
**After:** với mọi ứng viên: `baseLetters`, rồi `markedVariants(base, picked)`, rồi lọc từ điển. Cùng số dấu thì xếp theo ctc. Với log trên, ctc: men −12.56, mê-tín −13.02, muốn −13.30, nên **mến** đứng đầu.

---

### 5. BUGS ĐÃ FIX

#### ❌ Bug: Crash khi swipe, "Failed to initialize SwipeDecoder … model_fp32.pte (err=0x23)"
**Root cause:** file model trong submodule `futo-swipe` chỉ là pointer Git LFS (132 byte).
**Fix:** cài git-lfs, rồi chạy `git lfs install --local && git lfs pull` (khoảng 13.4 MB). Không phải lỗi do code.

#### ❌ Bug: Rerank theo "bằng nhau" thất bại
**Before:** yêu cầu bộ dấu của từ == bộ dấu đã đi qua. Bộ dấu đi qua là {1..6} nên không từ nào khớp.
**After:** dùng quan hệ tập con, sau đó chuyển sang nhận dấu bằng chỗ rẽ.

#### ❌ Bug: Gợi ý sai dấu (ảnh chụp ngày 2026-11-13)
| Vuốt | Model đưa ra | Nguyên nhân |
|---|---|---|
| thiệt | thiêu, thiếng, thiên, thiết | code cũ chỉ ghép dấu cho từ không dấu |
| đường | sương, dương, suông | như trên |
| ước | ươi, ương, ưu-nhược | "uoc" không có trong top 4 của model |

**Fix:** ghép dấu theo chữ gốc của mọi ứng viên, và topK=8. ⚠️ Chưa kiểm chứng trên máy.

---

### 6. TEST RESULTS

#### Unit test `VniSwipeTest`
**Command:**
```bash
JAVA_HOME=~/Unity/Hub/Editor/6000.3.25f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK ANDROID_HOME=~/Android/Sdk ./gradlew :testUnstableDebugUnitTest --tests '*VniSwipeTest*'
```
**Status:**
- ✅ 6/6 pass ở bản đầu (trước khi có nhận dấu bằng chỗ rẽ).
- ⚠️ Các test sau (pickedTones, markedVariants, baseLetters, tiebreak bằng ctc) **chưa ai chạy**. Agent không được chạy Gradle (xem mục 9). Có thể có lỗi compile hoặc test fail.

#### Debug script trên log giả
**Command:** `OUT_DIR=/tmp/x tools/vni-swipe-debug/vni_swipe_debug.sh /tmp/x/raw.log`
**Kết quả:** `picked [1, 6]`, góc 1=90, 6=74. ✅

#### Thủ công trên máy ảo (do người dùng thực hiện)
- ✅ `m-e-^-'-n`: nhận đúng {1, 6}. ❌ Ra "mê-tín" (trước khi có phần ghép dấu).
- ❌ thiệt / ước / đường: sai như bảng ở mục 5 (trước khi ghép dấu theo chữ gốc).
- ⏳ Bản mới nhất (commit `6c69f47dc`): **chưa thử**.

---

### 7. DEPENDENCIES & PREREQUISITES

#### Required Setup
- **JDK 17.** JDK 25 mặc định báo lỗi "Unsupported class file major version 69". Dùng `JAVA_HOME=~/Unity/Hub/Editor/6000.3.25f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK`.
- **Android SDK:** `ANDROID_HOME=~/Android/Sdk`, không cần ghi `local.properties`.
- **Git LFS:** đã cài, đã pull model.
- **Gradle tasks:** `:compileUnstableDebugKotlin`, `:testUnstableDebugUnitTest`, `:installUnstableDebug`.
- **Emulator:** `emulator-5554` (AVD `Medium_Phone_API_37.0`, Android 17, x86_64).
- **Bật developer mode trong app:** Help → bấm dòng version 8 lần. Sau đó vào Developer settings → Swipe debug để có:
  - "Keep swipe trail on screen"
  - "Activate swipe debug logging for 5 minutes", cần cho bản release; bản `BuildConfig.DEBUG` luôn log.

#### Related Files to Review
- `java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt`, hàm `getSuggestions`: toàn bộ pipeline.
- `java/src/org/futo/inputmethod/latin/VniSwipe.kt`: logic thuần.
- `VNICombiner` (tìm bằng grep): cách chữ số fullwidth thành dấu.
- `java/assets/layouts/LatinScript/Asia/vietnamese_vni_tone_row.yaml`: layout.

---

### 8. CÁC VIỆC CÓ THỂ LÀM TIẾP

#### High Priority
1. **Cắt đoạn đường đi lên và quay xuống hàng dấu.** Đây là nguyên nhân gốc. Đã đề xuất với người dùng, **chưa được duyệt chính thức**.
   - Hiện tại chỉ xoá các điểm *nằm trên* hàng dấu. Đoạn từ chữ cuối (vd. e) đi lên phím ^ vẫn quét qua r, t, y, u, nên model ra thiết, thêu, theo khi vuốt `the^/`.
   - Cách làm:
     - Từ điểm đi vào hàng dấu, lùi ngược về chỗ rẽ hoặc dừng gần nhất (đỉnh rẽ ≥ ngưỡng trên path đã thin). Xoá đoạn giữa, chỉ giữ phím chữ tại đỉnh đó.
     - Lúc quay xuống làm đối xứng: giữ phím chữ nơi đường vuốt rẽ hoặc kết thúc lần đầu.
   - Viết thành hàm thuần trong `VniSwipe`, có test.
   - Kỳ vọng: `the^/` ra thế, `m-e-^-'-n` ra mến, ước ra ước.
2. **Kiểm chứng commit `6c69f47dc`.** Người dùng tự build, rồi chạy `VniSwipeTest` và thử thiệt / ước / đường / mến.
3. **Ràng buộc dấu cứng.** Khi có ứng viên mang **đúng** bộ dấu đã nhận, chỉ hiện những từ đó, bỏ các từ chỉ dùng tập con. Đây là bước 2 của thiết kế đã đề xuất.

#### Medium Priority
4. **Cập nhật spec/plan** trong `docs/volio/` cho đúng thiết kế hiện tại (nhận dấu bằng chỗ rẽ, ghép dấu theo chữ gốc, cắt đoạn đường), rồi xin commit.
5. **Commit repo `futo-keyboard-layouts`** (`mapping.yaml` + yaml mới) và **push submodule** `cef117b`. Cần hỏi người dùng trước.
6. **Tinh chỉnh tham số** nếu log cho thấy cần: `MIN_TURN_DEGREES = 60`, `minStep = toneKeyWidth / 3`, `topK = 8`, `MAX_VARIANTS = 64`.

#### Optional
7. Vẽ lên SVG đoạn bị cắt (sau việc số 1) để người dùng thấy được.
8. Gỡ hoặc giảm log `VNIDUMP` trước khi merge.

---

### 9. LƯU Ý QUAN TRỌNG

#### ⚠️ KHÔNG tự chạy build/install/Gradle hay adb
**Problem:** người dùng đã nói rõ: "đợi lâu quá, đừng có tự chạy nữa, để tôi dùng android studio". Lệnh `gradlew test` cũng bị auto-mode classifier từ chối.
**Solution:** viết code, rồi nhờ người dùng build và chạy test trong Android Studio. Đọc log qua **clipboard**:
```bash
wl-paste | tools/vni-swipe-debug/vni_swipe_debug.sh /dev/stdin
```
Người dùng copy Logcat rồi nói "xem clipboard". Người dùng không muốn tạo file text.
**Impact if ignored:** người dùng bực mình, tool call bị từ chối.

#### ⚠️ Không commit khi chưa hỏi
Hai commit hiện có là **người dùng tự commit**. Agent phải hỏi trước mỗi commit hoặc push, nhất là với submodule và repo layouts.

#### ⚠️ Chính sách test của dự án (CLAUDE.md)
- Hành vi gesture/cảm giác vuốt: **không viết unit test**, dùng checklist thủ công.
- Logic thuần (markDigits, markedVariants, pickedTones trên bàn phím giả, rerank): **được** viết unit test.
- Phải nói rõ ở bước TDD là chọn cách nào.

#### ⚠️ Volio workflow
Mọi task phải phân tier trước (skill `task-tiering-volio-style`). Thay đổi thuật toán swipe là T2–T3: cần brainstorm và người dùng duyệt thiết kế trước khi code.

#### ⚠️ Ngôn ngữ
Người dùng nói tiếng Việt, nên trả lời bằng tiếng Việt.

#### ⚠️ Model chỉ biết chữ cái
Đừng cố sửa model hay trie. Mọi xử lý dấu nằm trong Kotlin, sau khi decode. Trie đã có sẵn từ có dấu, nên model *đã* ra được từ có dấu khi không chạm hàng dấu.

---

### 10. REFERENCE FILES
- **Spec (cũ):** `docs/volio/specs/2026-11-13-vni-tone-row-and-swipe-design.md`
- **Plan (cũ):** `docs/volio/plans/2026-11-13-vni-tone-row-and-swipe.md`
- **Logic thuần:** `java/src/org/futo/inputmethod/latin/VniSwipe.kt`
- **Pipeline:** `java/src/org/futo/inputmethod/latin/SwipeDecoderDictionary.kt`
- **Tests:** `tests/unit/org/futo/inputmethod/latin/VniSwipeTest.kt`
- **Layout:** `java/assets/layouts/LatinScript/Asia/vietnamese_vni_tone_row.yaml`, `java/assets/layouts/mapping.yaml`
- **Debug tool:** `tools/vni-swipe-debug/`
- **Vệt swipe:** `java/src/org/futo/inputmethod/keyboard/internal/GestureTrailDrawingPoints.java`
- **Transcript phiên trước:** `~/.claude/projects/-run-media-trongnlh-T7-AppDevelopment-futo-android-keyboard/510d0a6a-2ac8-4b4c-8775-7585d6114892.jsonl`

---

### 11. NEXT CHAT CÓ THỂ BẮT ĐẦU TỪ ĐÂU

**Nếu muốn tiếp tục:**
1. Đọc file này, rồi `VniSwipe.kt` và hàm `getSuggestions` trong `SwipeDecoderDictionary.kt`.
2. Hỏi người dùng kết quả build/test của commit `6c69f47dc`. Nếu có log thì đọc clipboard bằng `wl-paste | tools/vni-swipe-debug/vni_swipe_debug.sh /dev/stdin`.
3. Xin duyệt thiết kế "cắt đoạn đường đi lên/quay xuống" (High Priority #1). Viết test trước cho hàm thuần trong `VniSwipe`, rồi nối vào `stripTonePoints`.
4. Nhờ người dùng build, vuốt `the^/`, `m-e-^-'-n`, `ước`, `đường`, `thiệt`, rồi gửi clipboard và ảnh có vệt.

**Nếu muốn kiểm chứng:**
1. Người dùng chạy `VniSwipeTest` trong Android Studio.
2. Dòng log `VNI … model=… added=… ranked=…`: `added` phải chứa từ đúng (mến, thiệt, đường, ước) và `ranked` phải có từ đó ở đầu.
3. Tóm tắt từ script: `picked` phải đúng bộ dấu người dùng muốn.

---

### 12. TIMELINE & ESTIMATED TIME

#### Time Spent (ước tính, 2026-11-13 → 2026-11-16)
- Phân tích, spec, plan: ~1h
- Layout + submodule + sửa crash LFS: ~1h
- Nhận dấu (3 lần làm lại), rerank, ghép dấu: ~2–3h
- Debug tool (log dump, plot, lọc, giữ vệt): ~1h
- **Total:** ~5–6h

#### Estimated Remaining Time
- Cắt đoạn đường + test: ~1–2h, cộng 2–3 vòng thử trên máy với người dùng
- Ràng buộc dấu cứng + tinh chỉnh: ~1h
- Cập nhật docs + commit 2 repo: ~30 phút
- **Total estimated:** ~3–4h
