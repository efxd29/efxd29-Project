#!/usr/bin/env python3
"""Adds the native macOS-style title bar to the upstream Termux source.

Run from the termux-app checkout root, after custom.patch has been applied:
    python3 ../custom/termux-custom/titlebar/apply_titlebar.py
It only adds files and one line to TermuxActivity.onCreate(); no existing feature is changed.
"""
import base64
import shutil
import sys
from pathlib import Path

here = Path(__file__).resolve().parent
root = Path.cwd()

java_dst = root / "app/src/main/java/com/termux/app/terminal/TermuxTitleBar.java"
icon_dst = root / "app/src/main/res/drawable-nodpi/ic_title_folder_home.png"
activity = root / "app/src/main/java/com/termux/app/TermuxActivity.java"

for p in (java_dst.parent, activity):
    if not p.exists():
        sys.exit(f"Missing path (run from termux-app root): {p}")

shutil.copyfile(here / "TermuxTitleBar.java", java_dst)
icon_dst.parent.mkdir(parents=True, exist_ok=True)
png = here / "ic_title_folder_home.png"
if png.exists():
    shutil.copyfile(png, icon_dst)
else:
    icon_dst.write_bytes(base64.b64decode((here / "ic_title_folder_home.png.b64").read_text()))

s = activity.read_text()
anchor = "        setTermuxTerminalViewAndClients();\n"
call = "        com.termux.app.terminal.TermuxTitleBar.install(this);\n"
if call in s:
    print("Title bar already installed, nothing to do")
else:
    if s.count(anchor) != 1:
        sys.exit("Could not find the unique setTermuxTerminalViewAndClients() call; refusing partial build")
    activity.write_text(s.replace(anchor, anchor + "\n" + call, 1))
    print("Title bar installed")
