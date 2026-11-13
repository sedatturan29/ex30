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

marker = "</body>"
i = app.rfind(marker)
if i < 0:
    sys.exit("app.html içinde </body> yok")
out = app[:i] + '<template id="panelSrc">\n' + panel + "\n</template>\n" + app[i:]
(root / "ex30-reader.html").write_text(out, encoding="utf-8")
print(f"ex30-reader.html yazıldı ({len(out)//1024} KB)")
