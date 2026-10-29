#!/usr/bin/env python3
"""Follow-up smoke probe inside the REAL KodaStream UI after CineLite installation.

This is an observational test: it cannot infer media-playback success from the
presence of cards. Read-only navigation and real app searches only.
"""
import pathlib, subprocess, re, time, xml.etree.ElementTree as ET
ROOT=pathlib.Path("artifacts/android-cinelite")
ROOT.mkdir(parents=True,exist_ok=True)
catalog_errors=[]

def adb(*args):
    p=subprocess.run(["adb",*args],capture_output=True,text=True,timeout=45)
    if p.returncode: raise RuntimeError(str(args)+":"+p.stderr[-250:])
    return p.stdout
def ui():
    adb("shell","uiautomator","dump","/sdcard/koda.xml")
    s=adb("exec-out","cat","/sdcard/koda.xml")
    root=ET.fromstring(s[s.index("<?xml"):])
    out=[]
    for n in root.iter("node"):
        a=n.attrib
        t=a.get("text") or a.get("content-desc") or ""
        m=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",a.get("bounds",""))
        if m:
            z=list(map(int,m.groups()))
            out.append((t,(z[0]+z[2])//2,(z[1]+z[3])//2))
    return out
def show(name):
    view=ui()
    print("APP_UI_"+name.upper(),repr([v[0] for v in view if v[0]][:130]),flush=True)
    with (ROOT/("08-"+name+".png")).open("wb") as f:
        subprocess.run(["adb","exec-out","screencap","-p"],stdout=f,check=True,timeout=25)
    return view
def tap(regexes):
    for regex in regexes:
        items=[(t,x,y) for t,x,y in ui() if re.search(regex,t,re.I)]
        if items:
            t,x,y=items[-1]
            adb("shell","input","tap",str(x),str(y))
            print("APP_UI_TAP",repr(t),flush=True)
            time.sleep(1.5)
            return True
    return False

tap([r"^Home$",r"^الرئيسية$"])
show("home-after-install")
if tap([r"Choose source",r"CineLite"]) :
    show("source-sheet")
    tap([r"^CineLite$",r"CineLite"])
    time.sleep(12)
    home=show("cinelite-home")
    failures=[t for t,x,y in home if re.search("page did not render|no real cards|network error|HTTP 403",t,re.I)]
    if failures:
        print("APP_UI_CATALOG_FAILURE",repr(failures),flush=True)
        catalog_errors.extend(failures)
        # The provider logs only HTTP status, WebView error codes and
        # annotated-card counts, never authenticated stream URLs.
        log=adb("logcat","-d","-t","4000")
        lines=[line for line in log.splitlines() if "CineLiteSnapshot" in line]
        print("CINELITE_WEBVIEW_DIAGNOSTIC",repr(lines[-35:]),flush=True)
        (ROOT/"catalog-webview-diagnostics.txt").write_text("\n".join(lines[-100:]))
else:
    print("APP_UI_SOURCE_MISSING: source picker not accessible",flush=True)
tap([r"^Search$",r"^بحث$"])
page=show("search")
# KodaSearch will submit only on IME search, not every keypress.
candidates=[(x,y) for t,x,y in page if re.search("Search every provider|Search movies|ابحث",t,re.I)]
if candidates:
    x,y=candidates[-1]
    adb("shell","input","tap",str(x),str(y))
    adb("shell","input","text","ONE%sPIECE"%"%s")
    adb("shell","input","keyevent","66")
    time.sleep(12)
    search=show("one-piece-search")
    if "No results" not in [t for t,x,y in search] and any(
        t.strip().upper()=="ONE PIECE" for t,x,y in search
    ):
        print("APP_UI_SEARCH_FOUND: ONE PIECE in actual KodaStream search results",flush=True)
        if tap([r"^ONE PIECE$"]):
            time.sleep(18)
            details=show("one-piece-details")
            eps=[t for t,x,y in details if re.search("Episode 1|S01E01|Ep 1|الحلقة 1",t,re.I)]
            print("APP_UI_EPISODES",repr(eps[:25]),flush=True)
            if eps:
                print("PASS: CineLite displays actual ONE PIECE episode options on Android KodaStream",flush=True)
            else:
                print("APP_UI_EPISODES_UNVERIFIED: no episode labels visible in native details UI",flush=True)
    else:
        print("APP_UI_SEARCH_NOT_FOUND: no ONE PIECE search card in this session",flush=True)
else:
    print("APP_UI_SEARCH_FIELD_MISSING",flush=True)
print("CATALOG_PROBE_COMPLETE: do not interpret this as successful media playback",flush=True)
if catalog_errors:
    raise RuntimeError("CineLite native Android catalog failed to render; see safe WebView diagnostics")
