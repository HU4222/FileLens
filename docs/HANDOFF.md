# FileLens 交接文件（新会话先读这个）

> 读这一份 ≈ 3 千 token；翻聊天记录 ≈ 38 万。
> 最后更新：2026-11-08 13:40

---

## 一、这是什么

Android 文件管理 App，包名 `com.hu4222.filelens`。
五个主 Tab：**空间 / 搜索 / 清理 / 传输 / 设置**。

## 二、工程与构建

```
主线代码   E:\deepseek工作区\filelens            （main 分支，当前在 demo/blur）
构建工作树 E:\deepseek工作区\filelens-main       （main 检出，用它构建主线）
构建命令   cd filelens-main && cmd /c build_android.bat
git        C:\Users\ZhuanZ（无密码）\AppData\Local\Programs\WorkBuddy\resources\vendor\PortableGit\mingw64\bin\git.exe（不在 PATH）
JDK        C:\Program Files\Java\jdk-17
签名       ANDROID_USER_HOME=.android-home（证书指纹 FA1B29…）★不许换
产物       filelens-main\FileLens-<版本>.apk
真机       PKR110（adb：桌面\手机\platform-tools\adb.exe，序列号 3B1F5FE9B8L7QJ0U）
```

**签名必须固定**：换 keystore → 手机覆盖安装被拒 → 要卸载重装并丢权限。

## 三、当前版本与状态

```
当前版本   1.4.1 / versionCode 24（模拟器实跑：重命名/移动/新建、越界移动拦截、接收目录切换+上传+回退；
                                   真机未验）
已验证版本 1.3.7 / versionCode 19（真机验过：齿轮 ✅ Shizuku 隐藏 ✅ 树图下钻 ✅
                                   六位配对码 ✅ 局域网地址 ✅ 扫码拉起相机 ✅ 本机浏览无下载按钮 ✅）
最近提交   d5dc901 chore(release): bump to 1.4.1
           f9b5955 feat(transfer): customizable receive directory with writable probe
           facea13 feat(files): rename, move and new folder with local snapshot patch
```

### 1.4.1 落点（新会话按这里查）

**原生文件操作**

| 入口 | 支持 |
|---|---|
| 搜索结果长按 | 重命名 / 移动到… / 删除 |
| 树图列表长按 | 重命名 / 移动到… / 新建文件夹（创建在当前目录，根层不给）/ 多选 |
| 预览页「改名」 | 只改名，改完更新 `paths[index]` 与标题 |

- 校验/执行：`core/FileOps.kt`——空名、`.`/`..`、`/ \ : * ? " < > |`、trim、同名直接关、同目录已存在拒绝；移动查自身子孙/目标同名/同目录/跨卷
- 菜单与输入框：`ui/Dialogs.kt` 的 `showActions()` / `showInput()`
- 目录选择器：`ui/DirPicker.kt`（只列目录，有 NodeStore 走树、没有退回 File）
- 撤销：复用底部 5 秒条（`showActionUndo()`），重命名/移动可反向
- P5 局部刷新：`NodeStore.renameNode()/moveNode()/addDir()/findChild()/indexOfPath()`；路径按 parent 链重算，**不重扫**，页面重读同一 store

**接收目录**

- `core/RecvDir.kt`：默认 `Download/FileLens`；Prefs 存绝对路径；每次使用校验存在+可写，坏掉回默认
- `TransferService.recvRoot()` 走 `RecvDir.resolve()`
- 网页「我的」接收目录行：注入点击 → `pickRecvDir()` 桥；选完直接改 DOM 两处路径，不依赖切页
- 写探针 `.fl_probe` 只在 `RecvDir.probe()`；新增代码里唯一 `File.delete()`

1.4.1 自测（模拟器）：重命名成功、同名拒绝、含 `/` 拒绝并指出字符；移动成功（源没、目标有、搜索行目录跟着变）；
文件夹移进自己子目录被拒；新建文件夹出现在当前列表且能进入；接收目录改 DCIM 后网页立即显示新路径、
`curl` 上传文件落在 DCIM；删掉 DCIM 重启 → 回默认 + toast。
**未测：预览页改名、撤销按钮点击、跨存储卡移动、菜单多选、恢复默认按钮。真机未验。**

### 1.4.0 两处落点（新会话按这里查）

**A 搜索（`ui/SearchPage.kt`）**

| # | 改动 | 位置 |
|---|---|---|
| A1 | 三模式互斥：`*.png`/`.png` 后缀、含 `/` 走完整路径、否则名称 | `queryMode()` / `suffixOf()`，`perform()` 内匹配 |
| A2 | 大小四档 `<1MB` / `1–10MB` / `>10MB` / `>100MB`（`sizeMode` 1–4） | `buildChips()` |
| A3 | 第二行改「大小 · 相对目录」，`TruncateAt.START`；根目录显示「内部存储」 | `relativeDir()`、`HitAdapter.getView()` |
| A4 | 名称/大小/时间三选一，默认大小降序；扫描时只留最优 200 条；切排序保持列表位置 | `buildSortRow()`、`sortHits()`、`perform()` |
| A5 | 关键词 0 结果带原文 + 路径提示；纯筛选 0 结果给「清空筛选」 | `renderStatus()`、`clearFilters()` |

**B 崩溃日志**

| 文件 | 作用 |
|---|---|
| `App.kt` + `AndroidManifest.xml` | `Application` 里安装 handler；`finally` 转调原 handler |
| `core/CrashLog.kt` | `files/crash/crash-yyyyMMdd-HHmmss.txt`；同步写 + `fd.sync()`；最多 10 份，`File.delete` 逐个删 |
| `ui/CrashLogPage.kt` | 列表 / 全文 / 分享 / 删除 / 清空；返回键先退全文再关页（`handleBack()`） |
| `ui/SettingsPage.kt` | 「诊断」区，`崩溃日志 · N 份`，`onCrashLog` |
| `MainActivity.kt` | `showCrashLogPage()`：全屏 Dialog + 返回键处理，关闭后刷新份数 |

自测（模拟器 `spacelens_api34`，`tools/emu.py`）：build 通过；1.4.0/23 冷启动不崩；
`.png` 60 项全 png、`DCIM/` 走路径提示、`zzzqqq` 提示带原文、`SpaceLensTest/` 714 项；
`<1MB` 8941 项全部 <1MB、`1–10MB` 25 项全部 1–10MB；名称/大小/时间三种顺序都对；
`>100MB` 0 结果显示「当前筛选条件下没有文件 + 清空筛选」；12 次人为崩溃只留 10 份，内容含版本/机型/堆栈；
分享面板、删除单条（10→9）、清空全部（9→0）、返回键（详情→列表→设置）均通。截图在 `.tmp/emu/`。**真机未验。**

> 顺带修了一个原有问题：外层 `SwipePager` 会把所有横向拖动当切页，筛选 chip 那排原本滚不动；
> 现在在 `SearchPage` 里手指按下时 `requestDisallowInterceptTouchEvent`，不改 `SwipePager`。

### 1.3.10 新增（新会话按这里查）

| 文件 | 用途 |
|---|---|
| `tools/emu.py` | 模拟器验证 CLI（status/start/stop/launch/install/shot/tap/swipe/key/perm/logcat/wait）；路径与端口集中在顶部 `CONFIG` |
| `docs/EMU-VERIFY.md` | 使用说明、三条常用组合、1.3.9 验证范本、常见失败 |

模拟器：AVD `spacelens_api34`，序列号 `emulator-5556`，adb 端口 5038。
自测已真跑：status（停/跑）、start、install（1.3.10 / 22）、shot（1080x2340）、
`perm allfiles deny` + launch 见提示、`perm reset` + launch 提示消失、stop（emu kill 无效时走 reboot -p，17s 完成）。

### 1.3.9 两处落点（新会话按这里查）

| # | 改动 | 位置 |
|---|---|---|
| P1-1 提示状态 | 新增 `syncScanGate()`：有权限 hide、无权限 show；`onResume()` 末尾 + `selectTab()`/`onPageSettled()` 的空间页分支各调一次 | `MainActivity.kt:689-695`、`onResume()`、`selectTab()`、`onPageSettled()` |
| P1-2 提示让位 | 提示显示时给 `scroll` 加底部 `padding = 96dp + 提示高 + 12dp`；隐藏时归 0；`addOnLayoutChangeListener` 校正首次量到 0 的情况 | `ui/OverviewPage.kt` `showScanPermissionGate()` / `hideScanPermissionGate()` / `applyGatePad()` |

1.3.9 自测（仅模拟器）：build 通过；冷启动 pid 存活、crash 缓冲无本应用 FATAL；无权限进空间页提示出现，
`appops allow` 后回前台提示消失且 padding 归零。**真机未验。**

### 1.3.8 三处落点（新会话按这里查）

| # | 改动 | 位置 |
|---|---|---|
| P1-2 按钮底色 | `refresh` 背景改 `Color.argb(15,255,255,255)`，与 `qrButton` 同款 | `ui/TransferPage.kt` `refresh` 初始化 |
| P1-3 面包屑根名 | `#crumbs` 首段 `/` 注入显示「内部存储」，`data-crumb=0` 不变 | `ui/TransferPage.kt` `injectViewBridge()` 末尾 IIFE |
| P2-1 展开遮罩 | `scrim` 35% 黑，展开 150ms 淡入、收起淡出后 GONE | `ui/TransferPage.kt` `buildBalls()` / `expandBalls()` / `collapseBalls()` |

P1-1（底部被胶囊遮挡）真机实测不成立：`.views` 已有 134px 内边距 + WebView 92dp，滚到
`scrollTop + innerHeight >= scrollHeight - 2` 时最后一行在胶囊上方，未改。

1.3.8 自测：build 通过；冷启动无 FATAL；传输页能打开；页面内实测面包屑首段=「内部存储」、
悬浮球展开时内容亮度下降 35%（截图在 `.tmp/v138-*.png`）。功能验收未做。

### 1.3.7 五项落点（历史，新会话按这里查）

| # | 改在哪个文件 / 函数 |
|---|---|
| P1-1 设置齿轮 | `ui/Draw.kt` 的 `Icon.SETTINGS` 分支：外圈 + 8 短齿 + 中心空心圆，描边 |
| P1-2 树图/测速扩张 | `ui/OverviewPage.kt` 的 `Content.quickCardRectOnScreen()`、`lastQuickCardRectOnScreen()`；`MainActivity.kt` 的 `showTreeOverlay()` / `hideTreeOverlay()` / `showBenchOverlay()` / `hideBenchOverlay()` |
| P1-3 清理三段 | `ui/CleanPage.kt` 的 `selectSegment()`：18% 宽横向位移 + alpha 0.4→1，200ms |
| P1-4 应用排序 | `ui/CleanPage.kt` 的 `buildAppSection()` 内两个排序 chip 点击监听：纵向 8dp + alpha 0.5→1，160ms |
| P1-5 Shizuku | `ui/CleanPage.kt` 的 `init` 初始 GONE、`onShown()` 刷新、`refreshShizuku()` 仅 AUTHORIZED 时 VISIBLE |

1.3.7 自测：build 通过；`am start` 冷启动无 FATAL；底栏设置 Tab 能打开设置页。功能验收未做。

### 1.3.6 已验过的事实（别重复验）
- 渐变淡出：画在 `SwipePager.dispatchDraw()`，在子页面之后绘制 → z 序正确
- 回收站：设置页那一行点进去能看到空态「回收站是空的 / 保留 30 天」
- 相机权限：点了跳「应用详情页」（可手动开），不是死点击

### 已知测量陷阱（我踩过三次）
**不要靠截图估算坐标去点控件。** 必须先 `adb shell uiautomator dump` 拿真实 `bounds` 再 tap。
我因为按缩放比换算坐标，三次把「点偏了」误判成「点击失效」，白让开发改了两轮。

### 已知不一致（别当 bug 修）

- 存储权限判断有三套写法：
  - `SettingsPage.kt:190`：SDK >= 30 → `isExternalStorageManager()`，否则查 `READ_EXTERNAL_STORAGE`
  - `MainActivity.kt:680`：SDK >= 30 → `isExternalStorageManager()`，否则 `false`
  - `TransferService.kt:87`：`SDK_INT < 30 || isExternalStorageManager()`
  API 26–29 上 `TransferService` 会无条件认为有权限。主路径被 `hasReadAccess()` 兜住，minSdk 26 没出过问题；
  **不要单独改这一处**，要统一就三处一起改，否则传输服务的存储根会跟着变。

## 四、下一步

1.4.1 已完成；**暂停新功能，转全量回归测试**，等用户的回归清单逐条执行。
新会话先按「三、1.4.1 落点」核对，用 `tools\emu.py` 复验，再按回归清单填结果。

### 已定顺序（暂停，等回归后再说）
- 1.4.2 SAF 基础设施 + 自定义传输接收目录（已并入 1.4.1 的接收目录部分）
- 1.4.3 SAF 目录扫描 / 浏览 / 删除接入（依赖 1.4.2，顺序不能换）

### 更后面的（没排期，等用户定）
- 照片清理（方案在 `docs/PHOTO-CLEAN-PLAN.md`，P1 一周）
- 正式发布签名 + App 备案（要用户身份，我做不到）
- 桥的事件流对 `steer` 不报（已知 bug，靠读会话日志绕过）

## 五、红线（违反即回退）

1. **不改 `app/src/main/assets/www/`** —— 网页资源一个字节都不动，行为改动走运行时 JS 注入
2. **`deleteRecursively` 必须保持 0 处** —— 删除一律走 `Trash`
3. **不删除、不替换原有组件**，除非它功能完全不可用
4. **最小改动**：只做任务书写的；每个改动要能回答「对应哪一条」
5. **新建文件/新增组件/改 ≥3 个文件 → 先报批**

## 六、决策记录（别重提已否决的方案）

见 `docs\DECISIONS.md`。几条最容易走回头路的：

- **玻璃**：Android 没有系统级背景模糊，主线用伪玻璃（半透明+高光+噪点）。别再提 `RenderEffect` 能真模糊。
- **照片清理**：不做训练模型，做「打分 + 原型检索」。
- **大文件排行**：已删，价值低。
- **传输**：走个人版（局域网免登录），但保留配对码走挑战-应答。

## 七、文档索引

```
docs\DECISIONS.md          决策与否决理由
docs\CHANGELOG.md          版本演进
docs\EMU-VERIFY.md         模拟器验证流程（tools\emu.py）
docs\TASK-TEMPLATE.md      任务书模板
docs\FIX-1.3.2-DIAGNOSIS.md 1.3.2 九个问题的根因
docs\PHOTO-CLEAN-PLAN.md   照片清理方案（未排期）
AGENTS.md                  给开发 agent 的硬约束
```

## 八、开发协作方式

- 开发在 **DSH** 里跑，用桥通信：`node tools\bridge_api.mjs POST /api/dsh-bridge/prompt '{"sessionId":"...","text":"..."}'`
- 派任务用 `node tools\dsh_send.mjs <sessionId> <md文件> steer`
- 桥当前版本 0.6.3
- **当前开发会话 `session-097f03fd-e12f-499b-b3fa-36ef37308e8f`**（2026-11-08 11:15 起用）
- 旧会话 `session-0b986ca7-9f3c-47d7-bbf3-2c20b34ab154` **已归档**（5.48 亿 token，从侧边栏隐藏，仅作追溯，不要再往里派活）
- **桥的事件流对 steer 发起的轮次不报** → 改为读会话日志：
  `~/.dsh/sessions/--E-deepseek~5DE5~4F5C~533A--/<sid>/session.v4.jsonl.zstd`
- 思考强度切换：`tools\effort.bat high|max`（改配置，需重启 DSH 生效）

## 九、验证方式

手机连 USB 后：
```bash
ADB="C:\Users\ZhuanZ（无密码）\Desktop\手机\platform-tools\adb.exe"
"$ADB" -s 3B1F5FE9B8L7QJ0U install -r <apk>     # 签名一致才能覆盖
"$ADB" -s 3B1F5FE9B8L7QJ0U exec-out screencap -p > shot.png
"$ADB" -s 3B1F5FE9B8L7QJ0U shell input tap <x> <y>
```

**不要用 `adb install` 装演示版**（`.demo` 包名会被 InstallerX 拦截）；
主线包可以正常 `install -r`。
