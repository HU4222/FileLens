# 可复用资产：安卓模拟器验证工具链

> 来源：FileLens（1.5.2）开发过程沉淀。这套东西本身与项目无关，任何安卓项目都能直接用。
> 解决的问题：**没有 GUI 会话、真机不在手边、UI 全自绘导致常规自动化失效**时，如何稳定地跑验证。

---

## 一、这套工具解决什么

三个具体的痛点，每个都有对应工具：

| 痛点 | 工具 | 效果 |
|---|---|---|
| 起模拟器/装机/截图/切权限，每次都要手敲 adb | `emu.py` | 12 个子命令，一条命令做完一件事 |
| `uiautomator dump` 在 Canvas 自绘页拿不到文本，只能盲点坐标 | `drive.py` | 按文本找控件、按语义切页面、点完校验状态 |
| 模拟器总在错误环境下启动（读写失败、路径乱码） | 两个 `.bat` | 环境变量与编码全部固定，双击即用 |

---

## 二、`tools/emu.py`（17.6 KB，纯标准库）

### 命令契约

```
python tools/emu.py status                  查 AVD 状态/版本/权限，不启动任何东西
python tools/emu.py start [--headless]      启动并等到 sys.boot_completed=1
python tools/emu.py stop                    emu kill → reboot -p → 超时报错
python tools/emu.py launch                  强停再启 App，等 2 秒
python tools/emu.py install [apk路径]        不传就取仓库最新 FileLens-*.apk
python tools/emu.py shot <名字>              存 .tmp/emu/<名字>.png
python tools/emu.py tap <x> <y>
python tools/emu.py swipe <x1> <y1> <x2> <y2> [ms]
python tools/emu.py key <键名>
python tools/emu.py perm <allfiles|usage|notif|camera> <allow|deny>
python tools/emu.py perm reset              四项全 allow
python tools/emu.py logcat [行数]            按包名过滤
python tools/emu.py wait <秒>
```

### 移植到别的项目只改一处

所有路径/端口集中在文件顶部 `CONFIG` 区块：

```python
CONFIG = {
    "adb":      r"...\platform-tools\adb.exe",
    "emulator": r"...\emulator\emulator.exe",
    "avd_name": "spacelens_api34",
    "serial":   "emulator-5556",
    "port":     "5038",          # ANDROID_ADB_SERVER_PORT
}
```

### 三个真实行为，不是理论

1. **`emu kill` 可能返回 0 但不生效** —— 实测某 AVD 上 qemu 进程仍在。`stop` 实现了三级兜底：`emu kill` → 等 10s → `adb shell reboot -p` → 总超时 60s → 退出 1 并打真实错误。**且 `reboot -p` 之后要轮询到进程真的消失才算成功。**
2. **`perm` 不自作主张重启 App** —— 改权限后是否 relaunch 交给调用方串，工具只做一件事。
3. **`start` 必须真的等到开机** —— 只看进程存在不够，要轮询 `getprop sys.boot_completed`，超时 180s 报错。

---

## 三、`tools/drive.py`（11.7 KB）

在 `emu.py` 之上加语义层，解决"点不准"。

```
python tools/drive.py restart                强停 + 冷启动 + 等到首帧
python tools/drive.py goto <页名>             切页并断言成功，失败重试一次
python tools/drive.py assert-page <页名>      断言当前页，不是就退出码 1
python tools/drive.py wait-text <文本> [秒]   轮询 dump 直到出现该文本
python tools/drive.py tap-text <文本> [--expect-gone X | --expect-text Y]
python tools/drive.py long-press <文本> [ms]
```

### 为什么需要它

`uiautomator dump` 的 bounds 理论上和渲染一致，但实测**"报成功但实际没点中"**很常见。原因是"发出点击"和"点击生效"之间没有任何校验。

`tap-text` 的可靠契约（**这是关键设计**）：

1. 等布局稳定 —— 连续两次 dump（间隔 300ms）内容一致才动手，最多等 5s
2. 找唯一节点 —— 完全匹配优先；多个取 bounds 最大的；找不到就打印**当前页所有可点文本**（便于判断是不是走错页）
3. 点 bounds 中心
4. **点击后校验** —— 800ms 内分 4 次轮询 dump，校验 `--expect-gone` / `--expect-text`；不满足重试一次；两次都失败退出码 1

### 一个必须知道的坑

**App 若大量用全屏 Dialog 做页面，dump 可能同时包含底层 Activity 和上层 Dialog 的节点**，文本匹配会命中被盖住的那一层，坐标自然对不上。判断方法：`dumpsys window | grep "Window{"` 看有几个应用窗口，并检查 dump 里有没有重复文本节点。

---

## 四、两个启动脚本：五个坑，每个都真实踩过

`start-spacelens.bat`（手机尺寸）与 `start-tablet.bat`（平板尺寸）。**这五个坑按出现顺序排列，全部会让人误以为是"模拟器坏了"：**

### 坑 1：bat 必须是 CRLF 换行

用 LF 写的 bat，cmd 会把多行连在一起读，**字符被吞**：

```
'nly' 不是内部或外部命令      ← "ASCII-only" 的尾巴
's' 不是内部或外部命令
'erride' 不是内部或外部命令    ← "override" 的尾巴
```

症状极具误导性：看起来像编码问题，实际是换行符。修法：

```python
data = ('\r\n'.join(lines) + '\r\n').encode('ascii')
open('x.bat','wb').write(data)
```

### 坑 2：bat 里不能写中文路径

cmd 按 ANSI 读 bat 文件，UTF-8 的中文会变乱码：

```
系统找不到文件 E:\deepseek宸ヤ綔鍖篭铏氭嫙鎵嬫満\AndroidSdk\emulator\emulator.exe
```

修法：**不写死中文路径，用脚本自身位置反推**

```bat
for %%I in ("%~dp0..") do set "PROJ=%%~fI"
```

### 坑 3：必须清掉 `ANDROID_SDK_HOME`

系统里若残留 `ANDROID_SDK_HOME=C:\Android`（该目录不可写），模拟器会疯狂刷：

```
ERROR | Unexpected error while creating: C:\Android\.android\emu-last-feature-flags.protobuf
```

然后退出。**必须显式清空**，并重定向五个变量：

```bat
set "ANDROID_USER_HOME=%PROJ%\.android"
set "ANDROID_EMULATOR_HOME=%PROJ%\.android"
set "ANDROID_PREFS_ROOT=%PROJ%\.android"
set "ANDROID_SDK_HOME="
```

### 坑 4：`-gpu auto` 会让模拟器启动后立刻退出

日志里能看到识别到独显、WHPX 可用、然后进程消失。改用软件渲染稳定：

```
-gpu swiftshader_indirect -no-snapshot -no-metrics
```

### 坑 5：模拟器进程会在宿主会话结束时被回收

**这是最费时间的一个**。从工具/AI 会话里启动的模拟器，命令一结束就被系统回收——表现是"启动成功（日志有 `Boot completed in 51611 ms`），几十秒后 qemu 进程消失"。

试过且**都失败**的方式：`emu.py start`、`explorer.exe 借壳`、`Start-Process`、`Start-Process -WindowStyle Normal`、创建 .lnk 交给 explorer。

**唯一可行**：让真人双击 bat。

判断方法：`tasklist /FI "IMAGENAME eq qemu-system-x86_64.exe"` 能否持续看到进程。

---

## 五、时间线：从 5 分钟一次到 30 秒一次

| 阶段 | 单条验证耗时 | 手段 |
|---|---|---|
| 起初 | 5–8 分钟 | 手敲 adb + 截图肉眼判 |
| 加 `emu.py` | 2–3 分钟 | 命令封装 |
| 加 `drive.py` | 1–2 分钟 | 语义化导航 |
| **加 App 内 Debug 跳转入口** | **20–30 秒** | 见下节 |

---

## 六、最高杠杆的一招：让 App 自己能被驱动

三个工具都比不上这一条：**在 App 里加 Debug-only 的跳转入口和状态导出**。

```kotlin
if (BuildConfig.DEBUG) {
    intent.getStringExtra("flPage")?.let { /* space/search/clean/transfer/settings/tree/trash/crash */ }
    intent.getStringExtra("flTap")?.let { /* 按控制点 id 直接触发动作，不模拟触摸 */ }
    if (intent.getBooleanExtra("flDumpControls", false)) { /* 把所有可点区域打到 logcat */ }
    if (intent.getBooleanExtra("flDumpCounts", false)) { /* 把各模块计数打到 logcat */ }
}
```

用法：

```bash
# 直接跳页，不点任何控件
am start -n pkg/.MainActivity --es flPage clean

# 把当前页所有可点区域导出：id + 文本 + 精确矩形
am start -n pkg/.MainActivity --es flPage tree --ez flDumpControls true
logcat -d | grep FLCTL
# FLCTL screen=tabbar id=tab:空间 rect=56,2450,286,2669

# 按 id 触发动作（不模拟触摸，彻底绕开坐标）
am start -n pkg/.MainActivity --es flTap "crumb:0"

# 让 App 自己报数，不用点 UI 对比
am start -n pkg/.MainActivity --es flPage clean --ez flDumpCounts true
# DClean dupGroups=3 dupFiles=7 dupBytes=12345678
```

**为什么这招最有效**：自绘 UI 的可点区域本来就写在代码里（`hit()` 用的那批矩形）。把它导出来 → 用同一批坐标点击 → **命中必然成立**，不存在"dump 与实际渲染偏移"。

副作用：`flDumpCounts` 这类"让 App 自报数据"的入口，能把大量 UI 验证降级成日志比对，比截图可靠得多。

**安全要求**：全部包在 `BuildConfig.DEBUG` 里，Release 不许有；交付前用 `grep -rn "flTap" app/src/` 和 `unzip -p x.apk classes*.dex | strings | grep -c flTap` **两条都验**（源码没有不代表包里没有，没重新构建就会残留）。

---

## 七、给下一个项目的最小落地顺序

1. 复制 `emu.py`，只改 `CONFIG` 区块 → 立刻能起/装/截图
2. 复制两个 bat，把 `%~dp0..` 反推和五个变量照抄 → 环境问题一次解决
3. 有 WebView 的页面用 CDP 验（`curl http://127.0.0.1:<port>/json` 拿调试端口）
4. **自绘 UI 一定早做 Debug 跳转 + 控制点导出**，不要等测试卡住了才做
5. `drive.py` 只给"标准控件"的页面用，自绘页别指望它

---

## 附：文件清单

```
tools/emu.py              17,605 B   模拟器 CLI（12 子命令）
tools/drive.py            11,697 B   语义化导航（含点击后校验）
docs/EMU-VERIFY.md         5,099 B   常用命令与失败排查
start-spacelens.bat          860 B   手机尺寸启动（ASCII + CRLF）
start-tablet.bat           1,579 B   平板尺寸启动（自动 wm size/density）
```
