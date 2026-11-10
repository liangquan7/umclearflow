#!/usr/bin/env python3
"""Generate a tiny SYNTHETIC image set so you can check that eval_triage.py runs end-to-end.

!!! The pictures are coloured noise, not real hazards. NEVER quote numbers from this set in the report.
    Real evaluation needs your own photos taken at UM (see README.md).

Usage:  python evaluation/make_smoke_dataset.py   ->  evaluation/eval_data_smoke/
"""
import csv
import random
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parent / "eval_data_smoke"
random.seed(7)

# (base RGB, noise) per class/severity: crude colour statistics the heuristics react to
RECIPES = {
    ("Flash Flood", "Critical"): ((125, 95, 55), 25),
    ("Flash Flood", "Medium"): ((150, 140, 125), 20),
    ("Blocked Drain", "Critical"): ((25, 25, 28), 12),
    ("Blocked Drain", "Medium"): ((90, 90, 95), 25),
    ("Fallen Tree", "Critical"): ((40, 110, 45), 30),
    ("Fallen Tree", "Medium"): ((110, 140, 90), 30),
    ("Slope / Landslide", "Critical"): ((100, 70, 45), 30),
    ("Slope / Landslide", "Medium"): ((130, 110, 90), 30),
}


def make(path, base, noise, size=(640, 480)):
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(size[1]):
        for x in range(size[0]):
            px[x, y] = tuple(max(0, min(255, c + random.randint(-noise, noise))) for c in base)
    img.save(path, "JPEG", quality=88)


def main():
    (OUT / "images").mkdir(parents=True, exist_ok=True)
    rows = []
    i = 0
    for (htype, sev), (base, noise) in RECIPES.items():
        for k in range(2):
            i += 1
            name = f"synthetic_{i:02d}.jpg"
            make(OUT / "images" / name, base, noise)
            rows.append({"filename": name, "hazard_type": htype, "severity": sev, "split": "dev" if k == 0 else "test",
                         "title": "", "source": "SYNTHETIC", "notes": "coloured noise - smoke test only"})
    with open(OUT / "labels.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"Wrote {len(rows)} synthetic images to {OUT}  (smoke test only - not real data)")


if __name__ == "__main__":
    main()
