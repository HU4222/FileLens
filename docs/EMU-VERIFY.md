# 模拟器验证流程（tools/emu.py）

> 手机不在手边时，用 AVD `spacelens_api34` 做「装包 → 点按 → 截图」的验证。
> 所有路径、端口写在 `tools/emu.py` 顶部 `CONFIG`，换机器只改那一块。

## 一、先决条件

- Windows，Python 3.8+（只用标准库，不需要 pip install）
- AVD `spacelens_api34` 已存在（`emulator -list-avds` 能看到）
- adb 用 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`（37.0.1）；没有时退到 `Desktop\手机\platform-tools\adb.exe`
- 模拟器固定 `E:\deepseek工作区\虚拟手机\AndroidSdk\emulator\emulator.exe`
- adb 服务端端口固定 **5038**（5037 被本机另一份 adb 占用）
- 输出与二维码都按 `emulator-5556` 这个序列号操作

## 二、三条最常用的组合

### 1）起 → 装 → 截图 → 关

```bat
python tools\emu.py start
python tools\emu.py install
python tools\emu.py launch
python tools\emu.py shot 01-home
python tools\emu.py stop
```

### 2）切权限再验一遍

```bat
python tools\emu.py perm allfiles deny
python tools\emu.py launch
python tools\emu.py shot 02-without-perm
python tools\emu.py perm allfiles allow
python tools\emu.py launch
python tools\emu.py shot 03-restored
```

> **注意：改完权限必须重新 `launch`。** `perm` 只改系统状态，不会替你重启 App；
> 切 `allfiles` 时系统可能直接把进程杀掉，不 launch 会读到旧界面或连不上。

### 3）收尾

```bat
python tools\emu.py stop
python tools\emu.py status
```

## 三、1.3.9 修复的完整验证序列（范本）

验的是「空间页权限提示跟真实权限同步 + 不压住卡片」：

```bat
python tools\emu.py start
python tools\emu.py install
python tools\emu.py perm reset
python tools\emu.py launch
python tools\emu.py shot 01-with-perm

python tools\emu.py perm allfiles deny
python tools\emu.py launch
python tools\emu.py shot 02-without-perm

python tools\emu.py perm reset
python tools\emu.py launch
python tools\emu.py shot 03-restored

python tools\emu.py stop
```

预期：

- `01-with-perm`：空间页底部**没有**黄色提示条
- `02-without-perm`：提示条「需要文件访问权限才能扫描 · 去设置」出现，且不压住「安装包 / 压缩包」
- `03-restored`：提示条消失

> 如果 `shot` 出来是纯白/纯黑，说明 App 还在首帧绘制：先
> `python tools\emu.py wait 2` 再截一次。`launch` 只固定等 2 秒，冷启动时偶发不够。

## 四、命令一览

| 命令 | 说明 |
|---|---|
| `status` | 看 AVD 是否在跑、序列号、已装版本、四项权限；**不启动任何东西** |
| `start [--headless]` | 启动 AVD 并等 `sys.boot_completed=1`（超时 180s） |
| `stop` | `emu kill` → 等 10s → `reboot -p` → 总超时 60s |
| `launch` | 强制停止再启动 App，等 2 秒 |
| `install [apk路径]` | 不传路径就装仓库根目录最新的 `FileLens-*.apk` |
| `shot <名字>` | 存到 `.tmp/emu/<名字>.png`，打印绝对路径与字节数 |
| `tap <x> <y>` | 点击 |
| `swipe <x1> <y1> <x2> <y2> [毫秒]` | 滑动，默认 300ms |
| `key <键名>` | 按键，如 `HOME`、`BACK`、`KEYCODE_ENTER` |
| `perm <项> <allow\|deny>` | 项：`allfiles` / `usage` / `notif` / `camera` |
| `perm reset` | 四项权限全部设为允许 |
| `logcat [行数]` | 按包名过滤，默认 200 行 |
| `wait <秒>` | 单纯等待，便于脚本串联 |

`perm` 是**两参数**形式：`perm allfiles deny`、`perm notif allow`。
不写 `allow/deny` 会报用法错误；`perm reset` 不接受第二个参数。

## 五、常见失败

| 现象 | 原因 / 处理 |
|---|---|
| `start` 超时 180s | 先 `emulator -list-avds` 确认 `spacelens_api34` 存在；看 `.tmp/emu/emulator.log`；AVD 残留锁时删 `avd\spacelens_api34.avd\*.lock` 再试 |
| `adb` 报端口/版本错误 | 不要用 `C:\Android\adb.exe`；脚本已固定 5038，若终端里另外起过 adb，先 `set ADB_SERVER_PORT=5038 && adb kill-server` |
| `status` 显示未运行但窗口在 | 模拟器进程可能刚起或已卡死；跑 `stop` 再 `start` |
| 切权限后界面没变 | 正常——`perm` 不重启 App；补一条 `python tools\emu.py launch` |
| `install` 失败 | 看完整 stderr；包名必须 `com.hu4222.filelens`，签名不一致时只能卸载重装 |
| `shot` 不是 PNG | adb 输出被其他进程污染；确认没有两个 adb 服务端在抢设备 |
| `start --headless` 失败 | 本机文档记录 `-no-window` 可能以 -36863 退出；有桌面会话时用带窗口模式 |

## 六、脚本约定

- 所有失败都返回非 0，并把真实错误打到 stderr（不吞异常）
- 截图目录不存在会自动创建
- `perm` 只改权限，不自动 `launch`
- 路径/端口/包名集中在 `tools/emu.py` 的 `CONFIG`

## Debug 跳转入口（仅 Debug）
m start ... --es flPage <space|search|clean|transfer|settings|tree|bench|trash|crash>，由 BuildConfig.DEBUG 保护，Release 不生效。
