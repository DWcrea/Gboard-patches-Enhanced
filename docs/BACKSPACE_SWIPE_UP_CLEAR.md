# Backspace swipe-up — V8 refinement

V8 builds on the stable V7 motion/session fix and changes the user-facing behavior in three ways.

## 1. Delete only before the cursor

Swipe-up on Backspace no longer clears the entire editor. It deletes only the text before the
current cursor position and preserves all text after the cursor.

Example:

`abc|DEF` -> swipe up Backspace -> `|DEF`

If a selection exists, V8 uses the left edge of the selection as the deletion boundary so the
selected text and suffix are preserved.

The runtime first requests a full `ExtractedText` snapshot and replaces `[0, cursor)` with an
empty string. Editors that do not provide a full snapshot fall back to bounded
`deleteSurroundingText(...)` chunks before the cursor.

## 2. Numeric / phone keypad Backspace fallback

Some Gboard number/phone layouts do not follow the same pointer-motion path as the normal
alphabetic keyboard. V8 therefore keeps V7's direct MotionEvent interception and adds two
layout-independent fallbacks:

- learn the canonical Backspace PRESS carrier code from `key_pos_del` and recognize other bound
  delete views by the same carrier code;
- install a private `SLIDE_UP` action on recognized Backspace metadata so number/phone layouts
  that use the normal SoftKey action dispatcher can still trigger the same delete-before-cursor
  operation.

Tap, long-press repeat delete, and left-swipe stock gesture-delete remain unchanged.

## 3. Half-width `:` / `.` immediately after a slide-down digit

After a `SLIDE_DOWN` gesture commits a digit (`0`-`9`), the next committed colon or period is
forced to ASCII half-width:

- `：` or `:` -> `:`
- `。`, `．`, or `.` -> `.`

This is a one-shot rule. Any other committed key cancels the armed state, and the state also
expires after 5 seconds. Non-commit lifecycle events do not cancel it.
