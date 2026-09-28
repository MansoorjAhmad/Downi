"""Extract inline <script> blocks (no src=) from www/index.html into one temp
.js so `node --check` can parse-verify the whole web layer. v3.1.4 gate."""
import re, sys, tempfile, os

html = open(os.path.join(os.path.dirname(__file__), "..", "www", "index.html"),
            encoding="utf-8").read()
blocks = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", html,
                    re.DOTALL | re.IGNORECASE)
out = os.path.join(tempfile.gettempdir(), "downi_inline.js")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n;\n".join(blocks))
print(f"{len(blocks)} inline script blocks -> {out}")
