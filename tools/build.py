#!/usr/bin/env python3
"""EX30 Reader: src/app.html + src/panel.html → ex30-reader.html (tek dosya).

Panel, ana sayfanın içine <template id="panelSrc"> olarak gömülür; Panel sekmesi
açılınca bir iframe'e (srcdoc) yüklenir. İki sayfanın kodu böylece birbirine
karışmaz ama tek dosya ve tek link olur.
"""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent
app = (root / "src/app.html").read_text(encoding="utf-8")
panel = (root / "src/panel.html").read_text(encoding="utf-8")

panel = re.sub(r"^\s*<!DOCTYPE html>\s*", "", panel, flags=re.I)
if "</template" in panel.lower():
    sys.exit("panel.html içinde </template geçemez")

# Sürüş ekranı yazı tipi (Barlow Semi Condensed, SIL OFL) dosyaya gömülür: araçta internet olmasa da çalışsın
import base64
LATIN = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"
LATEXT = "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF"
faces = []
for w in (500, 700):
    for sub, rng in (("latin", LATIN), ("latin-ext", LATEXT)):
        f = root / f"src/fonts/barlow-semi-condensed-{sub}-{w}-normal.woff2"
        faces.append("@font-face{font-family:'EX30 Gauge';font-style:normal;font-weight:%d;font-display:block;src:url(data:font/woff2;base64,%s) format('woff2');unicode-range:%s}" % (w, base64.b64encode(f.read_bytes()).decode(), rng))
app = app.replace("/*@FONTS@*/", "\n".join(faces))

marker = "</body>"
i = app.rfind(marker)
if i < 0:
    sys.exit("app.html içinde </body> yok")
panel = panel.replace("/*@FONTS@*/", "\n".join(faces))
out = app[:i] + '<template id="panelSrc">\n' + panel + "\n</template>\n" + app[i:]
(root / "ex30-reader.html").write_text(out, encoding="utf-8")
print(f"ex30-reader.html yazıldı ({len(out)//1024} KB)")
