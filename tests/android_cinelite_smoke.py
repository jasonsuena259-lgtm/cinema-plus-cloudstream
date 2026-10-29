#!/usr/bin/env python3
"""KodaStream/CloudStream native emulator smoke test: uses UI accessibility, not mock links.

Saves screenshots and UI dumps. Fails if the real .cs3 is not installed.
It is NOT a claim that protected CineLite media streams are playable.
"""
import os, re, subprocess, time, pathlib, xml.etree.ElementTree as ET, sys

ROOT=pathlib.Path("artifacts/android-cinelite")
ROOT.mkdir(parents=True, exist_ok=True)
REPO=os.environ.get("CINELITE_REPO", "https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/cinelite-preview/repo.json")
PKG="com.kodastream.app"
def adb(*args, timeout=45, check=True):
    proc=subprocess.run(["adb",*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout,text=True)
    if check and proc.returncode:
        raise RuntimeError("adb %s failed: %s"%(args,proc.stderr[:500]))
    return proc.stdout
def capture(name):
    with (ROOT/(name+".png")).open("wb") as f:
        subprocess.run(["adb","exec-out","screencap","-p"],stdout=f,timeout=25,check=True)
    try:
        doc=xml()
        (ROOT/(name+".xml")).write_text(ET.tostring(doc,encoding="unicode"))
    except Exception as e:
        print("Cannot dump screenshot semantics:",str(e)[:200],flush=True)
def xml():
    adb("shell","uiautomator","dump","/sdcard/ui.xml",timeout=35)
    raw=adb("exec-out","cat","/sdcard/ui.xml",timeout=15)
    return ET.fromstring(raw[raw.find("<?xml"):])
def nodes():
    root=xml()
    result=[]
    for node in root.iter("node"):
        a=node.attrib
        label=a.get("text") or a.get("content-desc") or ""
        match=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",a.get("bounds",""))
        if match:
            x1,y1,x2,y2=map(int,match.groups())
            if x2>x1 and y2>y1:
                result.append((label,((x1+x2)//2,(y1+y2)//2),a.get("class","")))
    return result
def brief():
    labels=[x[0] for x in nodes() if x[0]]
    print("SCREEN:",repr(labels[:75]),flush=True)
    return labels
def tap_label(patterns, wait=0.7):
    view=nodes()
    for regex in patterns:
        hits=[n for n in view if re.search(regex,n[0],flags=re.I)]
        if hits:
            n=hits[-1]
            adb("shell","input","tap",str(n[1][0]),str(n[1][1]))
            time.sleep(wait)
            print("TAPPED",repr(n[0]),flush=True)
            return True
    return False
def wait_for(pattern,limit=20):
    end=time.time()+limit
    while time.time()<end:
        if any(re.search(pattern,label,re.I) for label in brief()):return True
        time.sleep(2)
    return False

print("DEVICE:",adb("shell","getprop","ro.build.version.release").strip(),flush=True)
print("INSTALL CHECK:",adb("shell","pm","list","packages",PKG).strip(),flush=True)
adb("shell","am","start","-n",PKG+"/.MainActivity",timeout=30)
time.sleep(5)
capture("01-start")
for i in range(5):
    labels=brief()
    if any(re.search(r"next|continue|start|ابدأ|التالي",s,re.I) for s in labels):
        if not tap_label([r"^next$",r"^start$",r"^continue$",r"^التالي$",r"^ابدأ$"]):
            break
        time.sleep(1.5)
    else:
        break
capture("02-setup")
# Open the KodaStream-native repository deep-link, not a bespoke plugin loader.
uri="cloudstreamrepo://"+REPO
adb("shell","am","start","-a","android.intent.action.VIEW","-d",uri,PKG,timeout=30)
time.sleep(4)
capture("03-repository-link")
brief()
if not tap_label([r"^add$",r"^إضافة$",r"^اضافة$"],wait=4):
    # Account for a language/overlay dialog that blocks the deep link.
    raise RuntimeError("KodaStream did not show the native add repository action.")
capture("04-repository-added")
if not wait_for(r"CineLite",limit=30):
    raise RuntimeError("CineLite plugin was not listed by the native repository manager.")
capture("05-plugin-listed")
if not tap_label([r"^install$",r"^تثبيت$",r"^update$"],wait=7):
    raise RuntimeError("CineLite plugin row did not provide an Install button.")
capture("06-plugin-install-attempt")
log=adb("logcat","-d","-t","2500",timeout=30)
(ROOT/"logcat.txt").write_text(log)
# Restrict to the installed list: installation must succeed, not just a button tap.
tap_label([r"^installed\s*\(\d+\)$",r"^installed.*",r"^المثبتة"],wait=3)
capture("07-installed-tab")
labels=brief()
if not any("CineLite" in x for x in labels):
    failures=[line for line in log.splitlines() if "cinelite" in line.lower() or "plugin" in line.lower() or "FATAL EXCEPTION" in line]
    print("CINELITE_RELATED_LOGS", "\n".join(failures[-35:])[:4500],flush=True)
    raise RuntimeError("The actual KodaStream installed-plugins UI did not confirm CineLite.")
print("PASS: Native KodaStream app installed CineLite .cs3 from its repository on Android emulator.",flush=True)
print("LIMITATION: Real streaming server/Arabic subtitles must be verified separately using playable content.",flush=True)
