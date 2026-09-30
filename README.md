# Gboard Patches Enhanced

**English:** An enhanced Gboard patch set with Backspace swipe gestures, numeric keypad support, and Chinese input punctuation improvements.

**中文：** 增强版 Gboard 补丁，支持删除键上滑手势、数字键盘适配，以及中文输入标点优化。

Repository: `DWcrea/Gboard-patches-Enhanced`

Based on [`jasonwu1994/Gboard-patches`](https://github.com/jasonwu1994/Gboard-patches), with additional input-efficiency features for Gboard 18.0.3.

> This repository is an independent derivative project. It is not authored or endorsed by Google, Gboard, Morphe, or the upstream Gboard-patches maintainer.

## Added in this fork

### Backspace swipe-up: delete before cursor

Swipe **up from Backspace** to delete everything **before the cursor** while preserving text after the cursor.

Example:

```text
abc|DEF  ->  |DEF
```

Normal Backspace tap, long-press repeat deletion, and the stock left-swipe delete gesture are preserved.

### Numeric keypad support

The Backspace swipe-up action also has a fallback path for numeric / phone keypad layouts so the gesture can work outside the standard alphabet keyboard.

### Half-width punctuation after Chinese swipe-down digits

When a digit is entered with the Chinese keyboard's swipe-down digit gesture, the immediately following punctuation is normalized:

```text
： -> :
。 -> .
． -> .
```

The normalization is one-shot and expires automatically.

## Base project

This project is based on **Gboard Patches 3.11.0** by [`jasonwu1994`](https://github.com/jasonwu1994/Gboard-patches). The upstream project contains the large majority of the patch framework and features in this repository.

Please refer to the upstream project for the original feature set, documentation, and history.

## Target version

The custom behavior in this fork was developed and tested against:

```text
Gboard 18.0.3.954559732-release-arm64-v8a
```

Other Gboard versions may require new hook points or updated patches.

## Build

Requirements:

- JDK 21
- Android SDK
- GitHub Packages access required by the Morphe Gradle plugin

On Windows, create `local.properties` in the repository root:

```properties
sdk.dir=C:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

Configure GitHub Packages credentials in:

```text
%USERPROFILE%\.gradle\gradle.properties
```

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

Then build:

```powershell
.\gradlew.bat :patches:buildAndroid
```

The generated `.mpp` is normally placed under:

```text
patches/build/libs/
```

## Usage

Use the generated `.mpp` with a **clean/original Gboard 18.0.3 APK**. Avoid stacking this patch on an APK already patched by an older development version.

## Development notes

The Backspace gesture went through several iterations before the stable implementation. The debugging history and V6 diagnostic notes are preserved under:

```text
docs/backspace-swipe-history/
```

The current feature notes are in:

```text
docs/BACKSPACE_SWIPE_UP_CLEAR.md
```

## License and attribution

The upstream repository is distributed under **GNU GPL v3**. This derivative repository keeps the original `LICENSE` and `NOTICE` files and remains distributed under the same license.

Upstream project:

- https://github.com/jasonwu1994/Gboard-patches

Morphe is referenced only for compatibility/build purposes. The `NOTICE` file contains an additional project-name restriction that applies to derivative works; this fork therefore uses a distinct project name.
