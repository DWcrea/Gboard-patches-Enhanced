# Backspace swipe-up clear — v2 fix

This revision fixes the first implementation after real-device testing showed that swiping
up from Backspace still behaved like stock repeated deletion.

Key fixes:

1. The Backspace `SLIDE_UP` action is now replaced unconditionally with the private
   clear-all action instead of only being added when `SLIDE_UP` was absent.
2. Synthetic-event recognition no longer depends on runtime object identity for the patched
   metadata, because Gboard may clone/rebuild key metadata between binding and dispatch.
3. Clear-all now prefers `ExtractedText` + explicit full-range `setSelection(...)` +
   `commitText("", 1)`, with Android Select-All as a fallback.

Normal Backspace tap and stock left-swipe gesture-delete are left untouched because only
the `SLIDE_UP` action is replaced.
