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
