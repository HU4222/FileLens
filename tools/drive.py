#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""FileLens 语义化导航工具（依赖 tools/emu.py 的 adb/模拟器配置）。

背景：空间页/底栏是自绘 Canvas，uiautomator dump 里没有文本；搜索/清理/设置
页有原生控件。因此页面判定用「原生控件指纹」，不靠标题文本：
  搜索 = 有 EditText
  清理 = 有「重复文件/应用占用/垃圾建议」
  设置 = 有「回收站/崩溃日志」
  传输 = 有「传输服务/服务未开启」或 WebView
  空间 = 以上都不是（自绘页）

用法：
    python tools/drive.py restart
    python tools/drive.py goto 搜索
    python tools/drive.py assert-page 清理
    python tools/drive.py wait-text 找到 10
    python tools/drive.py tap-text 删除
    python tools/drive.py shot drive-ok
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ADB_CANDIDATES = [
    os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
    r"C:\Users\ZhuanZ（无密码）\Desktop\手机\platform-tools\adb.exe",
]
ADB_PORT = "5038"
SERIAL = "emulator-5556"
PKG = "com.hu4222.filelens"
ACT = "com.spacelens.app.MainActivity"
TMP = REPO / ".tmp"
SHOT_DIR = TMP / "emu"
TAB_X = {"空间": 108, "搜索": 324, "清理": 540, "传输": 756, "设置": 972}


def find_adb():
    for p in ADB_CANDIDATES:
        if p and os.path.isfile(p):
            return p
    raise SystemExit("找不到 adb，请检查 tools/drive.py 的 ADB_CANDIDATES")


ADB = find_adb()


def run(args, timeout=30, check=True):
    env = os.environ.copy()
    env["ADB_SERVER_PORT"] = ADB_PORT
    cp = subprocess.run([ADB, "-s", SERIAL] + args, capture_output=True,
                        timeout=timeout, env=env)
    if check and cp.returncode != 0:
        raise RuntimeError("adb %s 失败：%s" % (" ".join(args),
                                                cp.stderr.decode("utf-8", "replace").strip()))
    return cp.stdout.decode("utf-8", "replace")


def shell(cmd, timeout=30, check=True):
    return run(["shell", cmd], timeout=timeout, check=check)


def screen_size():
    out = shell("wm size")
    m = re.search(r"(\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2340)


class Ui:
    def __init__(self, nodes, width, height):
        self.nodes = nodes
        self.width = width
        self.height = height

    def texts(self):
        return [n["text"] for n in self.nodes if n["text"]]

    def has_text(self, s):
        return any(s in n["text"] for n in self.nodes if n["text"])

    def find(self, text, exact=False):
        out = []
        for n in self.nodes:
            t = n["text"]
            if not t:
                continue
            if (t == text) if exact else (text in t):
                out.append(n)
        return out

    def page(self):
        if any(n["cls"].endswith("EditText") for n in self.nodes):
            return "搜索"
        if self.has_text("重复文件") or self.has_text("应用占用") or self.has_text("垃圾建议"):
            return "清理"
        if self.has_text("回收站") or self.has_text("崩溃日志"):
            return "设置"
        if self.has_text("传输服务") or self.has_text("服务未开启") or \
                any("WebView" in n["cls"] for n in self.nodes):
            return "传输"
        return "空间"

    def status(self):
        return "当前页=%s 屏幕=%dx%d 节点=%d" % (
            self.page(), self.width, self.height, len(self.nodes))


def dump(retries=3):
    TMP.mkdir(parents=True, exist_ok=True)
    local = TMP / "drive_ui.xml"
    for _ in range(retries):
        shell("uiautomator dump /sdcard/drive_ui.xml", check=False)
        run(["pull", "/sdcard/drive_ui.xml", str(local)], check=False)
        if local.is_file() and local.stat().st_size > 100:
            try:
                root = ET.parse(str(local)).getroot()
                nodes = []
                for n in root.iter("node"):
                    b = n.get("bounds", "")
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
                    if not m:
                        continue
                    nodes.append({
                        "text": n.get("text", ""),
                        "cls": n.get("class", ""),
                        "clickable": n.get("clickable") == "true",
                        "x1": int(m.group(1)), "y1": int(m.group(2)),
                        "x2": int(m.group(3)), "y2": int(m.group(4)),
                    })
                w, h = screen_size()
                return Ui(nodes, w, h)
            except Exception:
                pass
        time.sleep(0.3)
    raise RuntimeError("uiautomator dump 连续失败")


def keyboard_shown():
    return "mInputShown=true" in shell("dumpsys input_method", check=False)


def hide_keyboard():
    if keyboard_shown():
        shell("input keyevent 4", check=False)
        time.sleep(0.3)


def tap(x, y):
    shell("input tap %d %d" % (x, y))


def center(n):
    return ((n["x1"] + n["x2"]) // 2, (n["y1"] + n["y2"]) // 2)


def fail(msg, ui=None):
    print("FAIL: " + msg)
    try:
        print((ui or dump()).status())
    except Exception:
        pass
    sys.exit(1)


def wait_page(page, timeout=5.0):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = dump()
        if last.page() == page:
            return last
        time.sleep(0.3)
    return last


def cmd_restart(args):
    shell("am force-stop %s" % PKG, check=False)
    time.sleep(0.2)
    shell("am start -W -n %s/%s" % (PKG, ACT), check=False)
    time.sleep(1.5)   # 等首帧；Canvas 页没有文本可轮询
    ui = dump()
    print("OK restart " + ui.status())


def cmd_goto(args):
    page = args.page
    if page not in TAB_X:
        fail("未知页名 %r（可用：%s）" % (page, "、".join(TAB_X)))
    hide_keyboard()
    ui = dump()
    if ui.page() == page:
        print("OK goto %s（已在）" % page)
        return
    y = ui.height - 148
    for attempt in (1, 2):
        tap(TAB_X[page], y)
        ui = wait_page(page, 5.0)
        if ui.page() == page:
            print("OK goto %s（第 %d 次点击）" % (page, attempt))
            return
        hide_keyboard()
    fail("goto %s 失败（两次点击后仍未切到）" % page, ui)


def cmd_assert_page(args):
    ui = dump()
    if ui.page() != args.page:
        fail("assert-page %s 不通过" % args.page, ui)
    print("OK assert-page %s" % args.page)


def cmd_wait_text(args):
    end = time.time() + args.seconds
    last = None
    while time.time() < end:
        last = dump()
        if last.find(args.text):
            print("OK wait-text %r" % args.text)
            return
        time.sleep(0.3)
    fail("wait-text %r 超时 %ss（自绘页面没有文本节点）" % (args.text, args.seconds), last)


def cmd_tap_text(args):
    hide_keyboard()
    ui = dump()
    nodes = ui.find(args.text, exact=True) or ui.find(args.text)
    if not nodes:
        fail("tap-text 找不到 %r（自绘页面没有文本节点）" % args.text, ui)
    nodes.sort(key=lambda n: (n["x2"] - n["x1"]) * (n["y2"] - n["y1"]))
    x, y = center(nodes[0])
    tap(x, y)
    print("OK tap-text %r (%d,%d)" % (args.text, x, y))


def cmd_long_text(args):
    hide_keyboard()
    ui = dump()
    nodes = ui.find(args.text, exact=True) or ui.find(args.text)
    if not nodes:
        fail("long-text 找不到 %r" % args.text, ui)
    nodes.sort(key=lambda n: (n["x2"] - n["x1"]) * (n["y2"] - n["y1"]))
    x, y = center(nodes[0])
    shell("input swipe %d %d %d %d %d" % (x, y, x, y, args.ms))
    print("OK long-text %r (%d,%d) %dms" % (args.text, x, y, args.ms))


def cmd_shot(args):
    SHOT_DIR.mkdir(parents=True, exist_ok=True)
    path = SHOT_DIR / (args.name + ".png")
    env = os.environ.copy()
    env["ADB_SERVER_PORT"] = ADB_PORT
    with open(path, "wb") as f:
        subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                       stdout=f, env=env, check=True)
    print("OK shot %s" % path)


def main():
    ap = argparse.ArgumentParser(prog="drive.py")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("restart").set_defaults(fn=cmd_restart)

    p = sub.add_parser("goto")
    p.add_argument("page")
    p.set_defaults(fn=cmd_goto)

    p = sub.add_parser("assert-page")
    p.add_argument("page")
    p.set_defaults(fn=cmd_assert_page)

    p = sub.add_parser("wait-text")
    p.add_argument("text")
    p.add_argument("seconds", nargs="?", type=float, default=10.0)
    p.set_defaults(fn=cmd_wait_text)

    p = sub.add_parser("tap-text")
    p.add_argument("text")
    p.set_defaults(fn=cmd_tap_text)

    p = sub.add_parser("long-text")
    p.add_argument("text")
    p.add_argument("ms", nargs="?", type=int, default=900)
    p.set_defaults(fn=cmd_long_text)

    p = sub.add_parser("shot")
    p.add_argument("name")
    p.set_defaults(fn=cmd_shot)

    args = ap.parse_args()
    try:
        args.fn(args)
    except SystemExit:
        raise
    except Exception as e:
        print("ERROR: %s" % e)
        sys.exit(1)


if __name__ == "__main__":
    main()
