#!/usr/bin/env python3
"""Evaluate hazard-photo triage engines on a labelled UM photo set.

Engines
  ondevice_hsv      Python port of CampusRepository.runVisionTriage (the offline path inside the APK)
  server_heuristic  backend/main.py _heuristic_triage (server fallback when no API key)
  claude            backend/main.py _llm_triage (needs ANTHROPIC_API_KEY)

Hint modes (what the user picked in the "Hazard Category" spinner)
  none     no usable hint      (on-device: index 0 = Flash Flood, the app default)
  correct  hint = true label
  wrong    hint = a different, wrong label

Usage (from backend/):
  python evaluation/eval_triage.py --data evaluation/eval_data --split test
  python evaluation/eval_triage.py --data evaluation/eval_data_smoke --split all --engines ondevice_hsv,server_heuristic
"""
import argparse
import colorsys
import csv
import json
import os
import sys
import tempfile
import time
from io import BytesIO
from pathlib import Path

# The backend creates a SQLite DB on import: keep it out of the repo.
_tmp = tempfile.mkdtemp()
os.environ.setdefault("CLEARFLOW_DB", os.path.join(_tmp, "eval.db"))
os.environ.setdefault("CLEARFLOW_PHOTOS", os.path.join(_tmp, "photos"))
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import eval_utils as U  # noqa: E402
import main as backend  # noqa: E402
from PIL import Image, ImageOps  # noqa: E402

TYPES = ["Flash Flood", "Blocked Drain", "Fallen Tree", "Slope / Landslide"]
NONE = "None"
LABELS = TYPES + [NONE]          # ground truth / predictions may be any of these
SEVERITIES = ["Low", "Medium", "Critical"]
NEUTRAL_TITLE = "Campus hazard"   # the heuristics read keywords in the title; a neutral one removes label leakage


# ----------------------------------------------------------------------------- data

def load_labels(data_dir: Path, split: str):
    path = data_dir / "labels.csv"
    if not path.is_file():
        raise SystemExit(f"Missing {path}. See evaluation/README.md for the labelling format.")
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["hazard_type"] not in LABELS:
                raise SystemExit(f"{r['filename']}: hazard_type must be one of {LABELS}")
            if r["severity"] not in SEVERITIES:
                raise SystemExit(f"{r['filename']}: severity must be one of {SEVERITIES}")
            if split != "all" and (r.get("split") or "test") != split:
                continue
            if not (data_dir / "images" / r["filename"]).is_file():
                raise SystemExit(f"Missing image: {data_dir / 'images' / r['filename']}")
            rows.append(r)
    return rows


def prepare_like_the_app(path: Path):
    """The APK sends a <=1280px JPEG (quality 85). Do the same so we evaluate the real pipeline."""
    img = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    img.thumbnail((1280, 1280))
    buf = BytesIO()
    img.save(buf, "JPEG", quality=85)
    data = buf.getvalue()
    return data, Image.open(BytesIO(data)).convert("RGB")


def hint_for(mode: str, truth: str, user_category: str = ""):
    """What the user picked in the category spinner. For a 'None' photo the user still had to pick something."""
    base = truth if truth in TYPES else (user_category if user_category in TYPES else TYPES[0])
    idx = TYPES.index(base)
    if mode == "correct":
        return base, idx
    if mode == "wrong":
        wrong = (idx + 1) % len(TYPES)
        return TYPES[wrong], wrong
    return "", 0


# ----------------------------------------------------------------------------- engines

def ondevice_hsv_triage(img, hint_index: int, title: str, rainfall_mm: float = 0.0):
    """Line-by-line port of CampusRepository.runVisionTriage (bitmap != null, backend unreachable).
    Keep in sync with the Java code; verify parity on a few photos inside the app before quoting numbers."""
    selected = TYPES[hint_index] if 0 <= hint_index < len(TYPES) else TYPES[0]
    w, h = img.size
    step_x, step_y = max(1, w // 24), max(1, h // 24)
    px = img.load()
    total = muddy = green = dark = reflect = 0
    for y in range(0, h, step_y):
        for x in range(0, w, step_x):
            r, g, b = px[x, y]
            hh, ss, vv = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
            hue = hh * 360.0
            total += 1
            if 15 <= hue <= 52 and ss >= 0.22 and 0.20 <= vv <= 0.85:
                muddy += 1
            if 65 <= hue <= 165 and ss >= 0.20 and vv >= 0.15:
                green += 1
            if vv < 0.25:
                dark += 1
            if ss < 0.18 and vv > 0.72:
                reflect += 1
    muddy_r, green_r, dark_r, reflect_r = (v / total if total else 0.0 for v in (muddy, green, dark, reflect))

    t = title.lower()
    detected = selected
    if "tree" in t or "branch" in t or green_r > 0.28:
        detected = "Fallen Tree"
    elif "drain" in t or "grate" in t or "culvert" in t or (dark_r > 0.30 and muddy_r < 0.22):
        detected = "Blocked Drain"
    elif "slope" in t or "landslide" in t:
        detected = "Slope / Landslide"
    elif muddy_r + reflect_r > 0.32:
        detected = "Flash Flood"

    if hint_index == 0 or muddy_r + reflect_r > 0.42:
        water = muddy_r * 1.4 + reflect_r * 0.8
        severity = "Critical" if (water > 0.35 or rainfall_mm > 5.0) else "Medium"
        confidence = min(0.96, 0.74 + water * 0.35)
    elif hint_index == 2 or green_r > 0.28:
        severity = "Critical" if (green_r > 0.38 or dark_r > 0.30) else "Medium"
        confidence = min(0.94, 0.72 + green_r * 0.40)
    else:
        severity = "Critical" if dark_r > 0.32 else "Medium"
        confidence = min(0.92, 0.70 + (dark_r + muddy_r) * 0.35)
    return {"hazard_type": detected, "severity": severity, "confidence": confidence,
            "note": f"water={muddy_r + reflect_r:.2f} green={green_r:.2f} dark={dark_r:.2f}"}


ENGINES = {
    "ondevice_hsv": lambda raw, img, hint, idx, title: ondevice_hsv_triage(img, idx, title),
    "server_heuristic": lambda raw, img, hint, idx, title: backend._heuristic_triage(raw, hint, title),
    "claude": lambda raw, img, hint, idx, title: backend._llm_triage(raw, hint, title, ""),
}


# ----------------------------------------------------------------------------- metrics

def summarise(rows):
    ok = [r for r in rows if r["pred_type"]]
    n, total = len(ok), len(rows)
    truth = [r["truth_type"] for r in ok]
    pred = [r["pred_type"] for r in ok]
    type_ok = sum(1 for t, p in zip(truth, pred) if t == p)
    sev_ok = sum(1 for r in ok if r["truth_sev"] == r["pred_sev"])
    crit = [r for r in ok if r["truth_sev"] == "Critical"]
    crit_hit = sum(1 for r in crit if r["pred_sev"] == "Critical")
    noncrit = [r for r in ok if r["truth_sev"] != "Critical"]
    false_alarm = sum(1 for r in noncrit if r["pred_sev"] == "Critical")
    per = U.prf(truth, pred, LABELS)
    right = [float(r["confidence"]) for r in ok if r["pred_type"] == r["truth_type"]]
    wrong = [float(r["confidence"]) for r in ok if r["pred_type"] != r["truth_type"]]
    lat = [float(r["latency_ms"]) for r in ok]
    return {
        "n_total": total, "n_ok": n, "fail_rate": (total - n) / total if total else 0.0,
        "type_acc": type_ok / n if n else 0.0, "type_ci": U.wilson(type_ok, n), "type_k": type_ok,
        "macro_f1": U.macro_f1(per), "per_class": per,
        "sev_acc": sev_ok / n if n else 0.0, "sev_ci": U.wilson(sev_ok, n), "sev_k": sev_ok,
        "crit_n": len(crit), "crit_recall": crit_hit / len(crit) if crit else float("nan"), "crit_missed": len(crit) - crit_hit,
        "noncrit_n": len(noncrit), "false_alarm": false_alarm / len(noncrit) if noncrit else float("nan"),
        "conf_right": sum(right) / len(right) if right else float("nan"),
        "conf_wrong": sum(wrong) / len(wrong) if wrong else float("nan"),
        "lat_p50": U.percentile(lat, 50), "lat_p95": U.percentile(lat, 95),
        "confusion": U.confusion(truth, pred, LABELS),
    }


def pct(x):
    return "n/a" if x != x else f"{x * 100:.0f}%"


def ci(a):
    return f"{a[0] * 100:.0f}–{a[1] * 100:.0f}%"


# ----------------------------------------------------------------------------- report

def write_report(path: Path, results, summaries, args, engines, modes):
    L = []
    L.append("# Hazard-photo triage evaluation\n")
    imgs = len({r["filename"] for r in results})
    L.append(f"- Images: **{imgs}** (split = `{args.split}`), titles = "
             f"{'real titles from labels.csv' if args.use_titles else 'neutral title (no keyword leakage)'}")
    L.append(f"- Engines: {', '.join(engines)}  |  hint modes: {', '.join(modes)}")
    L.append(f"- Date: {time.strftime('%Y-%m-%d %H:%M')}  |  Claude model: "
             f"{backend.CLAUDE_MODEL if 'claude' in engines else 'not run'}")
    L.append("- 95 % intervals are Wilson score intervals; with n < 50 they are wide — quote them, not just the point value.\n")

    L.append("## Headline results\n")
    L.append("| Engine | Hint | n ok/total | Type acc. (95% CI) | Macro-F1 | Severity acc. (95% CI) | "
             "Critical recall | Critical missed | False-alarm rate | Conf. right / wrong | Latency p50 / p95 |")
    L.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for (eng, mode), s in summaries.items():
        L.append(f"| {eng} | {mode} | {s['n_ok']}/{s['n_total']} | {pct(s['type_acc'])} ({ci(s['type_ci'])}) | "
                 f"{s['macro_f1']:.2f} | {pct(s['sev_acc'])} ({ci(s['sev_ci'])}) | {pct(s['crit_recall'])} "
                 f"({s['crit_n']} Critical) | {s['crit_missed']} | {pct(s['false_alarm'])} | "
                 f"{pct(s['conf_right'])} / {pct(s['conf_wrong'])} | {s['lat_p50']:.0f} / {s['lat_p95']:.0f} ms |")
    L.append("\n*Critical recall* = share of truly Critical photos that were labelled Critical (a miss is the safety-relevant error). "
             "*False-alarm rate* = non-Critical photos labelled Critical. *Conf. right / wrong* = mean self-reported "
             "confidence when the type was right vs wrong — if they are close, confidence is not informative.\n")

    L.append("## Per-class results (hint = none)\n")
    for (eng, mode), s in summaries.items():
        if mode != "none":
            continue
        L.append(f"**{eng}**\n")
        L.append("| Class | Precision | Recall | F1 | Support |")
        L.append("|---|---|---|---|---|")
        for lab, v in s["per_class"].items():
            L.append(f"| {lab} | {v['precision']:.2f} | {v['recall']:.2f} | {v['f1']:.2f} | {v['support']} |")
        L.append("")

    L.append("## Confusion matrices (rows = truth, columns = predicted; hint = none)\n")
    short = {t: t.split()[0][:6] for t in LABELS}
    for (eng, mode), s in summaries.items():
        if mode != "none":
            continue
        L.append(f"**{eng}**\n")
        L.append("| truth \\ pred | " + " | ".join(short[t] for t in LABELS) + " |")
        L.append("|---|" + "---|" * len(LABELS))
        for t in LABELS:
            L.append(f"| {t} | " + " | ".join(str(s['confusion'][(t, p)]) for p in LABELS) + " |")
        L.append("")

    # does the user's category choice drive the answer?
    if len(modes) > 1:
        L.append("## Sensitivity to the user's category choice\n")
        L.append("| Engine | " + " | ".join(f"Type acc. ({m})" for m in modes) + " | " +
                 " | ".join(f"Severity acc. ({m})" for m in modes) + " |")
        L.append("|---|" + "---|" * (2 * len(modes)))
        for eng in engines:
            row = [pct(summaries[(eng, m)]["type_acc"]) for m in modes] + [pct(summaries[(eng, m)]["sev_acc"]) for m in modes]
            L.append(f"| {eng} | " + " | ".join(row) + " |")
        L.append("\nA large drop from `correct` to `wrong` means the engine mostly echoes the user's choice instead of reading the photo.\n")

    # paired comparison
    if "ondevice_hsv" in engines and "claude" in engines:
        L.append("## Paired comparison: on-device vs Claude (type correct?)\n")
        L.append("| Hint | both right | only Claude right | only on-device right | both wrong |")
        L.append("|---|---|---|---|---|")
        for mode in modes:
            a = {r["filename"]: r for r in results if r["engine"] == "ondevice_hsv" and r["hint_mode"] == mode and r["pred_type"]}
            b = {r["filename"]: r for r in results if r["engine"] == "claude" and r["hint_mode"] == mode and r["pred_type"]}
            both = sorted(set(a) & set(b))
            ar = {f: a[f]["pred_type"] == a[f]["truth_type"] for f in both}
            br = {f: b[f]["pred_type"] == b[f]["truth_type"] for f in both}
            L.append(f"| {mode} | {sum(ar[f] and br[f] for f in both)} | {sum(br[f] and not ar[f] for f in both)} | "
                     f"{sum(ar[f] and not br[f] for f in both)} | {sum(not ar[f] and not br[f] for f in both)} |")
        L.append("")

    L.append("## Failure cases (type or severity wrong) — use these for the “handling incorrect outputs” discussion\n")
    for (eng, mode) in summaries:
        if mode not in ("none", "correct"):
            continue
        bad = [r for r in results if r["engine"] == eng and r["hint_mode"] == mode and r["pred_type"]
               and (r["pred_type"] != r["truth_type"] or r["pred_sev"] != r["truth_sev"])]
        failed = [r for r in results if r["engine"] == eng and r["hint_mode"] == mode and not r["pred_type"]]
        L.append(f"**{eng} / hint={mode}** — {len(bad)} wrong, {len(failed)} no answer\n")
        for r in bad[:15]:
            L.append(f"- `{r['filename']}`: truth {r['truth_type']}/{r['truth_sev']} → predicted "
                     f"{r['pred_type']}/{r['pred_sev']} (conf {float(r['confidence']):.2f}). {r['note'][:140]}")
        for r in failed[:5]:
            L.append(f"- `{r['filename']}`: no answer ({r['error'] or 'engine returned None'})")
        L.append("")

    L.append("## Limitations to state honestly\n")
    L.append("- Small, single-campus photo set; results do not generalise to other weather, lighting or camera conditions.")
    L.append("- `ondevice_hsv` is a Python port of the Java code; confirm parity on a few photos in the app.")
    L.append("- Ground-truth labels come from the team (not from JPPHB engineers); severity labelling is subjective.")
    L.append("- Do not tune thresholds on the `test` split; tune on `dev` and report `test` once.")
    path.write_text("\n".join(L), encoding="utf-8")


# ----------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", default=str(HERE / "eval_data"))
    ap.add_argument("--split", default="test", choices=["dev", "test", "all"])
    ap.add_argument("--engines", default="ondevice_hsv,server_heuristic,claude")
    ap.add_argument("--hint-modes", default="none,correct,wrong")
    ap.add_argument("--use-titles", action="store_true", help="use the title column instead of a neutral title")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--sleep", type=float, default=0.0, help="seconds between Claude calls (rate limits)")
    ap.add_argument("--out", default=str(HERE / "eval_results"))
    args = ap.parse_args()

    data_dir, out_dir = Path(args.data), Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    engines = [e.strip() for e in args.engines.split(",") if e.strip()]
    modes = [m.strip() for m in args.hint_modes.split(",") if m.strip()]
    for e in engines:
        if e not in ENGINES:
            raise SystemExit(f"Unknown engine {e}. Choose from {list(ENGINES)}")
    if "claude" in engines and backend._get_client() is None:
        print("! ANTHROPIC_API_KEY not set (or SDK missing): skipping the 'claude' engine.")
        engines.remove("claude")

    labels = load_labels(data_dir, args.split)
    if args.limit:
        labels = labels[:args.limit]
    if not labels:
        raise SystemExit("No images selected.")
    print(f"Evaluating {len(labels)} images x {len(modes)} hint modes x {len(engines)} engines")

    results = []
    for i, r in enumerate(labels, 1):
        raw, img = prepare_like_the_app(data_dir / "images" / r["filename"])
        title = (r.get("title") or NEUTRAL_TITLE) if args.use_titles else NEUTRAL_TITLE
        for mode in modes:
            hint, idx = hint_for(mode, r["hazard_type"], r.get("user_category", ""))
            for eng in engines:
                t0 = time.perf_counter()
                pred, err = None, ""
                try:
                    pred = ENGINES[eng](raw, img, hint, idx, title)
                except Exception as e:  # noqa: BLE001 - record and continue
                    err = repr(e)
                ms = (time.perf_counter() - t0) * 1000
                results.append({
                    "engine": eng, "hint_mode": mode, "filename": r["filename"],
                    "truth_type": r["hazard_type"], "truth_sev": r["severity"],
                    "pred_type": pred["hazard_type"] if pred else "", "pred_sev": pred["severity"] if pred else "",
                    "confidence": f"{pred['confidence']:.3f}" if pred else "0",
                    "latency_ms": f"{ms:.1f}", "note": (pred or {}).get("note", ""), "error": err,
                })
                if eng == "claude" and args.sleep:
                    time.sleep(args.sleep)
        print(f"  [{i}/{len(labels)}] {r['filename']}")

    csv_path = out_dir / "triage_results.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(results[0].keys()))
        w.writeheader()
        w.writerows(results)

    summaries = {}
    for eng in engines:
        for mode in modes:
            summaries[(eng, mode)] = summarise([x for x in results if x["engine"] == eng and x["hint_mode"] == mode])
    (out_dir / "triage_summary.json").write_text(json.dumps(
        {f"{e}|{m}": {k: v for k, v in s.items() if k not in ("confusion", "per_class")} for (e, m), s in summaries.items()},
        indent=2, default=str), encoding="utf-8")
    report = out_dir / "triage_report.md"
    write_report(report, results, summaries, args, engines, modes)
    print(f"\nWrote {csv_path}\n      {report}")


if __name__ == "__main__":
    main()
