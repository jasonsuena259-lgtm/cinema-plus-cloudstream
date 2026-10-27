#!/usr/bin/env python3
"""Compile production Kotlin regex literals against ICU (Android's regex engine family)."""
import ctypes, ctypes.util, glob, json, re, sys
lib=ctypes.CDLL(ctypes.util.find_library('icui18n'))
match=re.search(r'\.so\.(\d+)',lib._name)
suffix='_'+match.group(1) if match else ''
open_regex=getattr(lib,'uregex_open'+suffix)
open_regex.argtypes=[ctypes.POINTER(ctypes.c_uint16),ctypes.c_int32,ctypes.c_uint32,ctypes.c_void_p,ctypes.POINTER(ctypes.c_int32)]
open_regex.restype=ctypes.c_void_p
close_regex=getattr(lib,'uregex_close'+suffix); close_regex.argtypes=[ctypes.c_void_p]
def compile_pattern(pattern):
 raw=pattern.encode('utf-16-le'); chars=(ctypes.c_uint16*(len(raw)//2)).from_buffer_copy(raw); status=ctypes.c_int32(0)
 handle=open_regex(chars,len(chars),0,None,ctypes.byref(status))
 if handle: close_regex(handle)
 return status.value
files=glob.glob('*/src/main/kotlin/**/*.kt',recursive=True)
failed=0; count=0
for filename in files:
 source=open(filename,encoding='utf8').read()
 for found in re.finditer(r'Regex\((""".*?"""|"(?:\\.|[^"\\])*")\)',source,re.S):
  literal=found.group(1); pattern=literal[3:-3] if literal.startswith('"""') else json.loads(literal)
  result=compile_pattern(pattern); count+=1
  if result>0: print('FAIL',filename,repr(pattern),'ICU error',result); failed+=1
print('ICU patterns:',count,'failures:',failed)
sys.exit(bool(failed) or count==0)
