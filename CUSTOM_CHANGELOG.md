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


## 3.11.0-enhanced.2-dev.2

### 快捷文本候选修复

- 修复中文输入时快捷码被第一候选词覆盖的问题。
- 新增原始按键缓冲：例如输入 `sjh` 时，即使第一候选词是“手机号”，按一次空格也会直接展开为配置的文本。
- 不再要求先让 `sjh` 上屏后再按第二次空格。
- 对 composing text 使用安全清理后再提交替换内容，避免候选词抢先上屏。
- 新增运行时测试覆盖中文第一候选干扰场景。
- 快捷文本设置界面与新增用户可见文案统一使用简体中文。
