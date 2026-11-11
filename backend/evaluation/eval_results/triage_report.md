# Hazard-photo triage evaluation

- Images: **24** (split = `test`), titles = neutral title (no keyword leakage)
- Engines: ondevice_hsv, server_heuristic, claude  |  hint modes: none, correct, wrong
- Date: 2026-11-11 20:12  |  Claude model: claude-haiku-5-5
- 95 % intervals are Wilson score intervals; with n < 50 they are wide — quote them, not just the point value.

## Headline results

| Engine | Hint | n ok/total | Type acc. (95% CI) | Macro-F1 | Severity acc. (95% CI) | Critical recall | Critical missed | False-alarm rate | Conf. right / wrong | Latency p50 / p95 |
|---|---|---|---|---|---|---|---|---|---|---|
| ondevice_hsv | none | 24/24 | 46% (28–65%) | 0.41 | 33% (18–53%) | 41% (17 Critical) | 10 | 14% | 84% / 86% | 0 / 0 ms |
| ondevice_hsv | correct | 24/24 | 50% (31–69%) | 0.40 | 33% (18–53%) | 47% (17 Critical) | 9 | 29% | 88% / 83% | 0 / 1 ms |
| ondevice_hsv | wrong | 24/24 | 42% (24–61%) | 0.36 | 38% (21–57%) | 53% (17 Critical) | 8 | 29% | 85% / 83% | 0 / 0 ms |
| server_heuristic | none | 24/24 | 33% (18–53%) | 0.30 | 29% (15–49%) | 41% (17 Critical) | 10 | 29% | 90% / 86% | 8 / 12 ms |
| server_heuristic | correct | 24/24 | 58% (39–76%) | 0.53 | 38% (21–57%) | 53% (17 Critical) | 8 | 29% | 85% / 86% | 8 / 13 ms |
| server_heuristic | wrong | 24/24 | 25% (12–45%) | 0.22 | 38% (21–57%) | 53% (17 Critical) | 8 | 57% | 91% / 84% | 8 / 12 ms |
| claude | none | 24/24 | 79% (60–91%) | 0.79 | 71% (51–85%) | 82% (17 Critical) | 3 | 14% | 90% / 71% | 1366 / 1906 ms |
| claude | correct | 24/24 | 83% (64–93%) | 0.84 | 75% (55–88%) | 82% (17 Critical) | 3 | 14% | 90% / 74% | 1335 / 2026 ms |
| claude | wrong | 24/24 | 83% (64–93%) | 0.84 | 75% (55–88%) | 88% (17 Critical) | 2 | 14% | 90% / 76% | 1297 / 2090 ms |

*Critical recall* = share of truly Critical photos that were labelled Critical (a miss is the safety-relevant error). *False-alarm rate* = non-Critical photos labelled Critical. *Conf. right / wrong* = mean self-reported confidence when the type was right vs wrong — if they are close, confidence is not informative.

## Per-class results (hint = none)

**ondevice_hsv**

| Class | Precision | Recall | F1 | Support |
|---|---|---|---|---|
| Flash Flood | 0.25 | 1.00 | 0.40 | 4 |
| Blocked Drain | 0.75 | 0.75 | 0.75 | 4 |
| Fallen Tree | 1.00 | 0.80 | 0.89 | 5 |
| Slope / Landslide | 0.00 | 0.00 | 0.00 | 5 |
| None | 0.00 | 0.00 | 0.00 | 6 |

**server_heuristic**

| Class | Precision | Recall | F1 | Support |
|---|---|---|---|---|
| Flash Flood | 0.21 | 1.00 | 0.35 | 4 |
| Blocked Drain | 0.67 | 0.50 | 0.57 | 4 |
| Fallen Tree | 1.00 | 0.40 | 0.57 | 5 |
| Slope / Landslide | 0.00 | 0.00 | 0.00 | 5 |
| None | 0.00 | 0.00 | 0.00 | 6 |

**claude**

| Class | Precision | Recall | F1 | Support |
|---|---|---|---|---|
| Flash Flood | 1.00 | 0.75 | 0.86 | 4 |
| Blocked Drain | 0.44 | 1.00 | 0.62 | 4 |
| Fallen Tree | 1.00 | 1.00 | 1.00 | 5 |
| Slope / Landslide | 1.00 | 1.00 | 1.00 | 5 |
| None | 1.00 | 0.33 | 0.50 | 6 |

## Confusion matrices (rows = truth, columns = predicted; hint = none)

**ondevice_hsv**

| truth \ pred | Flash | Blocke | Fallen | Slope | None |
|---|---|---|---|---|---|
| Flash Flood | 4 | 0 | 0 | 0 | 0 |
| Blocked Drain | 1 | 3 | 0 | 0 | 0 |
| Fallen Tree | 1 | 0 | 4 | 0 | 0 |
| Slope / Landslide | 4 | 1 | 0 | 0 | 0 |
| None | 6 | 0 | 0 | 0 | 0 |

**server_heuristic**

| truth \ pred | Flash | Blocke | Fallen | Slope | None |
|---|---|---|---|---|---|
| Flash Flood | 4 | 0 | 0 | 0 | 0 |
| Blocked Drain | 2 | 2 | 0 | 0 | 0 |
| Fallen Tree | 3 | 0 | 2 | 0 | 0 |
| Slope / Landslide | 4 | 1 | 0 | 0 | 0 |
| None | 6 | 0 | 0 | 0 | 0 |

**claude**

| truth \ pred | Flash | Blocke | Fallen | Slope | None |
|---|---|---|---|---|---|
| Flash Flood | 3 | 1 | 0 | 0 | 0 |
| Blocked Drain | 0 | 4 | 0 | 0 | 0 |
| Fallen Tree | 0 | 0 | 5 | 0 | 0 |
| Slope / Landslide | 0 | 0 | 0 | 5 | 0 |
| None | 0 | 4 | 0 | 0 | 2 |

## Sensitivity to the user's category choice

| Engine | Type acc. (none) | Type acc. (correct) | Type acc. (wrong) | Severity acc. (none) | Severity acc. (correct) | Severity acc. (wrong) |
|---|---|---|---|---|---|---|
| ondevice_hsv | 46% | 50% | 42% | 33% | 33% | 38% |
| server_heuristic | 33% | 58% | 25% | 29% | 38% | 38% |
| claude | 79% | 83% | 83% | 71% | 75% | 75% |

A large drop from `correct` to `wrong` means the engine mostly echoes the user's choice instead of reading the photo.

## Paired comparison: on-device vs Claude (type correct?)

| Hint | both right | only Claude right | only on-device right | both wrong |
|---|---|---|---|---|
| none | 10 | 9 | 1 | 4 |
| correct | 11 | 9 | 1 | 3 |
| wrong | 9 | 11 | 1 | 3 |

## Failure cases (type or severity wrong) — use these for the “handling incorrect outputs” discussion

**ondevice_hsv / hint=none** — 20 wrong, 0 no answer

- `um_drain_02.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.83). water=0.20 green=0.01 dark=0.49
- `um_drain_04.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.84). water=0.26 green=0.16 dark=0.30
- `um_drain_06.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.83). water=0.22 green=0.11 dark=0.57
- `um_drain_08.jpg`: truth Blocked Drain/Critical → predicted Flash Flood/Medium (conf 0.86). water=0.32 green=0.05 dark=0.24
- `um_ctrl_02.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.83). water=0.24 green=0.16 dark=0.30
- `um_ctrl_04.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.85). water=0.28 green=0.17 dark=0.27
- `um_ctrl_06.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.81). water=0.22 green=0.24 dark=0.11
- `um_ctrl_08.jpg`: truth None/Low → predicted Flash Flood/Critical (conf 0.96). water=0.51 green=0.05 dark=0.14
- `um_ctrl_10.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.81). water=0.23 green=0.03 dark=0.18
- `um_ctrl_12.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.83). water=0.26 green=0.12 dark=0.23
- `um_tree_02.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.81). water=0.21 green=0.29 dark=0.27
- `um_tree_04.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.82). water=0.24 green=0.49 dark=0.09
- `um_tree_06.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.75). water=0.02 green=0.36 dark=0.24
- `um_tree_10.jpg`: truth Fallen Tree/Critical → predicted Flash Flood/Medium (conf 0.82). water=0.24 green=0.10 dark=0.09
- `um_flood_08.jpg`: truth Flash Flood/Critical → predicted Flash Flood/Medium (conf 0.86). water=0.28 green=0.05 dark=0.22

**ondevice_hsv / hint=correct** — 18 wrong, 0 no answer

- `um_drain_04.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.86). water=0.26 green=0.16 dark=0.30
- `um_drain_08.jpg`: truth Blocked Drain/Critical → predicted Flash Flood/Medium (conf 0.83). water=0.32 green=0.05 dark=0.24
- `um_ctrl_02.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.83). water=0.24 green=0.16 dark=0.30
- `um_ctrl_04.jpg`: truth None/Low → predicted Fallen Tree/Medium (conf 0.79). water=0.28 green=0.17 dark=0.27
- `um_ctrl_06.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.81). water=0.22 green=0.24 dark=0.11
- `um_ctrl_08.jpg`: truth None/Low → predicted Flash Flood/Critical (conf 0.96). water=0.51 green=0.05 dark=0.14
- `um_ctrl_10.jpg`: truth None/Low → predicted Fallen Tree/Medium (conf 0.73). water=0.23 green=0.03 dark=0.18
- `um_ctrl_12.jpg`: truth None/Low → predicted Blocked Drain/Medium (conf 0.81). water=0.26 green=0.12 dark=0.23
- `um_tree_02.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.84). water=0.21 green=0.29 dark=0.27
- `um_tree_06.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.86). water=0.02 green=0.36 dark=0.24
- `um_tree_08.jpg`: truth Fallen Tree/Medium → predicted Fallen Tree/Critical (conf 0.90). water=0.03 green=0.46 dark=0.42
- `um_tree_10.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.76). water=0.24 green=0.10 dark=0.09
- `um_flood_08.jpg`: truth Flash Flood/Critical → predicted Flash Flood/Medium (conf 0.86). water=0.28 green=0.05 dark=0.22
- `um_slope_02.jpg`: truth Slope / Landslide/Critical → predicted Flash Flood/Medium (conf 0.78). water=0.35 green=0.06 dark=0.05
- `um_slope_04.jpg`: truth Slope / Landslide/Critical → predicted Blocked Drain/Critical (conf 0.85). water=0.30 green=0.21 dark=0.36

**server_heuristic / hint=none** — 19 wrong, 0 no answer

- `um_drain_04.jpg`: truth Blocked Drain/Critical → predicted Flash Flood/Medium (conf 0.84). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=15%).
- `um_drain_08.jpg`: truth Blocked Drain/Critical → predicted Flash Flood/Medium (conf 0.83). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=9%).
- `um_ctrl_02.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.83). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=12%).
- `um_ctrl_04.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.84). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=15%).
- `um_ctrl_06.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.83). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=12%).
- `um_ctrl_08.jpg`: truth None/Low → predicted Flash Flood/Critical (conf 0.96). [FastAPI Heuristic Vision Triage] Detected turbid surface water ponding (water-tone=52%).
- `um_ctrl_10.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.81). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=4%).
- `um_ctrl_12.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.86). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=19%).
- `um_tree_02.jpg`: truth Fallen Tree/Critical → predicted Flash Flood/Medium (conf 0.83). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=10%).
- `um_tree_06.jpg`: truth Fallen Tree/Critical → predicted Flash Flood/Medium (conf 0.80). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=1%).
- `um_tree_08.jpg`: truth Fallen Tree/Medium → predicted Fallen Tree/Critical (conf 0.90). [FastAPI Heuristic Vision Triage] Detected canopy/branch obstruction (foliage=34%, shadow=39%).
- `um_tree_10.jpg`: truth Fallen Tree/Critical → predicted Flash Flood/Medium (conf 0.88). [FastAPI Heuristic Vision Triage] Detected turbid surface water ponding (water-tone=26%).
- `um_flood_04.jpg`: truth Flash Flood/Critical → predicted Flash Flood/Medium (conf 0.85). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=18%).
- `um_flood_08.jpg`: truth Flash Flood/Critical → predicted Flash Flood/Medium (conf 0.85). [FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone=16%).
- `um_slope_02.jpg`: truth Slope / Landslide/Critical → predicted Flash Flood/Critical (conf 0.90). [FastAPI Heuristic Vision Triage] Detected turbid surface water ponding (water-tone=34%).

**server_heuristic / hint=correct** — 17 wrong, 0 no answer

- `um_drain_04.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.81). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Blocked Drain' (water=15%, foliage=9%, dark=18%).
- `um_drain_08.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.80). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Blocked Drain' (water=9%, foliage=1%, dark=15%).
- `um_ctrl_02.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.81). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Flash Flood' (water=12%, foliage=8%, dark=18%).
- `um_ctrl_04.jpg`: truth None/Low → predicted Fallen Tree/Medium (conf 0.80). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Fallen Tree' (water=15%, foliage=13%, dark=12%).
- `um_ctrl_06.jpg`: truth None/Low → predicted Flash Flood/Medium (conf 0.80). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Flash Flood' (water=12%, foliage=14%, dark=2%).
- `um_ctrl_08.jpg`: truth None/Low → predicted Flash Flood/Critical (conf 0.96). [FastAPI Heuristic Vision Triage] Detected turbid surface water ponding (water-tone=52%).
- `um_ctrl_10.jpg`: truth None/Low → predicted Fallen Tree/Medium (conf 0.80). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Fallen Tree' (water=4%, foliage=0%, dark=12%).
- `um_ctrl_12.jpg`: truth None/Low → predicted Blocked Drain/Medium (conf 0.82). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Blocked Drain' (water=19%, foliage=6%, dark=22%).
- `um_tree_02.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.81). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Fallen Tree' (water=10%, foliage=10%, dark=16%).
- `um_tree_06.jpg`: truth Fallen Tree/Critical → predicted Fallen Tree/Medium (conf 0.80). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Fallen Tree' (water=1%, foliage=12%, dark=14%).
- `um_tree_08.jpg`: truth Fallen Tree/Medium → predicted Fallen Tree/Critical (conf 0.90). [FastAPI Heuristic Vision Triage] Detected canopy/branch obstruction (foliage=34%, shadow=39%).
- `um_tree_10.jpg`: truth Fallen Tree/Critical → predicted Flash Flood/Medium (conf 0.88). [FastAPI Heuristic Vision Triage] Detected turbid surface water ponding (water-tone=26%).
- `um_flood_04.jpg`: truth Flash Flood/Critical → predicted Flash Flood/Medium (conf 0.81). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Flash Flood' (water=18%, foliage=5%, dark=17%).
- `um_flood_08.jpg`: truth Flash Flood/Critical → predicted Flash Flood/Medium (conf 0.81). [FastAPI Heuristic Vision Triage] Verified with user category hint 'Flash Flood' (water=16%, foliage=2%, dark=18%).
- `um_slope_02.jpg`: truth Slope / Landslide/Critical → predicted Flash Flood/Critical (conf 0.90). [FastAPI Heuristic Vision Triage] Detected turbid surface water ponding (water-tone=34%).

**claude / hint=none** — 8 wrong, 0 no answer

- `um_drain_04.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.85). [Claude Vision Triage] A concrete drainage channel alongside a building is partly clogged with leaves and debris, with standing water backin
- `um_drain_06.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.85). [Claude Vision Triage] A concrete drainage channel alongside the path is packed with dry leaves, twigs and debris, which could impede water 
- `um_ctrl_02.jpg`: truth None/Low → predicted Blocked Drain/Medium (conf 0.60). [Claude Vision Triage] Wet road with scattered leaves and debris collecting along the curb and gutter, suggesting a partially clogged drain,
- `um_ctrl_04.jpg`: truth None/Low → predicted Blocked Drain/Medium (conf 0.70). [Claude Vision Triage] A drain grate along the road edge has leaves and debris on it with water pooling beside the curb, suggesting partial 
- `um_ctrl_06.jpg`: truth None/Low → predicted Blocked Drain/Medium (conf 0.85). [Claude Vision Triage] A drain grate sits beside the roadside with a cracked concrete edge and a gap beneath it, with leaves and debris coll
- `um_ctrl_08.jpg`: truth None/Low → predicted Blocked Drain/Low (conf 0.60). [Claude Vision Triage] A narrow concrete drain channel shows a thin flow of water with some debris and leaves at the inlet, but the water is
- `um_tree_08.jpg`: truth Fallen Tree/Medium → predicted Fallen Tree/Critical (conf 0.95). [Claude Vision Triage] A large tree trunk with branches has fallen across the paved path, fully blocking the walkway.
- `um_flood_06.jpg`: truth Flash Flood/Critical → predicted Blocked Drain/Medium (conf 0.80). [Claude Vision Triage] A drain on the left edge is clogged with leaves and debris, and water is pooling across the road surface on the right

**claude / hint=correct** — 7 wrong, 0 no answer

- `um_drain_04.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.85). [Claude Vision Triage] A concrete drainage channel alongside the building is filled with wet leaves and debris, with standing water backing 
- `um_drain_06.jpg`: truth Blocked Drain/Critical → predicted Blocked Drain/Medium (conf 0.85). [Claude Vision Triage] The concrete drain channel alongside the walkway is packed with dry leaves, twigs and debris, obstructing water flow,
- `um_ctrl_02.jpg`: truth None/Low → predicted Blocked Drain/Medium (conf 0.70). [Claude Vision Triage] Wet road with leaves and debris along the gutter and yellow line, with shallow surface water but no deep or fast-movi
- `um_ctrl_06.jpg`: truth None/Low → predicted Blocked Drain/Medium (conf 0.85). [Claude Vision Triage] A rusted drain grate sits over a cracked concrete channel with leaves, debris and vegetation partly obstructing the f
- `um_ctrl_08.jpg`: truth None/Low → predicted Blocked Drain/Low (conf 0.60). [Claude Vision Triage] A narrow stone-lined drain channel carries only a thin trickle of water with leaf litter and some debris near the cul
- `um_tree_08.jpg`: truth Fallen Tree/Medium → predicted Fallen Tree/Critical (conf 0.95). [Claude Vision Triage] A large tree trunk has fallen across the paved path, fully blocking the walkway.
- `um_flood_06.jpg`: truth Flash Flood/Critical → predicted Blocked Drain/Medium (conf 0.82). [Claude Vision Triage] A roadside drain on the left is clogged with leaves and debris, and water is pooling across the adjacent road surface

## Limitations to state honestly

- Small, single-campus photo set; results do not generalise to other weather, lighting or camera conditions.
- `ondevice_hsv` is a Python port of the Java code; confirm parity on a few photos in the app.
- Ground-truth labels come from the team (not from JPPHB engineers); severity labelling is subjective.
- Do not tune thresholds on the `test` split; tune on `dev` and report `test` once.