"""Generate an index.html so pip's --find-links can index wheels whose
distribution name contains hyphens (e.g. chaquopy-libxml2). pip's flat
directory scan uses the strict packaging parser, which rejects such
filenames; HTML pages go through the lenient evaluator instead."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))

links = []
for entry in sorted(os.listdir(HERE)):
    if entry.endswith(".whl"):
        links.append(f'<a href="{entry}">{entry}</a>')
    elif entry.endswith(".html") and entry != "index.html":
        links.append(f'<a href="{entry}">{entry}</a>')

html = (
    "<!DOCTYPE html>\n<html><head><meta charset='utf-8'>"
    "<title>chaquopy-prebuilt local index</title></head><body>\n"
    + "\n".join(links)
    + "\n</body></html>\n"
)

with open(os.path.join(HERE, "index.html"), "w", encoding="utf-8") as f:
    f.write(html)
print(f"index.html written with {len(links)} entries")
