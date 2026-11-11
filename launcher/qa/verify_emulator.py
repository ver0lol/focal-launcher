#!/usr/bin/env python3
"""Behavioral checks on a dedicated emulator only. Refuses physical devices."""
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT.parent/"work/launcher-build"
OUT=ROOT.parent/"work/legacy-emulator"
BASE=["adb","-s","emulator-5580"]
REPORT=[]

def sh(command):
    return subprocess.check_output(BASE+["shell",command],text=True,timeout=45).strip()

def record(name,detail="passed"):
    REPORT.append({"check":name,"result":detail});print(name+": "+detail,flush=True)

def tree():
    sh("uiautomator dump /data/local/tmp/rice-qa.xml")
    return ET.fromstring(sh("cat /data/local/tmp/rice-qa.xml"))

def node(root,text):
    matches=[n for n in root.iter("node") if n.get("text")==text]
    if not matches:raise AssertionError("Missing UI text: "+text)
    return matches[0]

def click(n):
    x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
    sh("input tap {} {}".format((x1+x2)//2,(y1+y2)//2))

def keys(*codes):
    sh("input keyevent "+" ".join(map(str,codes)))

def start():
    sh("am start -W -n dev.projector.rice/.MainActivity");time.sleep(.4)

def focused(root):
    return next(n for n in root.iter("node") if n.get("focused")=="true")

def shot(name):
    data=subprocess.check_output(BASE+["exec-out","screencap -p"],timeout=20)
    assert data.startswith(b"\x89PNG")
    (OUT/name).write_bytes(data)

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    assert BASE[2].startswith("emulator-")
    assert sh("getprop ro.hardware")=="ranchu"
    assert sh("getprop ro.build.version.sdk")=="30"
    assert sh("getprop sys.boot_completed")=="1"
    record("emulator_guard","Android 11, ranchu, emulator-5580, 1280x720")
    subprocess.run(BASE+["install","--no-incremental","-r",str(OUT/"Rice-0.1.0.apk")],check=True)
    sh("pm clear dev.projector.rice")
    start();home=tree();node(home,"ИЗБРАННОЕ")
    assert len([n for n in home.iter("node") if ", в избранном" in n.get("content-desc","")])==4
    record("home_and_package_discovery")
    click(node(home,"02  приложения"));apps=tree();node(apps,"Приложения")
    # Test real D-pad movement; leave touch mode and reach the first app row.
    keys(19,19,19,19,21,21,21,21,22)
    focus=focused(tree());assert focus.get("text")=="02  приложения",focus.attrib
    keys(23);node(tree(),"Приложения")
    keys(20,21,21,21,21)
    focus=focused(tree());assert focus.get("content-desc")=="Calendar, в избранном",focus.attrib
    record("dpad_focus_and_select")
    keys(82);menu=tree();click(node(menu,"Убрать из избранного"))
    sh("am force-stop dev.projector.rice");start();home=tree()
    assert not any(n.get("content-desc")=="Calendar, в избранном" for n in home.iter("node"))
    record("favorite_removal_persists_after_process_restart")
    click(node(home,"02  приложения"));apps=tree()
    calendar=next(n for n in apps.iter("node") if n.get("content-desc")=="Calendar")
    x1,y1,x2,y2=map(int,re.findall(r"\d+",calendar.get("bounds")));x=(x1+x2)//2;y=(y1+y2)//2
    sh(f"input swipe {x} {y} {x} {y} 1200")
    click(node(tree(),"В избранное"));keys(4)
    home=tree();assert any(n.get("content-desc")=="Calendar, в избранном" for n in home.iter("node"))
    record("long_press_and_favorite_addition")
    click(node(home,"настройки"));settings=tree();click(node(settings,"Wi-Fi"))
    current=sh("dumpsys activity activities | grep mResumedActivity")
    assert "com.android.settings" in current,current
    record("wifi_settings_intent")
    start();click(node(tree(),"настройки"));click(node(tree(),"Акцент: шалфей / лёд / янтарь"))
    record("theme_switch")
    sh("am force-stop dev.projector.rice");start();home=tree();node(home,"ИЗБРАННОЕ")
    gallery=next(n for n in home.iter("node") if n.get("content-desc")=="Gallery, в избранном")
    click(gallery);current=sh("dumpsys activity activities | grep mResumedActivity")
    assert "com.android.gallery3d" in current,current
    record("application_launch")
    # Reset ONLY this throwaway emulator app's preferences for a clean preview.
    sh("pm clear dev.projector.rice");start();home=tree();time.sleep(4)
    shot("Rice-preview.png")
    click(node(home,"02  приложения"));tree();shot("Rice-apps-preview.png")
    keys(4);tree()
    keys(4)
    for _ in range(20):
        current=sh("dumpsys activity activities | grep mResumedActivity")
        if "dev.projector.rice" not in current:break
        time.sleep(.2)
    assert "dev.projector.rice" not in current,current
    record("back_returns_to_existing_home")
    resolved=sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    assert "dev.projector.rice/" not in resolved,resolved
    record("home_default_not_changed",resolved.splitlines()[-1])
    crash=sh("logcat -d -t 1000 -s AndroidRuntime:E")
    assert "FATAL EXCEPTION" not in crash,crash
    record("no_runtime_crashes")
    start()
    WORK.mkdir(parents=True,exist_ok=True)
    (OUT/"Rice-verification.json").write_text(json.dumps(REPORT,indent=2,ensure_ascii=False)+"\n")

if __name__=="__main__":main()
