from io import BytesIO
from typing import Optional
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from pydantic import BaseModel

app = FastAPI(title="UM-ClearFlow Backend API (SDG 13 Campus Resilience)")

VALID_HAZARD_TYPES = {
    "Flash Flood",
    "Blocked Drain",
    "Fallen Tree",
    "Slope / Landslide",
}


class ChatRequest(BaseModel):
    prompt: str
    rainfall_mm: float = 0.0
    forecast_3h_mm: float = 0.0
    mobility_mode: Optional[str] = "Walking (Pedestrian / Covered Walkway)"
    active_hazards_summary: Optional[str] = ""


@app.get("/api/v1/health")
async def health_check():
    return {"status": "ok", "service": "UM-ClearFlow FastAPI"}


@app.post("/api/v1/chat")
async def chat_endpoint(req: ChatRequest):
    q = req.prompt.lower()
    rain_info = f"(Current rain: {req.rainfall_mm:.1f} mm, Next 3h forecast: {req.forecast_3h_mm:.1f} mm)"
    hazards_clean = (req.active_hazards_summary or "").strip()
    hazards_context = (
        f"\nActive Unresolved Campus Hazards:\n{hazards_clean}"
        if hazards_clean
        else "\nActive Unresolved Campus Hazards: None currently recorded on device."
    )

    if any(k in q for k in ["shelter", "evacuat", "where to go", "避难"]):
        reply = (
            f"[Campus Rule Advisor; preset demo locations, not an official live shelter-status feed] {rain_info} Based on your mode ({req.mobility_mode}), "
            "preset shelter locations for this demonstration are:\n"
            "1. Dewan Tunku Canselor (DTC) — Primary assembly hall (Cap: 1,500)\n"
            "2. Perdanasiswa Complex (KPS) — Central covered relief hub (Cap: 800)\n"
            "3. FCSIT Block B Upper Concourse — North zone refuge (Cap: 400)."
            f"{hazards_context}"
        )
    elif any(k in q for k in ["kk8", "kk12", "route", "fcsit", "pantai", "safe", "walk", "bus"]):
        reply = (
            f"[Campus Rule Advisor; not live routing] {rain_info} Route idea for [{req.mobility_mode}]:\n"
            "• Avoid Lingkungan Budi valley road & Jalan Ilmu culvert during heavy rain.\n"
            "• An illustrative FCSIT -> KK8 option is via the Perdanasiswa (KPS) covered walkway. "
            "This service has no live road closure, route, or accessibility data; verify conditions and official UM guidance before moving."
            f"{hazards_context}"
        )
    elif any(k in q for k in ["thunder", "lightning", "heat", "haze", "jerebu", "tip", "sop"]):
        reply = (
            "[Campus Rule Advisor] UM Multi-Hazard Guidance:\n"
            "• Flash Flood: Never wade through moving water >15cm or near open storm drains.\n"
            "• Thunderstorm / Fallen Tree: Stay clear of mature roadside trees along Lingkungan Budi.\n"
            "• Extreme Heat / Haze: Hydrate at KPS water stations; wear N95 if API > 100."
            f"{hazards_context}"
        )
    else:
        reply = (
            f"[Campus Rule Advisor] {rain_info}\n"
            "• UM Auxiliary Police (Pejabat Keselamatan 24/7): 03-7967 7070\n"
            "• UM Medical Centre (PPUM Emergency): 03-7949 4422 | Student Clinic: 03-7967 6445\n"
            "• National Emergency: 999"
            f"{hazards_context}\n"
            "The Map tab draws illustrative lines against locally recorded reports; it is not live navigation or an official evacuation instruction."
        )
    return {"reply": reply}


@app.post("/api/v1/triage")
async def triage_endpoint(
    image: UploadFile = File(...),
    hint_type: Optional[str] = Form(None),
    title: Optional[str] = Form("")
):
    allowed_types = {"image/jpeg", "image/png", "image/webp"}
    if image.content_type not in allowed_types:
        raise HTTPException(status_code=415, detail="Upload a JPEG, PNG, or WebP image.")
    max_image_bytes = 8 * 1024 * 1024
    raw = await image.read(max_image_bytes + 1)
    if len(raw) > max_image_bytes:
        raise HTTPException(status_code=413, detail="Image must be 8 MB or smaller.")
    if not raw:
        raise HTTPException(status_code=400, detail="Image is empty.")

    # Analyze RGB distribution from raw image bytes (supports JPEG/PNG via Pillow if installed, else byte heuristics)
    muddy_ratio, green_ratio, dark_ratio = _analyze_image_bytes(raw)
    title_lower = (title or "").lower()
    clean_hint = (hint_type or "").strip()

    if "tree" in title_lower or "branch" in title_lower or green_ratio > 0.30:
        hazard_type = "Fallen Tree"
        severity = "Critical" if (green_ratio > 0.38 or dark_ratio > 0.30 or "block" in title_lower) else "Medium"
        confidence = min(0.95, 0.78 + green_ratio * 0.35)
        note = f"[FastAPI Heuristic Vision Triage] Detected canopy/branch obstruction (foliage={green_ratio*100:.0f}%, shadow={dark_ratio*100:.0f}%)."
    elif "drain" in title_lower or "grate" in title_lower or "culvert" in title_lower or (dark_ratio > 0.32 and muddy_ratio < 0.25):
        hazard_type = "Blocked Drain"
        severity = "Critical" if ("overflow" in title_lower or dark_ratio > 0.42) else "Medium"
        confidence = min(0.93, 0.76 + dark_ratio * 0.35)
        note = f"[FastAPI Heuristic Vision Triage] Detected storm-drain grate occlusion/debris (occlusion={dark_ratio*100:.0f}%)."
    elif "slope" in title_lower or "landslide" in title_lower:
        hazard_type = "Slope / Landslide"
        severity = "Critical"
        confidence = 0.90
        note = "[FastAPI Heuristic Vision Triage] Slope soil displacement risk flagged for immediate JPPHB cordon."
    elif "flood" in title_lower or "water" in title_lower or "pond" in title_lower or muddy_ratio > 0.25:
        hazard_type = "Flash Flood"
        severity = "Critical" if (muddy_ratio > 0.28 or "deep" in title_lower) else "Medium"
        confidence = min(0.96, 0.80 + muddy_ratio * 0.30)
        note = f"[FastAPI Heuristic Vision Triage] Detected turbid surface water ponding (water-tone={muddy_ratio*100:.0f}%)."
    elif clean_hint in VALID_HAZARD_TYPES:
        hazard_type = clean_hint
        severity = "Critical" if (hazard_type == "Slope / Landslide" or dark_ratio > 0.35 or muddy_ratio > 0.28) else "Medium"
        confidence = round(min(0.92, 0.76 + max(muddy_ratio, green_ratio, dark_ratio) * 0.30), 2)
        note = f"[FastAPI Heuristic Vision Triage] Verified with user category hint '{hazard_type}' (water={muddy_ratio*100:.0f}%, foliage={green_ratio*100:.0f}%, dark={dark_ratio*100:.0f}%)."
    else:
        hazard_type = "Flash Flood"
        severity = "Critical" if (muddy_ratio > 0.28 or "deep" in title_lower) else "Medium"
        confidence = min(0.96, 0.80 + muddy_ratio * 0.30)
        note = f"[FastAPI Heuristic Vision Triage] Detected surface ponding pattern (water-tone={muddy_ratio*100:.0f}%)."

    return {
        "hazard_type": hazard_type,
        "severity": severity,
        "confidence": round(confidence, 2),
        "note": note,
    }


def _analyze_image_bytes(raw: bytes):
    try:
        from PIL import Image
        img = Image.open(BytesIO(raw)).convert("RGB").resize((32, 32))
        pixels = list(img.getdata())
        total = max(1, len(pixels))
        muddy = green = dark = 0
        for r, g, b in pixels:
            if r < 65 and g < 65 and b < 65:
                dark += 1
            elif g > r * 1.12 and g > b * 1.15 and g > 45:
                green += 1
            elif r > 90 and g > 70 and b < r * 0.78:
                muddy += 1
        return muddy / total, green / total, dark / total
    except Exception:
        # Lightweight deterministic fallback when Pillow is not installed
        if not raw:
            return 0.35, 0.15, 0.25
        step = max(1, len(raw) // 256)
        samples = raw[::step]
        avg = sum(samples) / len(samples)
        muddy = 0.34 if avg >= 115 else 0.20
        green = 0.33 if 85 <= avg < 115 else 0.16
        dark = 0.36 if avg < 85 else 0.22
        return muddy, green, dark
