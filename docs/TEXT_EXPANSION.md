# 快捷文本 / Text Expansion

> 稳定版：`3.11.0-enhanced.2`

快捷文本用于在 Gboard 中定义自己的“快捷码 → 展开文本”映射，适合快速输入手机号、邮箱、地址、网址和常用句子。它不依赖 Gboard 个人字典，因此对数字、字母数字混合内容和中文输入场景更友好。

## 使用示例

```text
sjh=13800138000
yx=name@example.com
dz=辽宁省大连市……
```

输入 `sjh` 后，有两种方式可以上屏：

```text
方式 1：sjh → 第一候选显示 13800138000 → 点击候选
方式 2：sjh + 空格 → 13800138000 + 空格
```

当快捷码精确命中时，展开文本会显示在候选栏第一位置。这个候选预览是独立的安全 UI 层；真正的空格展开仍由已经稳定验证的 raw token / InputConnection 路径完成，所以即使候选预览无法绘制，`快捷码 + 空格` 仍然可以工作。

## 中文输入为什么可以稳定触发

中文拼音/双拼输入时，Gboard 会很早把原始字母解释为中文 composing 状态，第一候选可能已经变成一个中文词。例如：

```text
sjh → 第一候选“散户”
```

快捷文本不会再依赖第一候选词，而是在更早的 pointer-owner / SoftKeyView 路径记录用户真正按下的字母，形成独立的 raw token：

```text
s → sj → sjh
```

当按下空格、回车或受支持的标点时，运行时优先检查 raw token 是否精确命中规则。中文 composing 状态下会使用 `setComposingText()` 替换当前 composing span，再结束 composing，从而避免先上屏中文候选。

## 稳定性修复

`3.11.0-enhanced.2` 已修复开发阶段发现的 keyCode / Unicode 混淆问题。Gboard 的 `selectedCode` 在部分路径中是 Android `KeyEvent` keyCode，不能直接当作 Unicode 字符码使用；否则会出现例如：

```text
Backspace(67) → 被错误当成字符 C
D 的 keyCode 32 → 被错误当成空格
A 等按键 → 被错误解释为控制字符
```

这些错误会污染 raw token，造成“有时能展开、有时不能”的现象。稳定版现在优先使用真实 `eventText`，无文本事件只按明确的 Android keyCode 映射处理，并对 Backspace、Space、Enter、Tab 等特殊键单独处理。

## 触发符

当前支持：

- 空格、Tab、回车
- `.` `,` `:` `;` `!` `?` `-`
- `。` `，` `：` `；` `！` `？`

快捷码匹配不区分大小写。

## 设置位置

```text
Gboard Patches
→ 键盘工具与快捷操作
→ 快捷文本 / Text Expansion
```

设置页支持：

- 启用 / 停用快捷文本
- 保留或移除触发符
- 多行编辑快捷规则
- JSON 导入
- JSON 导出
- 查看当前规则
- 清空全部规则
- 可选诊断模式（稳定版默认关闭）

多行编辑格式：

```text
快捷码=展开文本
```

展开文本支持：

```text
\n   换行
\t   Tab
\r   回车
\\   反斜杠
```

## 批量导入 / 导出

快捷文本使用可移植 JSON 格式：

```json
{
  "format": "gboard-text-expansion.v1",
  "enabled": true,
  "keepTrigger": true,
  "entries": [
    {
      "shortcut": "sjh",
      "text": "13800138000"
    },
    {
      "shortcut": "yx",
      "text": "name@example.com"
    }
  ]
}
```

最多支持 200 条规则。导入后运行时设置缓存会立即失效，新规则无需等待即可生效。

如果你已有 Gboard 个人字典、文本文件、CSV、JSON 或其他快捷短语列表，可以先转换成上述格式，再一次性导入。

## 第一候选显示

稳定版支持“精确命中快捷码时，将展开文本显示在第一候选位置”。例如：

```text
sjh=13800138000
```

输入 `sjh` 后，第一候选位置会显示 `13800138000`。点击该候选会直接提交展开文本；如果继续按空格，则仍按“保留触发符”设置执行。

当前实现是视觉候选层，不会直接修改 Gboard 内部原生 candidate model。这样可以降低对 Gboard 混淆候选链的侵入风险，并保持已经验证稳定的空格展开路径不变。

## 安全策略

- Android 标记为密码的文本或数字输入框会自动禁用快捷文本。
- 诊断日志不会记录展开文本内容。
- 候选预览失败时自动回退到 Gboard 原有候选显示，不影响输入。
- 建议不要把密码、验证码、私钥等敏感内容保存为快捷文本。

## 诊断模式

稳定版默认关闭诊断。排查问题时可在快捷文本设置中手动开启，记录内容包括：

```text
SoftKey
raw token
input event
规则是否命中
替换是否成功
候选预览状态
```

最多保留 600 条，支持查看、复制和清空。

## 兼容性

当前实现针对并实机验证于：

```text
Gboard 18.0.3.954559732-release-arm64-v8a
```

快捷文本当前复用 Long-Press Editing Shortcuts 已验证的 Gboard 18.0.3 input-event / pointer hook，因此构建时需要包含相应 Patch。迁移到新的 Gboard 版本时，应重新验证 pointer owner、SoftKeyView、input-event dispatcher、candidate layout 和 InputConnection 行为。

## English Summary

Text Expansion provides user-defined `shortcut → replacement` mappings for phone numbers, email addresses, URLs, addresses and common phrases. Exact matches are shown in the first candidate position, can be tapped directly, and can also be expanded with Space / Enter / punctuation. Chinese input uses an independent raw-key buffer, so matching does not depend on the current Chinese candidate. JSON import/export, password-field protection and optional diagnostics are included.
