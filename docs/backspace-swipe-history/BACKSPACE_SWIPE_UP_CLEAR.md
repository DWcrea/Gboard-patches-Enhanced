# Backspace swipe-up delete-before-cursor

> Current local behavior: V8. Swipe up on Backspace deletes everything **before the cursor** and preserves the suffix. Number/phone keypads receive a metadata fallback, and a slide-down digit arms one-shot half-width `:` / `.` punctuation. See `BACKSPACE_SWIPE_UP_CLEAR_V8.md`.

This local fork extends the existing **Long-Press Editing Shortcuts** patch for Gboard 18.0.3.

Behavior:

- Tap Backspace: stock behavior.
- Swipe left from Backspace: stock Gboard gesture-delete behavior is left untouched.
- Swipe up from Backspace: the Backspace `SLIDE_UP` action is replaced with a private synthetic action, intercepted before stock Gboard, and clears the current editor.
- Disabling Long-Press Editing Shortcuts disables the injected gesture as well.

Implementation notes:

- Delete-key detection uses the stable Gboard resource entry `key_pos_del`, already used elsewhere in this repository.
- The injected synthetic keycode is `-0x6A53` and is consumed in `GboardLongPressQuickActions1803Runtime`. Existing stock `SLIDE_UP` metadata is deliberately replaced rather than skipped.
- Event recognition no longer depends on patched-metadata object identity because Gboard may clone metadata between binding and dispatch.
- Clear-all prefers `ExtractedText` + explicit full-range `setSelection` before committing an empty replacement.
- Clearing first uses `InputConnection.performContextMenuAction(android.R.id.selectAll)` + empty `commitText`, with `ExtractedText`/`setSelection` fallback.
- No root/Xposed requirement is introduced; this remains a Morphe patch change.

## Pointer anchoring

Gboard can retarget a pointer to a neighbouring key while a vertical gesture is in progress.
The implementation therefore reuses the repository's existing vertical-slide pointer-anchor
transform. Only the patched Backspace metadata is marked for this interop, so enabling this
feature does not add Zhuyin slide actions to other keys.

## Build

This fork uses the repository's normal Morphe build. From the repository root:

```text
gradlew.bat :patches:buildAndroid
gradlew.bat generatePatchesList
```

The `.mpp` bundle is written under `patches/build/libs/`. As documented by upstream,
local Gradle builds need credentials that can read Morphe's GitHub Packages registry.
