from fastapi import FastAPI, UploadFile, File
from pydantic import BaseModel

app = FastAPI(title="UM-ClearFlow Backend API")

class ChatRequest(BaseModel):
    prompt: str
    rainfall_mm: float = 0.0
    forecast_3h_mm: float = 0.0

@app.post("/api/v1/chat")
async def chat_endpoint(req: ChatRequest):
    return {
        "reply": (
            f"[FastAPI Live Server] Received query: '{req.prompt}'. "
            f"Telemetry shows {req.rainfall_mm:.1f}mm current rain and {req.forecast_3h_mm:.1f}mm 3h forecast. "
            f"Recommended Shelter: Dewan Tunku Canselor (DTC) via KPS elevated walkway."
        )
    }

@app.post("/api/v1/triage")
async def triage_endpoint(image: UploadFile = File(...)):
    return {
        "hazard_type": "Flash Flood",
        "severity": "Critical",
        "confidence": 0.91,
        "note": f"FastAPI Vision Server processed {image.filename}"
    }