# Custom Change Log

## Enhanced fork - 2026-09-30

### Added

- Backspace swipe-up gesture to delete all text before the cursor while preserving text after it.
- Backspace swipe-up support fallback for numeric / phone keypad layouts.
- Chinese swipe-down digit punctuation normalization: `： -> :`, `。 -> .`, and `． -> .` for the immediately following punctuation.

### Gesture debugging history

- Early versions attempted to use key metadata / `SLIDE_UP` dispatch directly.
- Diagnostic logging showed pointer retargeting and coordinate-space mismatch.
- Stable behavior was reached by fixing the gesture baseline and resetting gesture-session state correctly.


## 3.11.0-enhanced.2-dev.1

### Text Expansion / 快捷文本

- Added configurable shortcut expansion for phone numbers, email addresses, URLs, addresses and common phrases.
- Trigger expansion with space, Enter or common punctuation.
- Added a bilingual settings screen under Keyboard Tools & Shortcuts.
- Added JSON import/export using the portable `gboard-text-expansion.v1` format.
- Added a simple multi-line editor using `shortcut=expanded text` syntax.
- Supports up to 200 mappings, case-insensitive matching and optional delimiter retention.
- Automatically disables expansion in password fields.
- Reuses the already verified Gboard 18.0.3 input-event hook used by Long-Press Editing Shortcuts, avoiding a second competing patch on the same obfuscated dispatcher.
