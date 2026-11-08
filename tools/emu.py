#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""FileLens 模拟器验证 CLI。

用法：
    python tools/emu.py status
    python tools/emu.py start [--headless]
    python tools/emu.py stop
    python tools/emu.py launch
    python tools/emu.py install [apk路径]
    python tools/emu.py shot <名字>
    python tools/emu.py tap <x> <y>
    python tools/emu.py swipe <x1> <y1> <x2> <y2> [毫秒]
    python tools/emu.py key <键名>
    python tools/emu.py perm <allfiles|usage|notif|camera> <allow|deny> | perm reset
    python tools/emu.py logcat [行数]
    python tools/emu.py wait <秒>

只依赖 Python 标准库；adb/emulator 路径与端口集中在 CONFIG，换机器只改这一块。
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------- CONFIG

CONFIG = {
    # adb 优先用 %LOCALAPPDATA% 的 SDK（本机 37.0.1）；没有再用桌面手机目录那份。
    # 不要用 C:\Android\adb.exe：2016 年的老版本，会和 37.x 服务端打架。
    "adb_candidates": [
        r"C:\Users\ZhuanZ（无密码）\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        r"C:\Users\ZhuanZ（无密码）\Desktop\手机\platform-tools\adb.exe",
    ],
    # 本机 %LOCALAPPDATA%\Android\Sdk 下没有 emulator，全机唯一可用的一份在虚拟手机目录。
    "emulator": r"E:\deepseek工作区\虚拟手机\AndroidSdk\emulator\emulator.exe",
    # AVD 与用户目录都放 E 盘虚拟手机工程里，不写 C 盘用户目录。
    "avd_home": r"E:\deepseek工作区\虚拟手机\avd",
    "android_user_home": r"E:\deepseek工作区\虚拟手机\.android",
    "sdk_root": r"E:\deepseek工作区\虚拟手机\AndroidSdk",
    # 台账分给 SpaceLens/FileLens 的机器；console 5556，adb 5557。
    "avd_name": "spacelens_api34",
    "serial": "emulator-5556",
    # 5038：本机另有一份 adb 占着 5037，两边不能共用服务端。
    "adb_port": "5038",
    "boot_timeout": 180,   # start 等开机的上限（秒）
    "stop_timeout": 60,    # stop 等进程消失的上限（秒）
    "emu_kill_wait": 10,   # emu kill 后先等这么久，不生效再 reboot -p
    # 仓库根目录（脚本所在目录的上一级），APK 与截图都按它定位。
    "repo_root": r"E:\deepseek工作区\filelens-main",
    "package": "com.hu4222.filelens",
    "activity": "com.spacelens.app.MainActivity",
    "shot_dir": r"E:\deepseek工作区\filelens-main\.tmp\emu",
    "emulator_log": r"E:\deepseek工作区\filelens-main\.tmp\emu\emulator.log",
}

# ---------------------------------------------------------------- 基础


class EmuError(RuntimeError):
    """命令行可预期的失败：打印信息并以非 0 退出。"""


def _env():
    env = os.environ.copy()
    # 所有 adb / emulator 子进程都用同一个服务端端口，否则互相看不见。
    env["ADB_SERVER_PORT"] = CONFIG["adb_port"]
    env["ANDROID_AVD_HOME"] = CONFIG["avd_home"]
    env["ANDROID_USER_HOME"] = CONFIG["android_user_home"]
    env["ANDROID_EMULATOR_HOME"] = CONFIG["android_user_home"]
    env["ANDROID_SDK_ROOT"] = CONFIG["sdk_root"]
    env["ANDROID_HOME"] = CONFIG["sdk_root"]
    env.pop("ANDROID_SDK_HOME", None)   # 系统里那个 C:\Android，别让它抢配置
    return env


def find_adb():
    for path in CONFIG["adb_candidates"]:
        if os.path.isfile(path):
            return path
    raise EmuError(
        "找不到 adb，检查 CONFIG['adb_candidates']：\n  " +
        "\n  ".join(CONFIG["adb_candidates"]))


def run(cmd, timeout=60, check=True, binary=False):
    """跑一个子进程，返回 CompletedProcess；失败时把真实错误抛出。"""
    kwargs = {
        "env": _env(),
        "stdin": subprocess.DEVNULL,
        "timeout": timeout,
    }
    if binary:
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.PIPE
    else:
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.PIPE
        kwargs["text"] = True
        kwargs["encoding"] = "utf-8"
        kwargs["errors"] = "replace"
    proc = subprocess.run(cmd, **kwargs)
    if check and proc.returncode != 0:
        err = proc.stderr
        if isinstance(err, bytes):
            err = err.decode("utf-8", "replace")
        raise EmuError(
            "命令失败（exit=%s）：%s\n%s" % (proc.returncode, " ".join(cmd), (err or "").strip()))
    return proc


def adb(*args, serial=True, timeout=60, check=True, binary=False):
    cmd = [find_adb()]
    if serial:
        cmd += ["-s", CONFIG["serial"]]
    cmd += list(args)
    return run(cmd, timeout=timeout, check=check, binary=binary)


def adb_text(*args, **kwargs):
    proc = adb(*args, **kwargs)
    return (proc.stdout or "").strip()


def qemu_running():
    """用 tasklist 看模拟器进程，不依赖 adb 是否连上。"""
    proc = run(["tasklist", "/FI", "IMAGENAME eq qemu-system-x86_64.exe", "/NH"],
               check=False)
    out = (proc.stdout or "").lower()
    return "qemu-system-x86_64" in out


def device_present():
    proc = adb("devices", serial=False, check=False)
    for line in (proc.stdout or "").splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == CONFIG["serial"] and parts[1] == "device":
            return True
    return False


def boot_completed():
    proc = adb("shell", "getprop", "sys.boot_completed", check=False)
    return (proc.stdout or "").strip() == "1"


def ensure_device():
    if not device_present():
        raise EmuError("模拟器未连接（%s）。先跑 python tools/emu.py start" % CONFIG["serial"])


# ---------------------------------------------------------------- 命令


def cmd_status(_args):
    if not qemu_running():
        print("AVD: 未运行")
        return 0

    print("AVD: 运行中")
    if not device_present():
        print("adb: 进程在，但 %s 还没就绪（开机中？）" % CONFIG["serial"])
        return 0
    print("adb 序列号: %s" % CONFIG["serial"])

    dump = adb_text("shell", "dumpsys", "package", CONFIG["package"], check=False)
    m_name = re.search(r"versionName=(\S+)", dump)
    m_code = re.search(r"versionCode=(\d+)", dump)
    if m_name and m_code:
        print("安装版本: %s (%s)" % (m_name.group(1), m_code.group(1)))
    else:
        print("安装版本: 未安装 %s" % CONFIG["package"])

    print("权限:")
    print("  allfiles: %s" % appop_state("MANAGE_EXTERNAL_STORAGE"))
    print("  usage:    %s" % appop_state("GET_USAGE_STATS"))
    print("  notif:    %s" % runtime_perm_state(dump, "android.permission.POST_NOTIFICATIONS"))
    print("  camera:   %s" % runtime_perm_state(dump, "android.permission.CAMERA"))
    return 0


def appop_state(op):
    proc = adb("shell", "appops", "get", CONFIG["package"], op, check=False)
    out = (proc.stdout or "").strip()
    if not out or "No operations" in out:
        return "default"
    m = re.search(r"%s:\s*([a-zA-Z]+)" % re.escape(op), out)
    return m.group(1) if m else out.splitlines()[0]


def runtime_perm_state(dump, perm):
    if not dump:
        return "unknown"
    m = re.search(r"%s:\s*granted=(\w+)" % re.escape(perm), dump)
    if m:
        return "granted" if m.group(1) == "true" else "denied"
    return "unknown"


def cmd_start(args):
    if qemu_running() and device_present():
        print("已在运行（%s）" % CONFIG["serial"])
        return 0
    if qemu_running():
        print("检测到模拟器进程，等待开机…")
        return _wait_boot()

    log_path = Path(CONFIG["emulator_log"])
    log_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        CONFIG["emulator"], "-avd", CONFIG["avd_name"],
        "-port", "5556", "-no-audio", "-no-boot-anim",
        "-no-snapshot", "-no-metrics", "-gpu", "swiftshader_indirect",
    ]
    if args.headless:
        cmd.append("-no-window")
    print("启动 AVD %s（日志：%s）" % (CONFIG["avd_name"], log_path))
    with open(log_path, "ab") as log:
        proc = subprocess.Popen(
            cmd, env=_env(), stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT)
    print("已拉起 emulator（pid=%s），等待开机…" % proc.pid)
    return _wait_boot()


def _wait_boot():
    deadline = time.time() + CONFIG["boot_timeout"]
    adb("start-server", serial=False, check=False, timeout=30)
    while time.time() < deadline:
        if device_present() and boot_completed():
            print("开机完成（%s）" % CONFIG["serial"])
            return 0
        time.sleep(2)
    raise EmuError("等待开机超时（%ss）。检查 %s 的末尾日志与 adb 版本。"
                   % (CONFIG["boot_timeout"], CONFIG["emulator_log"]))


def cmd_stop(_args):
    if not qemu_running():
        print("未在运行")
        return 0
    if device_present():
        adb("emu", "kill", check=False, timeout=20)
    if _wait_gone(CONFIG["emu_kill_wait"]):
        print("已停止")
        return 0

    print("emu kill 未生效，改用 reboot -p …")
    adb("shell", "reboot", "-p", check=False, timeout=20)
    if _wait_gone(CONFIG["stop_timeout"]):
        print("已停止")
        return 0
    raise EmuError("停止超时（%ss）：模拟器进程仍在。检查任务管理器里的 qemu-system-x86_64。"
                   % CONFIG["stop_timeout"])


def _wait_gone(timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not qemu_running() and not device_present():
            return True
        time.sleep(1)
    return False


def cmd_launch(_args):
    ensure_device()
    adb("shell", "am", "force-stop", CONFIG["package"], check=False)
    out = adb_text("shell", "am", "start", "-n",
                   "%s/%s" % (CONFIG["package"], CONFIG["activity"]))
    if "Error:" in out or "does not exist" in out:
        raise EmuError("启动失败：\n%s" % out)
    print(out)
    time.sleep(2)
    return 0


def cmd_install(args):
    if args.apk:
        apk = Path(args.apk).expanduser()
        if not apk.is_file():
            raise EmuError("APK 不存在：%s" % apk)
    else:
        pattern = os.path.join(CONFIG["repo_root"], "FileLens-*.apk")
        files = glob.glob(pattern)
        if not files:
            raise EmuError("仓库根目录没有 FileLens-*.apk：%s" % pattern)
        apk = Path(max(files, key=os.path.getmtime))
    ensure_device()
    print("安装：%s" % apk)
    proc = adb("install", "-r", str(apk), check=False, timeout=300)
    out = (proc.stdout or "") + (proc.stderr or "")
    print(out.strip())
    if proc.returncode != 0 or "Success" not in out:
        raise EmuError("安装失败（exit=%s）" % proc.returncode)
    # 重装会把 MANAGE_EXTERNAL_STORAGE 打回 default，删除/重命名会整片失败；装完立即授权。
    for item in PERM_ITEMS:
        _set_perm(item, "allow")
    print("已自动授权四项权限（回归前置）")
    return 0


def cmd_shot(args):
    name = re.sub(r'[\\/:*?"<>|]', "_", args.name).strip() or "shot"
    out_dir = Path(CONFIG["shot_dir"])
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / (name + ".png")
    ensure_device()
    proc = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
    data = proc.stdout or b""
    if not data.startswith(b"\x89PNG"):
        raise EmuError("截图不是 PNG（%d 字节），adb 输出异常" % len(data))
    target.write_bytes(data)
    print(str(target))
    print("%d 字节" % target.stat().st_size)
    return 0


def cmd_tap(args):
    ensure_device()
    adb("shell", "input", "tap", str(args.x), str(args.y))
    return 0


def cmd_swipe(args):
    ensure_device()
    ms = str(args.ms if args.ms is not None else 300)
    adb("shell", "input", "swipe", str(args.x1), str(args.y1),
        str(args.x2), str(args.y2), ms)
    return 0


def cmd_key(args):
    ensure_device()
    adb("shell", "input", "keyevent", args.name)
    return 0


PERM_ITEMS = {
    "allfiles": {
        "kind": "appops",
        "op": "MANAGE_EXTERNAL_STORAGE",
        "label": "所有文件访问",
    },
    "usage": {
        "kind": "appops",
        "op": "GET_USAGE_STATS",
        "label": "使用情况访问",
    },
    "notif": {
        "kind": "pm",
        "perm": "android.permission.POST_NOTIFICATIONS",
        "label": "通知",
    },
    "camera": {
        "kind": "pm",
        "perm": "android.permission.CAMERA",
        "label": "相机",
    },
}


def _set_perm(item, action):
    if item not in PERM_ITEMS:
        raise EmuError("未知权限项：%s（可选：%s / reset）"
                       % (item, " / ".join(PERM_ITEMS)))
    cfg = PERM_ITEMS[item]
    if cfg["kind"] == "appops":
        # allow / deny 都用 appops；MANAGE_EXTERNAL_STORAGE 仍需 launch 后才看到 UI 变化。
        adb("shell", "appops", "set", CONFIG["package"], cfg["op"], action)
    else:
        adb("shell", "pm", "grant" if action == "allow" else "revoke",
            CONFIG["package"], cfg["perm"])
    print("%s: %s" % (cfg["label"], action))


def cmd_perm(args):
    if args.item == "reset":
        for item in PERM_ITEMS:
            _set_perm(item, "allow")
        print("四项权限已全部设为允许（如界面未变，跑一次 launch）")
        return 0
    if not args.action:
        raise EmuError("用法：perm <allfiles|usage|notif|camera> <allow|deny>，或 perm reset")
    _set_perm(args.item, args.action)
    print("提示：切完权限要再 launch 一次，否则进程可能已被系统杀掉、读不到状态")
    return 0


def cmd_logcat(args):
    ensure_device()
    n = args.lines if args.lines is not None else 200
    out = adb_text("logcat", "-d", "-t", str(n), check=False)
    lines = [ln for ln in out.splitlines() if CONFIG["package"] in ln]
    if lines:
        print("\n".join(lines))
    else:
        print("（最近 %d 行里没有 %s 的日志）" % (n, CONFIG["package"]))
    return 0


def cmd_wait(args):
    time.sleep(args.seconds)
    return 0


# ---------------------------------------------------------------- 入口


def build_parser():
    parser = argparse.ArgumentParser(
        prog="emu.py",
        description="FileLens 模拟器验证 CLI（AVD %s，adb 端口 %s）"
                    % (CONFIG["avd_name"], CONFIG["adb_port"]),
        epilog="""例子：
  python tools/emu.py start
  python tools/emu.py install
  python tools/emu.py perm allfiles deny
  python tools/emu.py launch
  python tools/emu.py shot 02-without-perm
  python tools/emu.py perm reset
  python tools/emu.py stop""",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status", help="查看 AVD/adb/版本/四项权限（不启动任何东西）")

    p_start = sub.add_parser("start", help="启动 AVD 并等开机完成")
    p_start.add_argument("--headless", action="store_true", help="加 -no-window（本机可能不支持）")

    sub.add_parser("stop", help="停止 AVD（emu kill → reboot -p 兜底）")
    sub.add_parser("launch", help="强制停止并启动 App，等 2 秒")

    p_install = sub.add_parser("install", help="安装 APK（默认取仓库最新 FileLens-*.apk）")
    p_install.add_argument("apk", nargs="?", help="APK 路径，可省略")

    p_shot = sub.add_parser("shot", help="截图到 .tmp/emu/<名字>.png")
    p_shot.add_argument("name", help="截图名字（不含扩展名）")

    p_tap = sub.add_parser("tap", help="点击屏幕坐标")
    p_tap.add_argument("x", type=int)
    p_tap.add_argument("y", type=int)

    p_swipe = sub.add_parser("swipe", help="滑动屏幕")
    p_swipe.add_argument("x1", type=int)
    p_swipe.add_argument("y1", type=int)
    p_swipe.add_argument("x2", type=int)
    p_swipe.add_argument("y2", type=int)
    p_swipe.add_argument("ms", nargs="?", type=int, help="毫秒，默认 300")

    p_key = sub.add_parser("key", help="按键（adb keyevent 名称，如 HOME/BACK）")
    p_key.add_argument("name")

    p_perm = sub.add_parser(
        "perm",
        help="改权限：perm <allfiles|usage|notif|camera> <allow|deny>，或 perm reset")
    p_perm.add_argument("item", help="allfiles / usage / notif / camera / reset")
    p_perm.add_argument("action", nargs="?", choices=["allow", "deny"],
                        help="allow 或 deny（reset 时省略）")

    p_logcat = sub.add_parser("logcat", help="按包名过滤 logcat")
    p_logcat.add_argument("lines", nargs="?", type=int, help="行数，默认 200")

    p_wait = sub.add_parser("wait", help="等待若干秒，便于脚本串联")
    p_wait.add_argument("seconds", type=float)

    return parser


HANDLERS = {
    "status": cmd_status,
    "start": cmd_start,
    "stop": cmd_stop,
    "launch": cmd_launch,
    "install": cmd_install,
    "shot": cmd_shot,
    "tap": cmd_tap,
    "swipe": cmd_swipe,
    "key": cmd_key,
    "perm": cmd_perm,
    "logcat": cmd_logcat,
    "wait": cmd_wait,
}


def main(argv):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return HANDLERS[args.cmd](args)
    except EmuError as e:
        print("[错误] %s" % e, file=sys.stderr)
        return 1
    except subprocess.TimeoutExpired as e:
        print("[错误] 命令超时：%s" % (e.cmd,), file=sys.stderr)
        return 1
    except OSError as e:
        print("[错误] %s: %s" % (type(e).__name__, e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
