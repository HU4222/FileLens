# 全量回归结果（1.4.2 第一轮）

> 执行环境：模拟器 `spacelens_api34`（`emulator-5556`），`tools/emu.py` + adb/uiautomator/CDP。
> 真机不在手边，全部结果只代表模拟器。
> 本轮按任务书要求边测边写；**只跑到 R 组的 R1**，其余条目如实标 NOT RUN。

## 总结

| 结果 | 数量 |
|---|---|
| PASS | 5 |
| FAIL（未修复） | 0 |
| BLOCKED / NOT RUN | 59 |

测试过程中暴露 4 个缺陷，其中 3 个已修复并重跑通过（见下），1 个（F4 冷启动竞态）本轮未修。

## R1 删除 → 回收站 → 撤销 / 恢复 / 彻底删除

| # | 条目 | 结果 | 证据 |
|---|---|---|---|
| 1 | 在 `/sdcard/Download/FileLensTest/` 放 3 个文件并搜索到一个 | PASS | `a.txt/b.txt/c.txt`，md5 见下 |
| 2 | 长按 → 删除 → 确认 | PASS | 三条删除后回收站出现对应文件：`/sdcard/Android/media/com.hu4222.filelens/trash/<batch>/Download/FileLensTest/*.txt` |
| 3 | 5 秒内撤销 → 回原目录、内容一致 | **PASS（修复后）** | `.tmp/emu/110-undo-fast.png`；恢复后 `md5sum /sdcard/Download/FileLensTest/a.txt` = `6bfc5e3a09316d64473d2dcf3dfda8f1`（与删除前一致） |
| 4 | 再删一次 → 回收站 → 恢复 | PASS | `.tmp/emu/99-restored.png`；恢复后 md5 同上 |
| 5 | 回收站「彻底删除」 | PASS | 清理回收站后 `/sdcard/Android/media/com.hu4222.filelens/trash` 下只剩空 batch 目录，`b.txt/c.txt` 原路径不存在：`.tmp/emu/112-purged.png` |
| 6 | 原父目录改名 → 恢复 → 「另存到 Download」 | NOT RUN | 本轮时间用尽，未执行 |

### 本轮修复的缺陷

**F1 单文件删除没有撤销入口**（数据风险相关，已修）
- 现象：搜索结果长按删除后只弹 toast「已移入回收站」，底部没有「已移入回收站 · 撤销」。
- 根因：`deleteSingle()` 调 `Trash.move` 时没传 batch，也没有 `lastTrashBatch`/`showUndoBar()`；只有批量删除路径有撤销。
- 修复：`deleteSingle` 用显式 batch、记录 `lastTrashBatch`、成功后 `showUndoBar()`。

**F2 撤销条被底栏/键盘挡住，点了会切 Tab**（已修）
- 现象：撤销条（`undoBar`）位于 `bottomMargin 88dp`，正好落在底部胶囊区域内；弹出的 toast 又盖住它；搜索页键盘弹出时完全不可见。点击位置会落到 TabBar（实测点「撤销」跳到清理页）。
- 修复：`bottomMargin` 88→120dp（移到胶囊上方）；`showUndoBar/showActionUndo` 先 `bringToFront()` 并收键盘；删除成功不再弹 toast（避免与撤销条重叠）。

**F3 删除后搜索结果不刷新**（已修）
- 现象：删除 a.txt 后列表仍显示 a.txt。
- 根因：`deleteSingle` 调 `searchPage.refresh()`，它被 `lastSignature` 缓存挡住，签名没变就不重查。
- 修复：删除/批量删除后改调 `searchPage.refreshNow()`（强制重查）。

**F4 冷启动/扫描后立即搜索会「假空」**（未修，OPEN）
- 现象：安装后刚启动、或刚触发扫描时搜已知文件名返回「没有名字包含…」，等 store 加载/扫描完成后再搜就有结果。
- 根因猜测：`SearchPage` 在 `SnapshotStore.load()`/Scanner 重建 store 期间已经可查询，查询跑在未完成的 store 上，结果为空又被签名缓存记住。
- 建议：`MainActivity` 增加 store ready 状态，未就绪时搜索页显示「正在载入索引」；或 `SearchPage` 在 `snapshotLoading` 为 true 时延后查询。
- 本轮未修（需要改状态机，超出「只修回归暴露的明确缺陷」的最小范围，且 30 分钟内做不完）。

## R2–R5

| # | 条目 | 结果 |
|---|---|---|
| 7–8 | 回收站 30 天过期 | NOT RUN |
| 9 | 相册文件走系统回收站 | NOT RUN |
| 10 | 预览页删除 | NOT RUN |
| 11 | 清理页批量删除 + 撤销 | NOT RUN |

## 12–64 其余条目

全部 **NOT RUN**。本轮只完成 R1（1–6，其中 6 未跑），按任务书「跑不完就停在某一组」收尾。

## 复跑方法（下一轮直接照做）

```bat
python tools\emu.py start
python tools\emu.py install FileLens-1.4.2.apk
python tools\emu.py perm reset
python tools\emu.py launch
```

- 测试数据：`/sdcard/Download/FileLensTest/{a,b,c}.txt`，内容 `AAA-content` 等；md5 记录在案。
- 回收站真实路径：`/sdcard/Android/media/com.hu4222.filelens/trash/`
- 删除确认框需要在 3 秒内完成「长按 800ms」，否则自动关闭（`Dialogs.showDeleteConfirm` 的 3 秒超时）——下轮脚本要注意这个窗口。
- 撤销条位置：胶囊上方（`bottomMargin 120dp`），y ≈ 1990（1080×2340）。

---

# 第二轮（1.4.2，基线 0802fb7）

## A. 先修 F4（索引未就绪搜索假空）

**PASS（修复后）**

- 修复：`MainActivity` 新增 `indexLoading`（启动为 true，快照加载/扫描完成置 false）；`SearchPage.indexLoading` 回调 + `onIndexReady()`；`perform()` 在未就绪时显示「正在载入索引…」且不缓存空结果，就绪后 `onIndexReady()` 自动重跑当前查询。提交 `f322cbc`。
- 验证：`am force-stop` → `am start -W`（TotalTime 1743ms）→ 立即搜 `a.txt` → `找到 1 项`（`.tmp/emu/121-cold-search2.png`，dump `f4.xml`）。
- 限制：本轮没在模拟器上抓到「正在载入索引…」的实际画面（快照加载 < 输入延迟），该分支为代码路径确认；未再出现「没有名字包含」的错误结论。

## R2 回收站 30 天过期

**PASS**

- 31 天：把回收站条目 `trash.json` 的 `movedAt` 改为 31 天前后冷启动，`a.txt` 与 `trash.json` 均被清掉（batch 目录只剩空 `Download/FileLensTest`），原路径无文件。
- 29 天：手工构造 `20261108-test29` 条目（`movedAt` = 29 天前），冷启动后 `cat .../a29.txt` 仍返回 `KEEP29`，条目保留。
- 说明：`purgeExpired` 以 `trash.json` 的 `movedAt` 为准，不是文件系统 mtime；本轮按 meta 构造。

## R3 相册文件走系统回收站

**NOT RUN**（时间用尽，未执行；不是 BLOCKED，下轮继续）

## R4 预览页删除 + #50 无法解码

**NOT RUN**（时间用尽）

## R5 清理页批量删除 + 撤销

**FAIL（新缺陷 F5，未修）**

- 操作：清理页选中 3 项（还默认选中了其它重复项，实际 14 项）→ 删除选中 → 长按确认。
- 结果：14 项移入回收站，**只出现 toast「已移入回收站 14 个」，没有「撤销」条**；点撤销条应在的位置（y≈1990）无反应，文件未恢复。
- 根因猜测：清理页批量删除走的是 `CleanPage` → `MainActivity` 的另一条删除路径（`toast(result.message)` 分支），没有像 `executeDelete()` 那样设置 `lastTrashBatch` 并调 `showUndoBar()`；第一轮 F2 的修复只覆盖了搜索/树图的单删与 `executeDelete`。
- 修复方案：把清理页批量删除统一到 `executeDelete()`（或补 `lastTrashBatch` + `showUndoBar()` + `searchPage.refreshNow()`），并补 `md5` 前后对比。
- 风险等级：文件在回收站里可手动恢复，不是不可逆；但「撤销」入口缺失与第一轮 F1 同类，属于数据风险类，优先修。

## 本轮统计

| 结果 | 数量 |
|---|---|
| PASS | 2（A、R2） |
| FAIL | 1（R5 → F5） |
| NOT RUN | 2（R3、R4） |

新缺陷：**F5 清理页批量删除没有撤销入口**（未修）。


## 第二轮补充（收尾）

- F5 修复已提交 8e212a1：CleanPage 批量删除改用显式 batch，并通过 onBatchDeleted 回调 MainActivity 弹 5 秒撤销条。
- **未重跑验证**（本轮时间用尽），R5 仍记 FAIL（修复未验证）；下一轮第一件事：清理页选 3 项删除 → 撤销条出现 → 点撤销 → 3 项回原处。
- A 的「正在载入索引…」文案未在模拟器窗口内抓到（快照加载快于输入延迟），该分支为代码路径确认。


---

# 第三轮（1.4.2，基线 96552b5）

## A. 重跑 R5（F5 验证）→ FAIL（新缺陷 F7）

- 操作：清理页第 1 组 7 份，取消 3 份后选中 3 份（app_9/app_10/VID_10）→ 删除选中 → 长按确认。
- 结果：3 份移入回收站；这次**撤销条出现了**（已移入回收站 · 撤销，.tmp/emu/131-batch-undo.png），说明 F5 的接线生效。
- 但点撤销条（y≈1990）后文件未恢复，md5sum 两个 app 文件仍不存在（.tmp/emu/132-r5-undone.png）。
- 根因：撤销条（ottomMargin 120dp，y≈1957-2031）与清理页「删除选中（进回收站）」按钮（y 1905-2015）、底部胶囊重叠；点击落在下层控件上，不是撤销条。与第一轮 F2 同类，只是这次发生在清理页。
- 建议：撤销条改放到内容区底部之上（如 ottomMargin 180dp）或改用独立 PopupWindow/Snackbar，不要和操作按钮同层。
- 状态：FAIL，未修（改布局属 UI 结构调整，超出本轮时间）。

## B. R3 相册 / 系统回收站 → NOT RUN（时间用尽，未执行）

## C. R4 预览页删除 + #50 → NOT RUN（时间用尽，未执行）

## D. 空间页 12–19 → NOT RUN

## E. F6 冷启动 pager 竞态 → 未能复现（8 次尝试，但 8 次都停在启动画面）

- 方法：orce-stop → m start → 0.5s 后连点「搜索」「清理」→ 1.2s 后 uiautomator dump。
- 结果：8 次 dump 都是 4342 字节的启动画面（无任何页面文本），说明点击时 App 还没画出首帧，**没有真正触发到 Tab 切换**。
- 结论：本轮未能复现，也不能算「跑了 8 次有效尝试」；下轮改为等首帧（dumpsys gfxinfo 或轮询 dump 出现文本）后再连点，并保存截图逐张比对标题/内容。

## 本轮统计

| 结果 | 数量 |
|---|---|
| PASS | 0 |
| FAIL | 1（R5 → F7） |
| BLOCKED / 未跑 | 5（R3、R4、空间 12–19、F6 无效尝试） |

新缺陷：**F7 清理页撤销条与删除按钮重叠，点击落到下层控件**（未修）。

## 本轮结论

- F5 的接线是对的（撤销条能出现），但 F2 的「位置」问题在清理页再次出现；这类问题必须换层（独立窗口/更高位置），不能只靠 ringToFront。
- 下轮优先：F7 布局修复 + R5 重跑；然后 R3、R4，再进空间页。


---

# 第四轮（1.4.2，基线 be43218）

## F7 换层改造 → 已实现，**未通过实跑验收**

改动（MainActivity）：
- 新增 undoLayer（FrameLayout 浮层），内含 undoBar，isClickable=true，elevation=24dp；
- showUndoLayer()：isibility=VISIBLE → ringToFront() → placeUndoLayer()（按 	abBar 顶边动态算 ottomMargin，上移 16dp）→ 淡入 + 上移 12dp 动画 → 5 秒后 hideUndoLayer()；
- 
unPendingUndo() 统一处理点击：先隐藏再执行改名/移动的 action 或 undoDelete()；
- 各页面仍统一走 showUndoBar()/showActionUndo()，不再各自维护。

实测情况：
- 代码可编译、冷启动存活（pid 4376，无本应用 FATAL），新 APK SHA-256 D4E96D74…C577。
- **但没完成「点撤销 → 文件回来」的验收**：本轮自动化在清理页反复遇到 Tab 状态残留 + IME 弹出，删除流程多次跑到错误页面（.tmp/emu/150-layer-bar.png 实为清理页 + 键盘），无法稳定复现「删除 3 项 → 点撤销」。
- 旧构建下的反证仍在：.tmp/emu/143-after-undo.png 显示点撤销条位置弹出了「删除选中文件」确认框（18 个文件），说明当时触摸确实穿到下层；新浮层是否修好**未证实**。

状态：FAIL（换层已实现，验收未通过/未完成）。下一轮第一件事就是重新跑这条，不要在代码上继续加假设。

## R3 / R4 / 空间页 12–19 / F6 → NOT RUN

本轮时间全部耗在 F7 与自动化稳定性上，其余条目未执行。

## 本轮统计

| 结果 | 数量 |
|---|---|
| PASS | 0 |
| FAIL | 1（F7 验收未完成） |
| NOT RUN | R3、R4、空间 12–19、F6 |

## 备注

- 这轮暴露的是**测试脚本对 Tab 状态/IME 的假设不可靠**（App 启动会保留上次 Tab，	ap 2192 不一定切到预期页）。下轮先 orce-stop + 冷启动，并用 dump 确认当前页标题后再操作。


---

# 第五轮（1.4.2，基线 12326f9）

## A. tools/drive.py

新增语义化导航工具，命令：restart / goto / assert-page / wait-text / tap-text / long-text / shot。

实现要点：
- 
estart 先 orce-stop 再 m start -W，等 1.5s 首帧；
- goto 用底栏固定 x + 页面指纹断言（空间/底栏是自绘 Canvas，dump 里没有文本，不能靠标题）：
  搜索=有 EditText；清理=有「重复文件/应用占用/垃圾建议」；设置=有「回收站/崩溃日志」；传输=有「传输服务/WebView」；其余=空间；
- 	ap-text/long-text 按 dump 的 bounds 算中心；wait-text 轮询 dump；
- 失败打印当前页 + 屏幕尺寸 + 节点数。

验收序列（restart → goto 搜索 → assert → goto 清理 → assert → goto 空间 → shot）**连跑 3 遍全部通过**，输出见本轮汇报。

## B. F7 验证 / R3 / R4 / 空间页

待本轮继续执行，结果追加在本节之后。


## 第五轮 B 组结果（工具重建后）

### drive.py 验收：3/3 通过

序列 
estart → goto 搜索 → assert-page 搜索 → goto 清理 → assert-page 清理 → goto 空间 → shot 连跑 3 遍，全部退出码 0：
- 第 1 遍：restart 空间 13 节点；goto 搜索/清理/空间 均一次点击成功；shot drive-ok-1.png
- 第 2 遍：同上，drive-ok-2.png
- 第 3 遍：同上，drive-ok-3.png

### F7 验证：仍未完成（FAIL）

- 用 drive.py 能稳定切页（清理页断言通过），但点「删除选中（进回收站）」仍打不开确认框：
  - 	ap-text 从 dump 取到按钮 bounds [47,1905][1033,2015]，点击中心 (540,1960) 无反应；
  - 截图实测按钮在 y≈2066–2143，dump bounds 与渲染位置不一致（dump 抓取时列表还在异步重排）。
- 手动补点 (540,2080) 也未打开确认框；随后 long-text '长按 800ms' 找不到节点，说明对话框没出现。
- 结论：**F7 的撤销条点击验证仍未完成**；新增待查问题：清理页 dump bounds 与真实按钮位置偏差（可能是 dump 抓取时机，不是 App 缺陷）。
- 建议：	ap-text 增加「等布局稳定 + 二次 dump + 点击后校验目标是否消失/出现预期文本」，并给清理页固定 wait 1s 再取 bounds。

### R3 / R4 / 空间页 12–19

NOT RUN（本轮时间用尽）。

### 本轮统计

| 结果 | 数量 |
|---|---|
| PASS | 1（drive.py 3/3） |
| FAIL | 1（F7 验证仍未完成） |
| NOT RUN | R3、R4、空间 12–19 |
