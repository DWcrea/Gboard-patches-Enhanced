<h1 align="center">Gboard Patches Enhanced</h1>

<p align="center">
  <strong>面向 Gboard 18.0.3 的增强型 Morphe 补丁集</strong><br>
  Enhanced Morphe patch set for Gboard 18.0.3
</p>

<p align="center">
  在上游 <code>Gboard-patches</code> 的基础上，加入删除键上滑、数字键盘适配与中文输入优化，并保留原项目的完整补丁功能。
</p>

<p align="center">
  <a href="https://github.com/DWcrea/Gboard-patches-Enhanced/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/DWcrea/Gboard-patches-Enhanced?display_name=tag&label=Release&style=for-the-badge"></a>
  <a href="https://github.com/DWcrea/Gboard-patches-Enhanced/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/DWcrea/Gboard-patches-Enhanced/total?label=Downloads&style=for-the-badge"></a>
  <a href="https://morphe.software/add-source?github=DWcrea/Gboard-patches-Enhanced"><img alt="Add to Morphe" src="https://img.shields.io/badge/Morphe-Add%20Source-00A8FF?style=for-the-badge"></a>
  <a href="https://github.com/DWcrea/Gboard-patches-Enhanced"><img alt="GitHub stars" src="https://img.shields.io/github/stars/DWcrea/Gboard-patches-Enhanced?style=social"></a>
</p>

---

## 项目简介 / Overview

**Gboard Patches Enhanced** 是一个公开的 Gboard 补丁项目，基于 [jasonwu1994/Gboard-patches](https://github.com/jasonwu1994/Gboard-patches) 二次开发，并使用 **Morphe** 构建和应用补丁。

本项目的目标不是重新实现 Gboard，而是在指定 Gboard 版本上，通过补丁方式增强现有功能与输入体验。当前增强版重点围绕删除键手势、数字键盘兼容和中文输入细节进行优化，同时完整保留上游项目提供的剪贴板、主题、OCR、语音输入、工具栏、注音输入等补丁。

> **English:** This repository is a public derivative of the upstream Gboard Patches project. It keeps the upstream patch set and adds practical input improvements, especially Backspace swipe gestures, numeric keypad compatibility, and Chinese punctuation normalization.

### 当前增强功能 / Enhanced features

1. **删除键上滑：删除光标前全部内容**  
   在普通键盘中，从删除键向上滑动，可一次删除光标前的所有文本，同时保留光标后的内容。

   示例：

   ```text
   abc|DEF  →  上滑删除键  →  |DEF
   ```

2. **数字键盘 / 电话键盘支持删除键上滑**  
   对部分不走普通字母键盘 MotionEvent 路径的数字/电话布局增加兼容 fallback，使同样的删除手势可用。

3. **中文下滑数字后的半角标点优化**  
   中文输入状态下，通过下滑输入数字后，如果紧接着输入全角 `：` 或 `。`，会自动转换为半角 `:` 或 `.`。

   ```text
   1： → 1:
   3。 → 3.
   ```

4. **快捷文本 / Text Expansion（测试版）**  
   可以设置“快捷码 → 展开文本”，用于快速输入电话号码、邮箱、地址、URL 或常用句子。输入快捷码后按空格、回车或常用标点即可展开；中文输入时按原始字母串匹配，不再依赖第一候选词。

   ```text
   sjh=13800138000
   yx=name@example.com
   dz=辽宁省大连市……
   ```

   设置位置：`Gboard Patches → 键盘工具与快捷操作 → 快捷文本 / Text Expansion`。  
   支持 JSON 导入/导出、最多 200 条规则、大小写不敏感匹配，并且会在密码输入框中自动停用。

详细实现与调试历史可查看：

- [V8 功能说明](docs/BACKSPACE_SWIPE_UP_CLEAR.md)
- [V2–V7 调试历史](docs/backspace-swipe-history/)
- [快捷文本说明](docs/TEXT_EXPANSION.md)
- [自定义更新记录](CUSTOM_CHANGELOG.md)

---

## 版本与兼容性 / Versions & Compatibility

| 项目 | 当前值 | 说明 |
| --- | --- | --- |
| **Gboard Patches Enhanced** | `3.11.0-enhanced.2-dev.2` | 快捷文本功能测试版 |
| **Gboard** | `18.0.3.954559732-release-arm64-v8a` | 当前明确支持的目标版本 |
| **Gboard target profile** | `18.0.3` | 版本绑定配置中的目标版本 |
| **架构 / ABI** | `arm64-v8a` | 当前目标 APK 架构 |
| **官方包名** | `com.google.android.inputmethod.latin` | Google Gboard 官方包名 |
| **默认补丁包名** | `dev.jason.com.google.android.inputmethod.latin` | 使用 Package Rename 时的默认并存包名 |
| **Morphe Gradle Plugin** | `1.3.3` | 项目构建脚本固定使用的 Morphe 补丁插件版本 |
| **Morphe 客户端** | 未在仓库中锁定最低版本 | 建议使用支持 GitHub Source 与当前 `.mpp` 格式的较新版本 |
| **Gradle** | `9.6.1` | Gradle Wrapper 固定版本 |
| **Java / JDK** | `21` | GitHub Actions 发布流程使用 Temurin 21 |
| **许可证 / License** | GPLv3 | 与上游项目保持一致 |

> [!IMPORTANT]
> 当前补丁针对 **Gboard 18.0.3.954559732-release-arm64-v8a** 进行绑定、测试和实机验证。  
> Gboard 更新后，混淆类名、方法签名或键盘事件路径可能变化，因此不要默认认为新版本可以直接兼容。

> [!NOTE]
> 这里的 `Morphe 1.3.3` 指的是 **Morphe Gradle Patch Plugin**，不是 Morphe Android 客户端版本。仓库当前没有声明客户端最低版本。

---

## 使用前须知 / Before You Start

- 本项目是 **非官方第三方项目**，与 Google、Gboard 或 Morphe 官方没有隶属或授权关系。
- 建议保留原版 Gboard APK，并从干净的目标版本重新打补丁，不要在已经修改过的 APK 上反复叠加补丁。
- 如果启用 **Package Rename**，补丁版 Gboard 可以和官方版并存，方便测试与回退。
- 某些功能依赖 Gboard 18.0.3 的内部混淆类和方法，因此跨版本兼容性不能保证。
- 修改输入法属于高频系统交互场景，建议先在非关键环境验证剪贴板、语音、密码输入框等常用功能。

## Included Patches

### Project-Built Features

Features designed and built by this project rather than simply unlocking an existing Gboard flag.

<details>
  <summary><code>Clipboard Enhancements</code></summary>

  Lets you enhance clipboard retention time, item count limits, preview lines, countdown and creation-time labels, order index, grid columns, and optionally render only the first 1,000 characters on each clipboard card.
</details>

<details>
  <summary><code>Web Clipboard</code></summary>

  Hosts a phone-powered Web Clipboard portal that lets desktop browsers sync with Gboard over the same LAN, with a pairing code gate and an optional Quick Settings Tile.

  Preview:

  <img alt="Web Clipboard pairing gate" src="docs/assets/features/web-clipboard/01-pairing-gate.png" width="720">

  <img alt="Web Clipboard conversation view" src="docs/assets/features/web-clipboard/02-conversation-view.png" width="720">

</details>

<details>
  <summary><code>Floating Web Search</code></summary>

  Open a floating web page directly from Gboard to quickly search for the information you need.
</details>

<details>
  <summary><code>Custom Theme</code></summary>

Import custom ZIP themes and beautiful themes from the official Rboard repository.
</details>

<details>
  <summary><code>FTP Server</code></summary>

  Hosts an FTP server on your phone so desktop FTP clients can browse, upload, download, and resume file transfers over the same LAN. It supports anonymous or password-protected access, a configurable control and passive port range, read-only mode, <code>/sdcard</code> or a user-selected folder as the root, live transfer progress, retained partial uploads, and an optional Quick Settings Tile.
</details>

<details>
  <summary><code>Long-Press Editing Shortcuts</code></summary>

  Add Select all, Undo, Copy, Cut, Paste, and Redo long-press shortcuts to English QWERTY and Zhuyin. This enhanced fork also supports swiping up from Backspace to delete everything before the cursor while preserving text after it, including a numeric/phone keypad fallback. The same verified Gboard 18.0.3 input-event hook is also reused by the optional Text Expansion settings feature.
</details>

<details>
  <summary><code>Toolbar Editing Buttons</code></summary>

  Add Select All, Copy, Cut and Paste to the menu. Drag them to the top toolbar and
  use them with different keyboard languages in supported editors.
</details>

<details>
  <summary><code>Swipeable Custom Top Row</code></summary>

  Lets you swipe the keyboard top row horizontally to open customizable text and JavaScript slots.
</details>

<details>
  <summary><code>Incognito Mode Toggle</code></summary>

  Add an Incognito toggle to the Access Point toolbar and configure clipboard and voice typing availability while Incognito mode is active.
</details>

<details>
  <summary><code>Custom Symbols</code></summary>

  Adds a dedicated symbols tab and a quick access entry from the comma long-press popup.
</details>

<details>
  <summary><code>Simple Calculator</code></summary>

  Adds an optional inline calculator for arithmetic expressions typed in any text field. The result appears in Gboard's suggestion row; tap it to replace the expression, or long-press it to copy the result.
</details>

<details>
  <summary><code>G Logo on Spacebar</code></summary>

  Show the G Logo on the spacebar and hide the language label.
</details>

<details>
  <summary><code>Rounded Keyboard Panel</code></summary>

  Customize which corners of the keyboard panel are rounded, and set the top and bottom radii separately.
</details>

<details>
  <summary><code>Latin Globe Key Ignore Interval</code></summary>

  Add an independent English globe key ignore interval override for post-typing language-switch delay.
</details>

<details>
  <summary><code>Emojis, stickers & GIFs Tab Order</code></summary>

Customize the bottom tab order in Gboard's Emojis, stickers & GIFs panel with drag-and-drop reordering.
</details>

<details>
  <summary><code>Backup &amp; Restore</code></summary>

  Exports all Gboard Patches settings to a portable JSON backup and restores only the modules you select, with per-module and per-key results. It also exports, compares, and restores Gboard's raw PB/XML flag-store files.
</details>


### Gboard Feature Unlocks

Features already present in Gboard that are exposed by enabling hidden settings, rollout gates, or built-in behavior.

<details>
  <summary><code>AI Writing Tools</code></summary>

  Enables the <code>Text correction &gt; Writing tools</code> setting with support for all languages.
</details>

<details>
  <summary><code>Advanced Voice Typing</code></summary>

  Enable Advanced Voice Typing with automatic punctuation, and separately enable automatic punctuation for Traditional Chinese voice typing, which does not support Advanced Voice Typing.
</details>

<details>
  <summary><code>Enable OCR / Scan Text</code></summary>

  Enable the OCR / Scan Text feature with Latin, Chinese, Japanese, Korean, and Devanagari recognition backends.
</details>

<details>
  <summary><code>English QWERTY Up-Flick Uppercase</code></summary>

  Flick up on the English QWERTY keyboard to toggle uppercase and lowercase.
</details>

<details>
  <summary><code>Enable Inline Autofill Suggestions</code></summary>

  Enables inline autofill suggestions in supported contexts.
</details>

<details>
  <summary><code>Grammar Checker</code></summary>

  Enables the <code>Text correction &gt; Grammar check</code> setting and its related rollout gate.
</details>

<details>
  <summary><code>Inline Suggestions</code></summary>

  Enables the <code>Text correction &gt; Smart Compose</code> setting and its related rollout gate.
</details>

<details>
  <summary><code>Key Shape Selection</code></summary>

  Enables the <code>Key shape</code> option inside theme details without forcing rounded keys by default.
</details>

<details>
  <summary><code>Use Bluetooth Microphone</code></summary>

  Enables the <code>Voice typing &gt; Use Bluetooth microphone</code> setting and its related rollout gate.
</details>

<details>
  <summary><code>Change emoji size</code></summary>

  Enables Gboard's emoji size setting.
</details>

<details>
  <summary><code>Enable cursor trackpad mode</code></summary>

  Enables the long-press-spacebar trackpad, cursor lock mode, and the required scrub-move preference.
</details>

<details>
  <summary><code>Enable split keyboard</code></summary>

  Enables Gboard's split keyboard layout.
</details>

<details>
  <summary><code>Enable accessibility layout</code></summary>

  Enables accessibility layout.
</details>

<details>
  <summary><code>Quick Insert</code></summary>

Enables the Quick Insert panel and toolbar access point.
</details>

<details>
  <summary><code>Hyperspeed Typing Animation</code></summary>

Shows the animation during sustained fast typing with support for all keyboards.
</details>

<details>
  <summary><code>Close Proactive Suggestions</code></summary>

Shows a dismiss button in the proactive suggestions bar.
</details>

<details>
  <summary><code>Clipboard Custom Character Limit</code></summary>

  Lets you set the maximum number of characters stored for each text clipboard item, with Gboard's stock 20,000-character limit as the default.
</details>

<details>
  <summary><code>Access Points menu style</code></summary>

  Lets you switch between the new and legacy Access Points menu styles.
</details>

<details>
  <summary><code>Top Toolbar Item Count</code></summary>

  Lets you customize the top toolbar item count.
</details>

<details>
  <summary><code>Settings Homepage Override</code></summary>

  Lets you switch between the new and legacy Gboard settings homepage styles.
</details>

<details>
  <summary><code>Developer options</code></summary>

  Enable Developer options and the Flag Editor, allowing you to modify flag values.
</details>

<details>
  <summary><code>Package Rename</code></summary>

  Renames the patched package so it can be installed alongside the official Gboard app.
</details>

### Taiwan-focused Features

Features tailored to Traditional Chinese and Zhuyin input workflows.

<details>
  <summary><code>Zhuyin Slide Input</code></summary>

  On the Zhuyin keyboard, swipe up or down to enter English letters without switching to another keyboard layout.
</details>

<details>
  <summary><code>Zhuyin Quick Traditional/Simplified Toggle</code></summary>

  Swipe up on the Zhuyin <code>ㄥ</code> key to quickly toggle between Traditional and Simplified Chinese.
</details>

<details>
  <summary><code>Zhuyin Bottom Row Key Sizes</code></summary>

  Adjusts the seven bottom-row slot sizes on the Zhuyin keyboard, including <code>?123</code>, <code>，</code>, the globe key, space, <code>ㄦ</code>, backspace, and the IME action key.
</details>

## Install

### 使用 Morphe 安装 / Install with Morphe

推荐直接把本仓库添加为 Morphe Source：

- [一键添加到 Morphe](https://morphe.software/add-source?github=DWcrea/Gboard-patches-Enhanced)
- 或在 Morphe 中手动添加：
  `https://github.com/DWcrea/Gboard-patches-Enhanced`

当前 Release：

- [v3.11.0-enhanced.1](https://github.com/DWcrea/Gboard-patches-Enhanced/releases/tag/v3.11.0-enhanced.1)
- 发布文件：`patches-3.11.0-enhanced.1.mpp`

### 推荐打补丁方式 / Recommended workflow

1. 准备目标 Gboard：`18.0.3.954559732-release-arm64-v8a`。
2. 在 Morphe 中添加本仓库 Source，或下载最新 Release 的 `.mpp`。
3. 选择需要的 Patch。
4. 建议启用 **Package Rename**，便于与官方 Gboard 并存。
5. 使用原版 APK 生成新的 patched APK。
6. 安装后进入 Gboard Patches 设置页面检查功能开关。
7. 先测试基本输入、删除、剪贴板和键盘切换，再作为日常输入法使用。

---

## Build

### 本地构建 / Local build

本项目通过 Morphe 的 GitHub Packages 分发构建插件。本地构建前，需要提供具有 `read:packages` 权限的 GitHub Token。

你可以使用以下任一方式：

**方式 A：Gradle 用户配置**

在：

```text
~/.gradle/gradle.properties
```

写入：

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PAT_WITH_PACKAGE_READ_ACCESS
```

**方式 B：环境变量**

```text
GITHUB_ACTOR
GITHUB_TOKEN
```

Windows PowerShell 示例：

```powershell
$env:GITHUB_ACTOR = "YOUR_GITHUB_USERNAME"
$env:GITHUB_TOKEN = "YOUR_GITHUB_PAT"
```

如果本地没有 Android SDK 路径配置，可在项目根目录生成：

```powershell
"sdk.dir=$($env:LOCALAPPDATA -replace '\\','/')/Android/Sdk" |
Set-Content -Encoding ASCII .\local.properties
```

构建 Android Patch Bundle：

```powershell
.\gradlew.bat :patches:buildAndroid
```

重新生成 Patch metadata：

```powershell
.\gradlew.bat generatePatchesList
```

常用完整验证：

```powershell
.\gradlew.bat :patches:test :patches:buildAndroid generatePatchesList
```

生成内容包括：

- `patches/build/libs/*.mpp`
- `patches-list.json`
- `patches-bundle.json`

### GitHub Actions 自动发布 / Automated release

仓库包含：

- `Validate`：对 `main` 自动运行测试、构建和 metadata 生成。
- `Release`：手动运行或推送 `v*` tag 时构建并发布 `.mpp`。

GitHub Actions 需要 Repository Secret：

```text
MORPHE_PACKAGES_TOKEN
```

该 Secret 应使用具有 `read:packages` 权限的 GitHub Token。

---

## 项目结构 / Repository Structure

```text
Gboard-patches-Enhanced/
├─ patches/                 # Morphe Patch 主体与补丁注册
├─ extensions/              # 注入 Gboard 的运行时逻辑
├─ docs/                    # 功能说明、截图和调试记录
├─ gradle/                  # Gradle Wrapper
├─ patches-bundle.json      # Release / Morphe Source 元数据
├─ patches-list.json        # 已发布 Patch 清单
├─ CUSTOM_CHANGELOG.md      # 本 Fork 的增强功能记录
├─ PUBLISHING.md            # 发布说明
└─ README.md
```

对于本 Fork 的 V8 增强，主要代码集中在：

- `extensions/extension/.../longpressquickactions/`
- `patches/.../features/longpressquickactions/`
- `extensions/extension/.../zhuyinslide/`

---

## 开发说明 / Development Notes

删除键上滑功能的最终实现并不是简单修改 `SLIDE_UP` metadata，而是经过实机日志定位后，在 Gboard 18.0.3 的 pointer / MotionEvent 路径进行拦截。

调试过程中确认过的关键点包括：

- Gboard 会在滑动过程中把 pointer owner 从删除键重新命中到相邻按键。
- 早期版本出现过 `Backspace → L` 的 retarget 问题。
- V6 日志最终确认了 MotionEvent 与 SoftKeyView 坐标系不一致的问题。
- V7 修复手势基准坐标与 session 生命周期后，上滑手势稳定。
- V8 在稳定手势基础上进一步改为“只删除光标前文本”，并增加数字键盘 fallback 和中文数字后的半角标点规则。

如果你准备把补丁迁移到新的 Gboard 版本，建议优先重新确认：

```text
pointer owner
MotionEvent dispatch
retarget path
gesture resolver
SoftKeyView binding
InputConnection behavior
```

而不是直接复用 18.0.3 的混淆方法名。

---

## 鸣谢 / Acknowledgements

感谢以下项目、作者与工具：

- **[jasonwu1994/Gboard-patches](https://github.com/jasonwu1994/Gboard-patches)**  
  本项目的上游来源。绝大多数基础 Patch、Morphe 项目结构、Gboard 版本绑定、运行时扩展与已有功能均来自该项目及其作者 **Jason Wu**。

- **[Morphe](https://morphe.software/)**  
  提供 Android 应用 Patch 框架、补丁格式与 Source 工作流。本项目通过 Morphe 构建和应用补丁。

- **Google Gboard**  
  本项目的目标应用。Gboard、Google 及相关商标均归其权利人所有。

- **开源社区与测试反馈**  
  感谢所有提供源码、文档、Issue、逆向思路、实机测试与问题反馈的人。V8 的删除键手势是在多轮实机测试和日志分析后逐步稳定下来的。

> 本 Fork 保留上游 LICENSE、NOTICE 和作者归属。新增修改以 GPLv3 条款继续公开。

---

## English Summary

**Gboard Patches Enhanced** is a public derivative of `jasonwu1994/Gboard-patches`, targeting **Gboard 18.0.3.954559732-release-arm64-v8a**.

Main additions in this fork:

- Swipe up on Backspace to delete all text before the cursor while preserving the suffix.
- The same Backspace gesture is supported on numeric / phone layouts through an additional fallback.
- After a Chinese slide-down digit, the immediately following full-width `：` / `。` is normalized to ASCII `:` / `.`.
- The upstream patch collection remains available.

Build stack:

- Morphe Gradle Patch Plugin: `1.3.3`
- Gradle: `9.6.1`
- Java: `21`
- License: GPLv3

For installation, add this repository as a Morphe Source or download the latest `.mpp` from GitHub Releases.

---

## License

本项目基于 **GNU General Public License v3.0 (GPLv3)** 发布，并继续遵循上游项目许可证要求。

详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

### 免责声明 / Disclaimer

本项目为非官方第三方修改项目，仅用于学习、研究与个人定制。  
本项目与 Google、Gboard、Morphe 及其维护者不存在官方隶属、合作或背书关系。

Google、Gboard、Morphe 及其他名称、商标与产品标识归各自权利人所有。
