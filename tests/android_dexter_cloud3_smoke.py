#!/usr/bin/env python3
"""Install real Dexter CS3 into cloud3 0.8.0 and check for app crash after loading."""
import os,re,subprocess,time,pathlib,xml.etree.ElementTree as ET
ROOT=pathlib.Path("artifacts/android-dexter"); ROOT.mkdir(parents=True,exist_ok=True)
PKG="com.kodastream.app"
REPO="https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/dexter-preview/repo.json"
EXPECTED_VERSION=os.environ.get("DEXTER_VERSION","3")
def adb(*args,timeout=45):
    p=subprocess.run(["adb",*args],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    return p.stdout
def labels():
    adb("shell","uiautomator","dump","/sdcard/ui.xml",timeout=35)
    raw=adb("exec-out","cat","/sdcard/ui.xml",timeout=25)
    if "<?xml" not in raw:return []
    root=ET.fromstring(raw[raw.find("<?xml"):])
    out=[]
    for node in root.iter("node"):
        a=node.attrib
        v=a.get("text") or a.get("content-desc") or ""
        b=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",a.get("bounds",""))
        if b:
            x1,y1,x2,y2=map(int,b.groups())
            out.append((v,((x1+x2)//2,(y1+y2)//2)))
    return out
def capture(name):
    with (ROOT/(name+".png")).open("wb") as f:
        subprocess.run(["adb","exec-out","screencap","-p"],stdout=f,check=True,timeout=30)
    (ROOT/(name+".txt")).write_text("\n".join(n[0] for n in labels()))
def tap(patterns):
    for p in patterns:
        a=[v for v in labels() if re.search(p,v[0],re.I)]
        if a:
            pos=a[-1][1]
            adb("shell","input","tap",str(pos[0]),str(pos[1]))
            time.sleep(3)
            print("TAPPED:",a[-1][0],flush=True)
            return True
    return False
def snapshot_logs(name):
    log=adb("logcat","-d","-t","3500",timeout=50)
    (ROOT/name).write_text(log)
    return log
def alive():
    pid=adb("shell","pidof",PKG).strip()
    return bool(pid)
def fail_if_crashed(log):
    suspects=[x for x in log.splitlines() if "FATAL EXCEPTION" in x or
        ("AndroidRuntime" in x and "com.kodastream.app" in x) or
        ("Failed to load" in x and "Dexter" in x)]
    if suspects:
        print("POSSIBLE_DEXTER_CRASH:\n"+"\n".join(suspects[-30:]),flush=True)
        raise RuntimeError("Cloud3 reported a plugin/class-loading crash")
print("APK:",adb("shell","pm","list","packages",PKG),flush=True)
adb("logcat","-c")
adb("shell","am","start","-n",PKG+"/.MainActivity")
time.sleep(8)
for _ in range(5):
    if tap([r"^Next$",r"^Start$",r"^Continue$",r"^التالي$",r"^ابدأ$"]):
        continue
    break
capture("before")
print("Before install alive:",alive(),flush=True)
deeplink="cloudstreamrepo://"+REPO
adb("shell","am","start","-a","android.intent.action.VIEW","-p",PKG,"-d",deeplink)
time.sleep(5)
capture("add-repo")
if not tap([r"^Add$",r"^إضافة$",r"^اضافة$"]):
    raise RuntimeError("Could not reach native Add repository confirmation")
capture("after-add")
view="\n".join(x[0] for x in labels())
if "Dexter" not in view:
    raise RuntimeError("Dexter not listed in cloud3 repository manager")
if not tap([r"^Install$",r"^تثبيت$",r"^Update$",r"^تحديث$"]):
    raise RuntimeError("Could not click native install action")
capture("post-install")
time.sleep(10)
logs=snapshot_logs("install-logcat.txt")
fail_if_crashed(logs)
if not alive():
    raise RuntimeError("Cloud3 process vanished after plugin install")
adb("shell","am","force-stop",PKG)
adb("logcat","-c")
adb("shell","am","start","-n",PKG+"/.MainActivity")
time.sleep(11)
capture("restarted")
logs=snapshot_logs("restart-logcat.txt")
fail_if_crashed(logs)
if not alive():raise RuntimeError("Cloud3 crashed during restart with Dexter installed")
print("PASS: cloud3 remains alive after native plugin install and restart",flush=True)
print("LIMIT: this test does not prove server video or Arabic captions are playable",flush=True)
