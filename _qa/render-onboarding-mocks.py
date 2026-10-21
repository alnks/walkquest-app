# -*- coding: utf-8 -*-
"""Рендер пошаговых моков онбординга (04-onboarding) из утверждённого HTML.

Метод сверки (compare/README.md): макет — отрендеренный headless Edge по одному
экрану на файл, масштаб 2x. В compare/_mock лежал только шаг 1 (полный телефон);
этот скрипт достраивает шаги 2-4 (и обновляет 1) из mockups/onboarding-duo.html:
каждый шаг x тема вырезается в standalone-страницу и снимается через headless Edge.

Выход: _bmad-output/initiative-walkquest/ux-walkquest/compare/_mock/
        04-onboarding-{dark|light}-s{1..4}.png  (524x1040, 2x)
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HTML = ROOT / "_bmad-output/initiative-walkquest/ux-walkquest/mockups/onboarding-duo.html"
OUT = ROOT / "_bmad-output/initiative-walkquest/ux-walkquest/compare/_mock"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

STYLE_RE = re.compile(r"<style>(.*?)</style>", re.S)
SECTION_RE = re.compile(r"<h2>(\d+)\..*?</h2>\s*<div class=\"wrap\">(.*?)(?=<h2>|</body>)", re.S)
PHONE_RE = re.compile(r"<div class=\"phone ([dl])\">(.*?)</div></div></div>", re.S)

STEPS = {1: "s1", 2: "s2", 3: "s3", 4: "s4"}
THEME_CLASS = {"d": "dark", "l": "light"}


def main() -> None:
    src = HTML.read_text(encoding="utf-8")
    style = STYLE_RE.search(src).group(1)
    OUT.mkdir(parents=True, exist_ok=True)

    rendered = 0
    for section in SECTION_RE.finditer(src):
        step_no = int(section.group(1))
        if step_no not in STEPS:
            continue
        body = section.group(2)
        for phone in PHONE_RE.finditer(body):
            theme = THEME_CLASS[phone.group(1)]
            inner = phone.group(2)
            doc = (
                "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                f"<style>{style}</style></head>"
                "<body style='padding:0;background:#ffffff;margin:0'>"
                f"<div class=\"col\" style=\"width:262px\">"
                f"<div class=\"phone {phone.group(1)}\">{inner}</div>"
                "</div>"
                "</body></html>"
            )
            with tempfile.NamedTemporaryFile(
                "w", suffix=".html", delete=False, encoding="utf-8",
            ) as f:
                f.write(doc)
                tmp = Path(f.name)
            out = OUT / f"04-onboarding-{theme}-s{step_no}.png"
            subprocess.run(
                [
                    EDGE, "--headless", "--disable-gpu", "--hide-scrollbars",
                    "--force-device-scale-factor=2",
                    "--window-size=266,524",
                    f"--screenshot={out}",
                    tmp.as_uri(),
                ],
                check=True,
                capture_output=True,
                timeout=60,
            )
            tmp.unlink()
            rendered += 1
            print(f"OK {out.name}")
    if rendered != 8:
        sys.exit(f"FAIL: отрендерено {rendered} файлов вместо 8 (паттерны HTML не совпали?)")


if __name__ == "__main__":
    main()
