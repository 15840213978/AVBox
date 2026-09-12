"""Scan pip http-v2 cache, extract android-tagged + pure-py wheels into wheelhouse."""
import io
import os
import sys
import zipfile
from email.parser import Parser

CACHE = os.path.expandvars(r"%LOCALAPPDATA%\pip\cache\http-v2")
OUT = os.path.dirname(os.path.abspath(__file__))

def sniff(path):
    try:
        with open(path, "rb") as f:
            head = f.read(4)
        if head[:2] != b"PK":
            return None
        with zipfile.ZipFile(path) as z:
            wheel_name = None
            for n in z.namelist():
                if n.endswith(".dist-info/WHEEL"):
                    wheel_name = n
                    break
            if not wheel_name:
                return None
            tags = Parser().parsestr(z.read(wheel_name).decode("utf-8", "replace")).get_all("Tag") or []
            meta_name = wheel_name.replace("/WHEEL", "/METADATA")
            meta = Parser().parsestr(z.read(meta_name).decode("utf-8", "replace"))
            name = meta.get("Name")
            version = meta.get("Version")
            return name, version, tags
    except Exception:
        return None

count = 0
for root, _dirs, files in os.walk(CACHE):
    for fn in files:
        p = os.path.join(root, fn)
        info = sniff(p)
        if not info:
            continue
        name, version, tags = info
        if not name or not version:
            continue
        # 只要 android 平台 wheel 或纯 python wheel(构建目标需要)
        interesting = any("android" in t for t in tags) or any(t == "py3-none-any" for t in tags)
        if not interesting:
            continue
        base = "-".join([name, version] + [t for t in tags])
        # 标准 wheel 文件名: name-version-tags.whl(取第一个 tag 组合)
        fname = f"{name}-{version}-{tags[0]}.whl"
        dest = os.path.join(OUT, fname)
        if not os.path.exists(dest):
            with open(p, "rb") as src, open(dest, "wb") as dst:
                dst.write(src.read())
            print(f"extracted: {fname}")
            count += 1
print(f"done, {count} new wheels -> {OUT}")
