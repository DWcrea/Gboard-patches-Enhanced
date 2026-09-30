# 快捷文本 / Text Expansion

> 首次加入版本：`3.11.0-enhanced.2-dev.2`

## 用途

快捷文本用于绕过 Gboard 个人字典在部分中文输入、数字内容和混合字母数字条目上的候选限制。它直接在补丁运行时检查光标前文本，因此可用于电话号码、邮箱、URL、地址和常用句子。

示例：

```text
sjh=13800138000
yx=name@example.com
dz=辽宁省大连市……
```

输入 `sjh` 后按空格：

```text
sjh␠  →  13800138000␠
```

即使中文输入法当前第一候选词显示为“手机号”，也会按你实际输入的原始字母串 `sjh` 匹配快捷规则，因此只需按 **一次空格**，不会先上屏“手机号”，也不需要第二次空格。

如果关闭“保留触发符”，结果则为：

```text
sjh␠  →  13800138000
```

## 支持的触发符

当前版本在按下以下字符时检查是否需要展开：

- 空格、Tab、回车等空白字符
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

- 启用/停用
- 是否保留触发符
- 多行编辑快捷规则
- JSON 导入
- JSON 导出
- 清空全部规则

多行编辑格式为：

```text
快捷码=展开文本
```

展开文本支持 `\n`、`\t`、`\r` 和 `\\` 转义。

## 导入导出格式

JSON 格式标识：

```json
{
  "format": "gboard-text-expansion.v1",
  "enabled": true,
  "keepTrigger": true,
  "entries": [
    {
      "shortcut": "sjh",
      "text": "13800138000"
    }
  ]
}
```

## 安全策略

为避免在敏感输入框里意外展开电话号码、邮箱等私人内容，本功能会自动跳过 Android 标记为密码的文本/数字输入框。

运行时现在有两条匹配路径：普通英文输入优先检查 `InputConnection.getTextBeforeCursor()` 中已经可见的快捷码；中文输入则额外记录真实按下的字母/数字序列。触发空格或标点时，如果原始按键串命中规则，会先安全清理当前 composing text，再直接提交展开内容，从而绕过第一候选词。

## 当前实现限制

本测试版复用了 Long-Press Editing Shortcuts 已验证的 Gboard 18.0.3 input-event hook，因此构建时需要包含 **Long-Press Editing Shortcuts** Patch。快捷文本拥有独立开关和独立规则，但暂时不是 Morphe 中单独可取消选择的公开 Patch。

这是有意的第一版设计：避免同时对同一个 Gboard 混淆 input-event dispatcher 安装两个相互竞争的 bytecode hook。实机确认稳定后，可以再把共享 hook 重构为公共 dispatcher，使两个功能在 Patch 选择层完全独立。

## English

Text Expansion provides user-defined `shortcut → replacement` mappings for mixed alphanumeric text such as phone numbers and email addresses. Expansion is triggered by Space, Enter or common punctuation, supports JSON import/export, and is disabled in password fields. This development version shares the already verified Gboard 18.0.3 input-event hook with Long-Press Editing Shortcuts.
