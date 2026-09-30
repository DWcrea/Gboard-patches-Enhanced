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
