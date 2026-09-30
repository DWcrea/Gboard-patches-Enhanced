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


## 3.11.0-enhanced.2-dev.3

### 修复：中文候选状态下一次空格直接展开

- 重新定位了 dev.2 没有真正解决问题的原因：中文拼音/双拼的字母按键并不会稳定以 ASCII 字母事件进入后面的 input-event dispatcher，因此只在那里缓存 `sjh` 实际上仍然拿不到完整原始按键串。
- 改为在更早的 Gboard pointer-owner / SoftKeyView 阶段读取每个真实按键的 PRESS metadata，在候选生成之前记录 `s → j → h`。
- 空格到达 input-event dispatcher 时，优先按原始按键缓存匹配快捷规则。
- 中文 composing 状态下不再先清空候选再等待第二次触发，而是直接用 `setComposingText()` 替换当前组合文本并结束 composing。
- 目标行为：即使第一候选词是“手机号”，输入 `sjh` 后第一次按空格就直接得到配置的手机号。
- 删除键会同步回退原始快捷码缓存；极短时间内的重复内部事件会去重，避免出现 `ssjjhh`。

### 简体中文

- Gboard Patches 整体设置界面的中文资源统一改为简体中文。
- 中文系统语言现在直接使用简体中文资源。
- 对台湾地区常用 UI 用语进一步归一化为大陆简体用语，例如“汇入/汇出 → 导入/导出”“设定 → 设置”“辨识 → 识别”“透过 → 通过”“工具列 → 工具栏”“剪贴簿 → 剪贴板”。
- Morphe 公共 Patch 元数据暂时保留上游原始双语文案，以维持现有发布契约和自动化测试；Gboard 内部设置界面已经全面简体化。


## 3.11.0-enhanced.2-dev.4

### 快捷文本候选修复
- 中文拼音/双拼按键捕获新增 SoftKeyView 可见键帽和无障碍标签 fallback。
- 同一次 pointer 手势只记录一次物理键，并在 finish/cancel 后释放。
- 解决部分布局 PRESS metadata 不包含 ASCII 字母，导致 `sjh` 只能在第一次空格上屏后、第二次空格才展开的问题。
- 目标行为保持为：无论第一候选词是 `sjh` 还是“手机号”，第一次按空格都直接展开。

## 3.11.0-enhanced.2-dev.5

### 快捷文本稳定性与候选预览

- 修复连续添加快捷文本条目时，第二次及后续编辑可能重新打开旧规则快照并覆盖新内容的问题；编辑器现在每次打开都从 SharedPreferences 读取最新规则。
- 保存、导入、启用/停用以及“保留触发符”变更后会立即清除运行时设置缓存，新规则无需等待缓存窗口即可生效。
- 输入完整快捷码后，在 Gboard 候选区域第一位置显示对应展开文本预览；点击预览可直接插入展开文本。
- 保留原有“第一次按空格直接展开”逻辑，候选预览不会改变触发规则。
- 候选预览仅在完整快捷码精确匹配时出现，继续输入其他字符会自动隐藏，删除回到完整快捷码时重新显示。

## 3.11.0-enhanced.2-dev.6

### 回滚候选覆盖实验并恢复基础展开

- 回滚 dev.5 新增的自定义“第一候选”覆盖层；该方案会干扰原有快捷文本触发流程，导致新规则第一次按空格也可能无法展开。
- 恢复 dev.4 已验证的快捷文本运行时输入路径，优先保证 `快捷码 + 第一次空格` 能稳定展开。
- 保留 dev.5 中与候选 UI 无关的连续编辑修复：每次打开规则编辑器都从 SharedPreferences 重新读取最新规则。
- 保留设置缓存即时失效机制，新增/修改/导入规则后无需等待 1 秒缓存窗口。
- “展开文本作为第一候选”后续改为研究 Gboard 原生候选链路，不再通过额外 View 覆盖候选栏。

## 3.11.0-enhanced.2-dev.7

### 修复中文候选确认时的空格识别

- 定位到 Gboard 某些中文 composing / 候选确认路径中的空格事件使用 Android `KEYCODE_SPACE = 62`，而旧逻辑把 `selectedCode` 直接当 Unicode 字符解释，结果把 62 当成 `>`，导致快捷文本触发失败。
- `triggerText()` 现在优先把 `KEYCODE_SPACE=62` 识别为真正空格，再处理普通字符分隔符。
- 新增测试覆盖 `sjh` 第一候选为“散户”这类候选 payload 非空格、但载体键仍是 Space 的场景。
- 保持 dev.6 的运行时基线，不重新引入候选栏覆盖实验。

