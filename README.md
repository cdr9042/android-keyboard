# FUTO Keyboard

[-> Xem bản Tiếng Việt 🇻🇳 <-](README.vi.md)

> This is an modified fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard). It improves Vietnamese VNI typing, see [Changes in this fork](#changes-in-this-fork). Report issues with these changes [here](https://github.com/cdr9042/android-keyboard/issues).

## Changes in this fork

### VNI layout with a tone row

A new layout, "VNI", is the default Vietnamese layout, listed first. The old VNI layout is kept, renamed "VNI (legacy)" ([layouts fork](https://github.com/cdr9042/futo-keyboard-layouts)). The tone keys still type the same VNI codes, but their labels show the mark they type:

| Key | Mark | Example |
|---|---|---|
| 1 | acute (sắc) | a1 → á |
| 2 | grave (huyền) | a2 → à |
| 3 | hook above (hỏi) | a3 → ả |
| 4 | tilde (ngã) | a4 → ã |
| 5 | dot below (nặng) | a5 → ạ |
| 6 | circumflex (mũ) | a6 → â, e6 → ê, o6 → ô |
| 7 | horn (móc) | o7 → ơ, u7 → ư |
| 8 | breve (trăng) | a8 → ă |
| 9 | stroke (gạch) | d9 → đ |

- Number row off (default): one row shows both the digit and the mark.
- Number row on: the real number row types digits, and the tone row sits under it showing only the marks.

### Tone-aware swipe typing

The swipe model only knows letters. Before, a swipe across the tone row fed those points to the model as noise, and the tone keys crossed were ignored, so the most frequent spelling won (`viet` -> whatever word was most common). Now, when the layout has VNI tone keys:

1. Picking tones. A tone key counts only where the finger rests on it: it stays slower than 0.005 key widths per ms for at least 60 ms, or the gesture starts or ends on it. Tone keys merely swept over (going from 6 to 1 passes 5, 4, 3 and 2) are fast and ignored.
2. Cutting the path. Each trip to the tone row is cut out of the path the model sees, from the last letter the finger rested on before it to the first one after it. Letters swept over on the way up and back down do not reach the model.
3. Letters surely typed. The gesture's start, end, rests (60 ms or more) and sharp turns (60 degrees or more) are letters the user clearly meant. A point on a key border may be either key. A candidate must hold all of them in order, with the start on its first letter and the end on its last. Candidates missing one are dropped, unless none has them all. This stops a short slowdown on a turn, or a key merely swept over, from changing the word (on a "thật" swipe the finger slowed for 34 ms on y between two tone keys; the model read t-h-a-y-t and offered "thay").
4. Re-spelling. The model is asked for 16 candidates instead of 4. Each candidate's base letters are also spelled with exactly the picked marks (men -> mến, thiết -> thiệt), kept only when the dictionary knows that word.
5. Re-ranking. Candidates whose marks were all picked come first, those using the most marks first, then by how well the swipe matches their letters. Then candidates without marks, then those using a mark that was not picked. The best scores are handed out in this order, so later sorting keeps it.

### Editing the tone of a swiped word

A word typed by swipe, or one the cursor comes back to, is loaded back into the VNI input as its VNI keys ("tháng" -> "tha1ng"). Tone keys pressed afterwards still add or change its marks.

The goal is to make a good modern keyboard that stays offline and doesn't spy on you. This keyboard is a fork of [LatinIME, The Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME), with significant changes made to it.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Nightly builds

Usually, a build close to the current master is available at [https://dl.keyboard.futo.org/nightly.apk](https://dl.keyboard.futo.org/nightly.apk). Read more about it here: https://docs.keyboard.futo.tech/improvements/nightly

The "Check for updates manually" button in the nightly build will check against the latest nightly, allowing you to get updated this way. It's recommended to check it once immediately after first installation in case an older version got downloaded due to CDN cache.

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

Please do not submit AI-generated pull requests, as these tend to make the codebase more difficult to understand. Pull requests made with clear heavy use of AI may be closed without comment. Please also avoid using AI to write issues, and try to explain it in your own words instead.

## Layouts

If you want to contribute layouts, check out the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts).

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recursive https://gitlab.futo.org/keyboard/latinime.git
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
