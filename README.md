# FileLens

**An Android file manager that shows you where your storage actually went — and lets you undo a delete.**

No cloud, no account, no telemetry. Everything stays on the device.

> **中文**：FileLens 是一个安卓文件管理与空间清理工具。核心是「看清空间去哪了」和「删错能反悔」——不联网、不要账号、不采集数据。

---

## What is FileLens

Android tells you "184 GB used" and stops there. FileLens answers the next question: **what is using it**.

It scans the whole device and lays the result out as a **treemap** — every folder is a rectangle, its area proportional to the bytes it holds. You see the answer before you tap anything, then drill down to find the exact culprit.

The second half is about deleting. Every delete goes to an in-app recycle bin first, stays restorable for 30 days, and can be undone within five seconds. Deleting the wrong photo stops being permanent.

> **中文**：系统只告诉你用了 184GB，FileLens 回答「是什么用的」。全盘扫描后用矩形树图铺满屏幕，方块面积等于实际占用，不用点开就能看出谁最占地方，再逐层下钻定位。另一半是删除——所有删除先进应用内回收站，30 天内可恢复，删除后 5 秒内可撤销。

---

## Features

| Area | What you get |
|---|---|
| **Overview** | Used / free / scanned figures that match the system, plus a treemap of the entire storage |
| **Drill-down** | Tap a rectangle to enter a folder, breadcrumb jumps back to any level |
| **Search by name** | Type `report` — matches the file name |
| **Search by extension** | `.pdf` or `*.pdf` |
| **Search by path** | `DCIM/` returns only files under that folder |
| **Filters** | Size (`<1MB` / `1–10MB` / `>10MB` / `>100MB`) and age (7 / 30 days / older), stackable with any search |
| **Sorting** | Name, size, or modified time — switching keeps your scroll position |
| **Duplicates** | Groups files with identical content, shows how much space they waste |
| **App usage** | Space per app, sortable by total size or by cache size |
| **Cleanup suggestions** | Ready-to-clear items with sizes, select-all supported |
| **Safe delete** | Everything goes to a recycle bin first, keeping the original folder structure |
| **Undo** | A five-second undo bar appears after every delete |
| **Recycle bin** | Restore individually, delete permanently, or let entries expire after 30 days |
| **Rename / Move** | Renames refuse to overwrite; moving a folder into its own subfolder is rejected |
| **New folder** | Create a directory in the current location |
| **LAN transfer** | Serve files to any browser on the same Wi-Fi, no client to install |
| **Preview** | Images and WebP; undecodable files report the reason and the full path instead of going black |
| **Speed test** | Sequential and 4K random read/write |
| **Adaptive layout** | Bottom capsule bar on phones, side rail plus detail pane on tablets |

> **中文**：功能分为空间（总览 + 树图 + 下钻 + 测速）、搜索（名称／后缀／路径 + 大小与时间筛选 + 三种排序）、清理（重复文件／应用占用／垃圾建议）、删除（回收站 + 5 秒撤销 + 30 天自动过期）、文件操作（重命名／移动／新建文件夹）、局域网传输、预览；界面在手机上用底部胶囊导航，平板上自动换成左侧栏 + 右侧详情栏。

---

## Why deletes are different here

Most file managers delete immediately. FileLens does four things instead:

1. **Moves instead of deleting.** The file goes into an in-app recycle bin, preserving its folder structure — a file deleted from `Download/Photos/` stays under `Download/Photos/` inside the bin.
2. **Undo within 5 seconds.** A bar appears at the bottom; one tap puts it back.
3. **Restorable for 30 days.** Restore any entry, or delete it permanently to actually free space.
4. **Rebuilds missing folders.** If the original folder was renamed or removed while the file sat in the bin, restoring it recreates the folder instead of failing — you never "restore" into a path that no longer exists.

Photos in the gallery go through the system delete flow, so other apps see a consistent state.

> **中文**：删除不是销毁，而是移动，并保留原来的目录结构；删完 5 秒内可撤销；30 天内可恢复或彻底删除；如果恢复时原文件夹已经没了，会自动把文件夹建回来再放文件，不会让你恢复到一个不存在的地方。相册照片走系统删除流程，和其它应用保持一致。

---

## LAN transfer

The phone opens a small HTTP service; any browser on the same Wi-Fi can connect. Nothing leaves the local network.

1. Open the **Transfer** tab and start the service (it is **off by default**)
2. The page shows a LAN address, a QR code, and a 6-digit pairing code
3. On the PC: open the address, enter the 6 digits
4. Browse, download, upload

Details worth knowing:

- The 6-digit code is never sent in plaintext — pairing uses a challenge-response handshake
- The download button is hidden when you browse the phone from the phone itself
- **The receive folder is configurable**, and is probed for writability before it is accepted; if it becomes unavailable the app falls back to the default folder

> **中文**：传输服务默认关闭，开启后页面上有局域网地址、二维码和 6 位配对码；电脑浏览器打开地址输入配对码即可浏览、下载、上传。配对码不在网络上明文传输（挑战-应答）；手机浏览自己时隐藏下载按钮；接收目录可自定义，选之前先探测可写性，失效时自动回退默认目录。

---

## Permissions

| Permission | Used for | Required |
|---|---|---|
| All files access (`MANAGE_EXTERNAL_STORAGE`) | Scanning, storage stats, file management | **Yes** |
| Usage access | Per-app storage stats | Optional |
| Notifications | Transfer service status | Optional |
| Camera | QR pairing with a PC | Optional |

The app has no internet permission for uploading anything. The only network activity is the LAN transfer service.

> **中文**：必需「所有文件访问」权限，其余三项（使用情况、通知、相机）都可选。应用不会上传任何数据，唯一的网络行为是局域网传输服务。

---

## Build from source

Requirements: JDK 17, Android SDK (compileSdk 34, build-tools 34.0.0+), Gradle 8.7.

```bash
# 1. Point the build at your SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. Build
./gradlew assembleDebug

# 3. Output
# app/build/outputs/apk/debug/app-debug.apk
```

After installing, grant **All files access** manually — without it, scanning and deleting do not work.

> **中文**：需要 JDK 17、Android SDK（compileSdk 34）、Gradle 8.7。创建 `local.properties` 指向本机 SDK，`./gradlew assembleDebug` 即可。装完必须手动授予「所有文件访问」，否则扫描和删除都不可用。

---

## Repository layout

```
app/src/main/java/com/spacelens/app/
├── core/            Scanning, the node index, duplicate detection, trash, snapshots, file ops
├── ui/              All screens (drawn directly on Canvas)
├── debug/           Debug-build test hooks (see below)
├── HttpServer.kt    LAN transfer service
└── MainActivity.kt  Screen wiring and orchestration

app/src/main/assets/www/   Browser-side UI for the transfer page
docs/                      Decisions, regression records, verification tooling
tools/                     Emulator and automation scripts
```

**Code comments and commit messages are in Chinese** — this is a single-developer project and Chinese is the working language. The README is in English for GitHub readers.

> **中文**：`core/` 是扫描、索引、去重、回收站、快照与文件操作；`ui/` 是全部界面（Canvas 自绘）；`assets/www/` 是传输页的网页端。**代码注释与提交信息用中文**，README 用英文，方便 GitHub 上的阅读者。

---

## Debug-only test hooks

The UI is drawn on Canvas, so standard UI automation cannot see it. The build contains intent extras that let a test harness drive the app directly — `flPage`, `flTap`, `flDumpControls`, `flDumpCounts` — all guarded by `BuildConfig.DEBUG` and absent from a release build.

```bash
adb shell am start -n com.hu4222.filelens/com.spacelens.app.MainActivity --es flPage clean
adb shell am start -n com.hu4222.filelens/com.spacelens.app.MainActivity --ez flDumpControls true
```

See [`docs/EMU-VERIFY.md`](docs/EMU-VERIFY.md) and [`docs/ASSET-EMU-TOOLCHAIN.md`](docs/ASSET-EMU-TOOLCHAIN.md).

> **中文**：界面是 Canvas 自绘，常规 UI 自动化看不到控件，所以 Debug 构建里带了 `flPage` / `flTap` / `flDumpControls` / `flDumpCounts` 四个入口，供测试直接跳页、按 id 触发动作、导出控件位置、导出各模块计数。全部包在 `BuildConfig.DEBUG` 内，Release 不存在。

---

## Documentation

| Document | Language | What's inside |
|---|---|---|
| [`FEATURES.md`](FEATURES.md) | 中文 | Feature-only overview, no implementation details |
| [`docs/PROJECT-BRIEF.md`](docs/PROJECT-BRIEF.md) | 中文 | Full project brief written for external review |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | 中文 | 8 ADRs — what was decided, and what was rejected |
| [`docs/REGRESSION-RESULT.md`](docs/REGRESSION-RESULT.md) | 中文 | 60-item regression log, with evidence for each item |
| [`docs/ASSET-DEFECT-PATTERNS.md`](docs/ASSET-DEFECT-PATTERNS.md) | 中文 | 12 reusable defect patterns found during testing |
| [`docs/EMU-VERIFY.md`](docs/EMU-VERIFY.md) | 中文 | Emulator commands and failure troubleshooting |

> **中文**：`FEATURES.md` 是纯功能说明（给非技术读者）；`docs/` 下有项目说明、8 条决策记录（含否决了哪些方案）、60 项回归记录（含每项证据）、12 条可复用缺陷模式，以及模拟器验证手册。

---

## FAQ

**Why does it need full file access?**
A file manager that cannot see the whole filesystem cannot tell you what is using your storage. There is no narrower permission that supports a full-disk treemap.

**Does anything leave the device?**
No. There is no upload path, no analytics, and crash logs stay in local storage.

**Is the transfer encrypted?**
Pairing is (challenge-response, the code never crosses the wire). File transfer itself is plain HTTP on the LAN. Use it on networks you trust, or on your phone's hotspot.

**Which Android versions?**
minSdk 26 (Android 8.0). Used daily on Android 14 and Android 16.

**Is there a release APK?**
Not yet. The app is currently signed with a debug key and has no store listing; build from source.

> **中文**：需要全盘权限是因为没有更窄的权限能支撑全盘树图；不会上传任何数据，崩溃日志只在本地；配对走挑战-应答，文件传输在局域网内是明文，请在可信网络下使用；支持 Android 8.0 及以上，日常在 Android 14/16 上使用；暂未提供正式签名的发布包，请从源码构建。

---

## License

MIT — see [`LICENSE`](LICENSE).

> **中文**：MIT 许可证，详见 `LICENSE`。
