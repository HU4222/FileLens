# FileLens 交接文件（新会话先读这个）

> 读这一份 ≈ 3 千 token；翻聊天记录 ≈ 38 万。
> 最后更新：2026-11-08 11:00

---

## 一、这是什么

Android 文件管理 App，包名 `com.hu4222.filelens`。
五个主 Tab：**空间 / 搜索 / 清理 / 传输 / 设置**。

## 二、工程与构建

```
主线代码   E:\deepseek工作区\filelens            （main 分支，当前在 demo/blur）
构建工作树 E:\deepseek工作区\filelens-main       （main 检出，用它构建主线）
构建命令   cd filelens-main && cmd /c build_android.bat
JDK        C:\Program Files\Java\jdk-17
签名       ANDROID_USER_HOME=.android-home（证书指纹 FA1B29…）★不许换
产物       filelens-main\FileLens-<版本>.apk
真机       PKR110（adb：桌面\手机\platform-tools\adb.exe，序列号 3B1F5FE9B8L7QJ0U）
```

**签名必须固定**：换 keystore → 手机覆盖安装被拒 → 要卸载重装并丢权限。

## 三、当前版本与状态

```
当前版本   1.3.7 / versionCode 19（已构建、已安装冷启动不崩；真机验收未回）
已验证版本 1.3.6 / versionCode 18（真机验过：渐变淡出 ✅ 回收站进入 ✅ 底栏五项 ✅）
最近提交   660026f chore(release): bump to 1.3.7
           54b0ff7 fix(clean): hide shizuku block unless binder is detected
           059f916 feat(clean): animate segment and sort changes
           67bd3f5 feat(ui): expand tree and bench overlays from tapped card
           633e064 fix(ui): draw outlined gear icon for settings tab
```

### 1.3.7 五项落点（新会话按这里查）

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

## 四、下一步

1.3.7 五项已实现，等待用户真机验收；本开发会话到此停止，不接新任务。
新会话先按「三、1.3.7 五项落点」核对代码，再按用户新任务书继续。

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
docs\TASK-TEMPLATE.md      任务书模板
docs\FIX-1.3.2-DIAGNOSIS.md 1.3.2 九个问题的根因
docs\PHOTO-CLEAN-PLAN.md   照片清理方案（未排期）
AGENTS.md                  给开发 agent 的硬约束
```

## 八、开发协作方式

- 开发在 **DSH** 里跑，用桥通信：`node tools\bridge_api.mjs POST /api/dsh-bridge/prompt '{"sessionId":"...","text":"..."}'`
- 派任务用 `node tools\dsh_send.mjs <sessionId> <md文件> steer`
- 桥当前版本 0.6.3，会话 `session-0b986ca7-9f3c-47d7-bbf3-2c20b34ab154`
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
