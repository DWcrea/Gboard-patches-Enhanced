# Custom Change Log

## 3.11.0-enhanced.2 — Stable

Released after the `3.11.0-enhanced.2-dev.1` → `dev.18` development cycle and on-device verification.

### Text Expansion / 快捷文本

- Added configurable `shortcut → replacement` mappings for phone numbers, email addresses, URLs, addresses and common phrases.
- Added multi-line rule editing with `shortcut=expanded text` syntax.
- Added portable JSON import/export using the `gboard-text-expansion.v1` format.
- Supports up to 200 mappings, case-insensitive shortcut matching and optional trigger retention.
- Automatically disables expansion in password fields.
- Chinese input uses an independent raw-key buffer so matching is not controlled by the current Chinese candidate.
- Exact shortcut matches are displayed in the first candidate position; tapping the candidate inserts the expansion directly.
- Space / Enter / common punctuation expansion remains independent from the candidate preview, so the stable trigger path still works if the preview cannot be shown.
- Fixed stale settings snapshots when repeatedly adding or editing rules.
- Settings cache is invalidated immediately after save/import/toggle changes.

### Text Expansion stability fixes

- Added early SoftKeyView / pointer-owner capture for Chinese Pinyin and Shuangpin layouts where ordinary letters do not reliably reach the later dispatcher as ASCII text.
- Added duplicate physical-key suppression to prevent tokens such as `ssjjhh`.
- Fixed Backspace editing of the raw shortcut buffer.
- Diagnosed and fixed the keyCode / Unicode confusion that caused intermittent failures:
  - Backspace keyCode `67` could be interpreted as `C`.
  - `KEYCODE_D = 32` could be interpreted as a space character.
  - other Android key codes could be interpreted as control/whitespace characters.
- `eventText` is now authoritative when present; text-less events are mapped only through explicit Android `KeyEvent` constants.
- Space, Enter and Tab are handled as explicit trigger keys instead of generic integer-to-character casts.
- Added regression coverage for real device values including Backspace `67`, D `32`, A `29`, Space `62` and Enter `66`.

### First candidate preview

- Added a first-candidate visual preview shown only after an exact shortcut match.
- Clicking the preview submits the expansion immediately without adding a trigger character.
- Continuing to type hides the preview when the token no longer exactly matches.
- Preview placement/rendering failure falls back to stock Gboard and does not alter the stable Space expansion path.

### Diagnostics

- Added an in-process Text Expansion diagnostic ring buffer for devices where normal `adb logcat` is unavailable or vendor-filtered.
- Diagnostics can record SoftKey capture, raw token updates, input events, rule matching, replacement results and candidate preview state.
- Logs do not include expansion text content.
- Stable builds keep diagnostics available for troubleshooting but disabled by default.

### Backspace swipe gesture

- Swipe up on Backspace to delete all text before the cursor while preserving text after the cursor.
- Added numeric / phone keypad fallback for layouts that do not follow the normal alphabetic pointer path.
- Added undo/recovery handling around large swipe-delete operations.
- Stabilized gesture behavior after diagnosing pointer retargeting, coordinate-space mismatch and gesture-session lifetime issues.

### Chinese input improvements

- After entering a digit through Chinese slide-down input, the immediately following full-width punctuation is normalized:
  - `： → :`
  - `。 → .`
  - `． → .`
- Gboard Patches settings UI uses Simplified Chinese terminology throughout the enhanced fork.

### Documentation and release channels

- Updated README with stable version, target Gboard version, Morphe build stack, installation, Text Expansion bulk import and acknowledgements.
- Added a dedicated stable Text Expansion guide.
- Documented `main` as the stable channel and `dev` as the prerelease channel.

---

## 3.11.0-enhanced.1

Initial public stable release of this enhanced fork.

Key enhancements included the Backspace swipe-up workflow, numeric keypad compatibility work and Chinese input refinements on top of the upstream Gboard patch collection.

---

## Development history

The `3.11.0-enhanced.2` feature line was iterated through prereleases up to `3.11.0-enhanced.2-dev.18`.

Important milestones included:

- `dev.1–dev.4`: initial Text Expansion, raw-key capture and Chinese composing replacement.
- `dev.5–dev.8`: candidate experiments and rollback while preserving stable settings fixes.
- later diagnostic builds: in-app diagnostics used to compare successful and failed real-device trigger paths.
- `dev.17`: root-cause keyCode / Unicode fix; on-device trigger behavior became stable.
- `dev.18`: first-candidate preview added on top of the verified `dev.17` trigger baseline and confirmed working on-device.

For exact implementation history, see Git commits, prerelease tags and `docs/TEXT_EXPANSION.md`.
