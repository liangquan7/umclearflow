# AI evaluation for UM-ClearFlow

Two things are evaluated, because those are the two AI features the app claims:

| Feature | Engines compared | Script |
|---|---|---|
| Photo triage (hazard type + severity) | `ondevice_hsv` (offline path in the APK), `server_heuristic`, `claude` | `eval_triage.py` |
| Safety chat assistant | `rules` (fallback), `llm` (Claude) | `eval_chat.py` |

The project brief asks you to justify each AI feature and report *accuracy on sample UM data*, *usefulness*, and *handling of incorrect outputs*. This folder produces tables for exactly that.

## 1. Collect the photo set (this is the part only you can do)

- **≥ 40 photos taken by your team at UM** (flooded roads, blocked drains, fallen branches/trees, slopes). Aim for ≥ 4 per cell of *4 types × {Medium, Critical}*, plus a few hard cases (night, rain on lens, partial view, glare).
- Use **your own photos**, not images from the web (copyright, and not "UM data"). Keep faces and number plates out of frame (PDPA).
- Put them in `evaluation/eval_data/images/` and describe each in `evaluation/eval_data/labels.csv` (copy `labels_template.csv`):

| column | meaning |
|---|---|
| `filename` | file in `images/` |
| `hazard_type` | `Flash Flood`, `Blocked Drain`, `Fallen Tree` or `Slope / Landslide` |
| `severity` | `Low`, `Medium` or `Critical` |
| `split` | `dev` (you may look at results and tune thresholds) or `test` (**report once, never tune on it**) |
| `title` | the title a student would type (only used with `--use-titles`) |
| `source`, `notes` | provenance, free text |

**Severity rubric (write this in the report).** *Critical* = immediate danger or a main path/road is blocked (deep or moving water, a large limb across the walkway, soil movement near a path, a drain overflowing onto a main route). *Medium* = hazard present but passable / localised. *Low* = negligible or no real hazard (use a few of these to measure false alarms; give them the category a user would have picked).

Have **two people label independently**, then settle differences — and say so in the report. Ground truth from the team is not ground truth from JPPHB; state that limitation.

## 2. Run

From `backend/`:

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=...          # without it the claude / llm engines are skipped, not faked

python evaluation/eval_triage.py --split dev    # look, debug, tune thresholds on dev only
python evaluation/eval_triage.py --split test   # final numbers, once
python evaluation/eval_chat.py --repeat 3       # LLM output varies between runs
```

Outputs land in `evaluation/eval_results/`: `triage_report.md`, `triage_results.csv`, `triage_summary.json`, `chat_report.md`, `chat_results.csv`. The `.md` files are written to be pasted into the report.

`python evaluation/make_smoke_dataset.py` creates 16 **synthetic** images (coloured noise) so you can confirm the pipeline runs. Never quote those numbers.

Tests: `pytest -q` (API) and `pytest -q evaluation` (evaluation tooling + chat regression tests) — run them as two separate commands.

## 3. How the triage evaluation is designed (say this in the video)

- **Same pipeline as the app**: each photo is EXIF-rotated, downsized to ≤ 1280 px and re-encoded as JPEG q85 before any engine sees it.
- **Neutral title by default.** Both heuristics read keywords in the title (`tree`, `drain`, …). Evaluating with the real title would test the keyword matcher, not the photo. Use `--use-titles` only to show that effect.
- **Three hint modes** — what the user chose in the category spinner: `none`, `correct`, `wrong`. The *Sensitivity* table shows whether an engine reads the photo or just echoes the user's choice. (The on-device severity logic depends on the chosen category by design.)
- **Safety-oriented metrics**: *Critical recall* and *Critical missed* matter more than overall accuracy, because a missed Critical hazard is the costly error. *False-alarm rate* is the cost on the other side.
- **Wilson 95 % intervals** on accuracy. With ~20 test photos the interval is ±20 points; quote it.
- **Confidence check**: mean confidence when right vs wrong. The heuristics' "confidence" is a formula, not a probability; if the two numbers are close, say that it is not informative.
- `ondevice_hsv` is a **Python port** of `CampusRepository.runVisionTriage`. Before quoting its numbers, run 3–5 photos through the app and confirm the same type/severity.

## 4. How to read and report the results

- If `ondevice_hsv` is near chance on the real photos, **say so** and position it as a last-resort offline estimate that the user must confirm (the UI already labels it "Heuristic"). That honest finding plus the Claude comparison is a stronger justification for the cloud AI than a flattering number.
- If Claude is better: report the gain *with* its cost (latency p50/p95, needs network, photo leaves the device → PDPA notice, no personal data sent).
- Use the *Failure cases* section directly for "handling incorrect outputs": show 2–3 wrong predictions, explain the cause (glare read as water, shadow read as drain…), and show the mitigation in the app (user sees the suggestion, can change the category, nothing is auto-dispatched).
- Do not tune thresholds on `test`. If you change thresholds, re-split or collect new test photos.

## 5. What the chat evaluation found so far (already fixed — quotable)

`eval_chat.py` ran 15 hand-written cases (English, Malay, Chinese; urgent, medical, prompt-injection, off-topic, route-safety). On the first run the deterministic fallback failed 11 of 67 checks. Real defects, now fixed in `backend/main.py` and locked by `test_chat_regressions.py`:

1. **Urgent requests without emergency numbers** — "I'm trapped in flood water near KK12" matched the route keyword and got route advice. Urgency is now checked first, in all three languages.
2. **Untrusted text echoed back** — a hazard title containing a phone number and "every route is safe" was repeated verbatim. The fallback now shows only severity + location; phone numbers and links are stripped before the text reaches either engine.
3. **Medical question with no guidance** — now says it cannot diagnose and gives the clinic / emergency numbers.
4. **English-only fallback** — Malay/Chinese users now get the "preset information, verify with official UM sources" caveat in their language (the detailed body is still English; say so).

Caveats: these fixes were developed against the same 15 cases, so they prove the regressions are closed, not that the assistant is safe in general. Write **10 more held-out cases** before the final run and report those separately. The LLM engine has **not** been evaluated yet — it needs your API key (`python evaluation/eval_chat.py --repeat 3`).

## 6. Keep the AI usage log as you go

See `AI_USAGE_LOG_TEMPLATE.md`. The brief requires prompts, how you verified the output and what you changed; reconstructing this at the end is how entries go missing.
