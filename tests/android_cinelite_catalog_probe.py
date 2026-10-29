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
def exact_result_card(title):
    """Find an actual search-result label, not the EditText containing the query."""
    adb("shell","uiautomator","dump","/sdcard/koda.xml")
    xml_text=adb("exec-out","cat","/sdcard/koda.xml")
    root=ET.fromstring(xml_text[xml_text.index("<?xml"):])
    for node in root.iter("node"):
        a=node.attrib
        if (a.get("text") or "").strip().casefold()!=title.casefold():
            continue
        if "EditText" in a.get("class","") or a.get("focused")=="true":
            continue
        match=re.search(r"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]",a.get("bounds",""))
        if match:
            x1,y1,x2,y2=map(int,match.groups())
            return (x1+x2)//2,(y1+y2)//2
    return None

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
    # The provider's own WebView can legally wait up to 23s for React
    # hydration. The old 12s assertion was earlier than that timeout.
    time.sleep(30)
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
    catalog_errors.append("CineLite source picker was unavailable")
tap([r"^Search$",r"^بحث$"])
page=show("search")
# KodaSearch will submit only on IME search, not every keypress.
candidates=[(x,y) for t,x,y in page if re.search("Search every provider|Search movies|ابحث",t,re.I)]
if candidates:
    x,y=candidates[-1]
    adb("shell","input","tap",str(x),str(y))
    adb("shell","input","text","ONE%sPIECE"%"%s")
    adb("shell","input","keyevent","66")
    # Native KodaStream search dispatches the provider on IME submit;
    # wait longer than CineLite's 23s WebView cap before asserting absence.
    time.sleep(33)
    search=show("one-piece-search")
    search_labels=[t for t,x,y in search]
    has_no_results=any(re.search(r"No results|Nothing found|لا توجد نتائج",x,re.I) for x in search_labels)
    actual_card=exact_result_card("ONE PIECE")
    if not has_no_results and actual_card:
        print("APP_UI_SEARCH_FOUND: actual ONE PIECE result card (not query input)",flush=True)
        x,y=actual_card
        adb("shell","input","tap",str(x),str(y))
        time.sleep(18)
        details=show("one-piece-details")
        eps=[t for t,x,y in details if re.search("Episode 1|S01E01|Ep 1|الحلقة 1",t,re.I)]
        print("APP_UI_EPISODES",repr(eps[:25]),flush=True)
        if eps:
            print("PASS: CineLite displays actual ONE PIECE episode options on Android KodaStream",flush=True)
        else:
            print("APP_UI_EPISODES_UNVERIFIED: no episode labels visible in native details UI",flush=True)
            catalog_errors.append("ONE PIECE opened but no episode was shown")
    else:
        print("APP_UI_SEARCH_NOT_FOUND: no ONE PIECE search card in this session",flush=True)
        catalog_errors.append("ONE PIECE search returned no real provider result")
else:
    print("APP_UI_SEARCH_FIELD_MISSING",flush=True)
    catalog_errors.append("Native KodaStream search field unavailable")
# Always collect *sanitized* native WebView diagnostic codes, even when
# the KodaStream UI merely stays empty (rather than displaying a provider error).
log=adb("logcat","-d","-t","5000")
lines=[line for line in log.splitlines() if "CineLiteSnapshot" in line]
print("CINELITE_WEBVIEW_DIAGNOSTIC",repr(lines[-65:]),flush=True)
(ROOT/"catalog-webview-diagnostics.txt").write_text("\\n".join(lines[-120:]))
# Include bounded, non-sensitive WebView diagnostics when the source fails.
# Never print URL query strings, cookies, video tokens or request headers.
if catalog_errors:
    try:
        raw=adb("logcat","-d","-t","5000")
        lines=[line for line in raw.splitlines() if "CineLiteSnapshot:" in line]
        print("CINELITE_ANDROID_DIAG",repr(lines[-45:]),flush=True)
        (ROOT/"snapshot-diagnostics.txt").write_text("\\n".join(lines[-90:]))
        print("ANDROID_WEBVIEW_PACKAGE",adb("shell","dumpsys","webviewupdate").splitlines()[:14],flush=True)
    except Exception as e:
        print("ANDROID_DIAG_ERROR",str(e)[:180],flush=True)
print("CATALOG_PROBE_COMPLETE: do not interpret this as successful media playback",flush=True)
if catalog_errors:
    raise RuntimeError("CineLite real Android catalog/search/episodes not confirmed: "+"; ".join(catalog_errors))
