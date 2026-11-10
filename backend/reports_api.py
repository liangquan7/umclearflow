"""UM-ClearFlow report sync API (shared hazard reports, votes, admin status, photos).

Storage : SQLite file (stdlib only) + a photo folder. Good for a demo / a few hundred users.
Identity: there are NO real accounts. A random per-install `device_id` is used to stop one device
          voting twice. It is spoofable - do not treat it as authentication.
Admin   : POST /admin/login with the staff PIN returns a short-lived bearer token (kept in memory).

Environment variables
  CLEARFLOW_DEMO=1        demo mode: default admin PIN "UM2026", /admin/reset enabled,
                          repeated "demo" resolve votes from one device allowed
  ADMIN_PIN=...           staff PIN (required when not in demo mode, otherwise admin login is disabled)
  CLEARFLOW_DB, CLEARFLOW_PHOTOS   storage locations
  MAX_REPORTS_PER_DEVICE_HOUR      create rate limit per device (default 30)
"""
import hmac
import math
import os
import re
import secrets
import shutil
import sqlite3
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/v1", tags=["reports"])

DB_PATH = os.getenv("CLEARFLOW_DB", str(Path(__file__).with_name("clearflow.db")))
PHOTO_DIR = Path(os.getenv("CLEARFLOW_PHOTOS", str(Path(__file__).with_name("photos"))))
DEMO_MODE = os.getenv("CLEARFLOW_DEMO", "0") == "1"
ADMIN_PIN = os.getenv("ADMIN_PIN") or ("UM2026" if DEMO_MODE else "")
MAX_CREATES_PER_HOUR = int(os.getenv("MAX_REPORTS_PER_DEVICE_HOUR", "30"))

VALID_HAZARD_TYPES = {"Flash Flood", "Blocked Drain", "Fallen Tree", "Slope / Landslide"}
VALID_SEVERITIES = {"Low", "Medium", "Critical"}
VALID_STATUSES = {"Submitted", "Verified", "In Progress - JPPHB", "Resolved"}
MERGE_RADIUS_M = 50.0
RESOLVE_VOTES_NEEDED = 3
MAX_PHOTO_BYTES = 8 * 1024 * 1024

ID_RE = re.compile(r"^[A-Za-z0-9-]{3,40}$")
EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")

_lock = threading.Lock()
_admin_tokens = {}   # token -> expiry epoch seconds
_login_fails = {}    # client ip -> [timestamps]


# ----------------------------------------------------------------------------- storage

def _init_db() -> None:
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DB_PATH) as c:
        c.execute(
            "CREATE TABLE IF NOT EXISTS reports ("
            "id TEXT PRIMARY KEY, title TEXT, description TEXT, location_name TEXT,"
            "hazard_type TEXT, severity TEXT, status TEXT, latitude REAL, longitude REAL,"
            "timestamp TEXT, upvotes INTEGER, resolve_votes INTEGER, ai_confidence REAL,"
            "ai_triage_note TEXT, reporter_email TEXT, timeline TEXT, device_id TEXT,"
            "created_at INTEGER, updated_at INTEGER, has_photo INTEGER DEFAULT 0)"
        )
        c.execute(
            "CREATE TABLE IF NOT EXISTS votes ("
            "report_id TEXT, device_id TEXT, vote_type TEXT,"
            "PRIMARY KEY (report_id, device_id, vote_type))"
        )
        c.execute("CREATE INDEX IF NOT EXISTS idx_reports_created ON reports(created_at DESC)")


_init_db()


@contextmanager
def _db():
    """One short transaction per request, serialised by a process-wide lock."""
    with _lock:
        conn = sqlite3.connect(DB_PATH, timeout=10, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()


# ----------------------------------------------------------------------------- helpers

def _now_ms() -> int:
    return int(time.time() * 1000)


def _hhmm() -> str:
    return datetime.now(timezone(timedelta(hours=8))).strftime("%H:%M")  # Malaysia time


def _haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6371000.0
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lon / 2) ** 2
    return r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def _mask_email(email: str) -> str:
    if "@" not in email:
        return ""
    local, domain = email.split("@", 1)
    return local[:1] + "***@" + domain


def _mask_emails_in_text(text: str) -> str:
    return EMAIL_RE.sub(lambda m: _mask_email(m.group(0)), text or "")


def _check_id(report_id: str) -> None:
    if not ID_RE.match(report_id or ""):
        raise HTTPException(status_code=422, detail="Invalid report id.")


def _get_or_404(db, report_id: str):
    _check_id(report_id)
    row = db.execute("SELECT * FROM reports WHERE id=?", (report_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Report not found.")
    return row


def _add_vote(db, report_id: str, device_id: str, vote_type: str) -> bool:
    cur = db.execute(
        "INSERT OR IGNORE INTO votes (report_id, device_id, vote_type) VALUES (?,?,?)",
        (report_id, device_id, vote_type),
    )
    return cur.rowcount == 1


def _my_flags(db, report_id: str, device_id: str):
    if not device_id:
        return False, False
    rows = db.execute(
        "SELECT vote_type FROM votes WHERE report_id=? AND device_id=?", (report_id, device_id)
    ).fetchall()
    kinds = {r["vote_type"] for r in rows}
    return "UPVOTE" in kinds, "RESOLVE" in kinds


def _report_json(row, device_id: str = "", my_up: bool = False, my_res: bool = False) -> dict:
    mine = bool(device_id) and row["device_id"] == device_id
    return {
        "id": row["id"],
        "title": row["title"],
        "description": row["description"],
        "location_name": row["location_name"],
        "hazard_type": row["hazard_type"],
        "severity": row["severity"],
        "status": row["status"],
        "latitude": row["latitude"],
        "longitude": row["longitude"],
        "timestamp": row["timestamp"],
        "upvotes": row["upvotes"],
        "resolve_votes": row["resolve_votes"],
        "ai_confidence": row["ai_confidence"],
        "ai_triage_note": row["ai_triage_note"],
        # Other people's e-mail addresses are personal data (PDPA): only the creating device sees its own.
        "reporter_email": row["reporter_email"] if mine else _mask_email(row["reporter_email"] or ""),
        "timeline": row["timeline"] if mine else _mask_emails_in_text(row["timeline"]),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "has_photo": bool(row["has_photo"]),
        "is_mine": mine,
        "my_upvote": my_up,
        "my_resolve": my_res,
    }


def _single(db, row, device_id: str) -> dict:
    up, res = _my_flags(db, row["id"], device_id)
    return _report_json(row, device_id, up, res)


def _require_admin(authorization: Optional[str]) -> None:
    token = ""
    if authorization and authorization.startswith("Bearer "):
        token = authorization[7:].strip()
    now = time.time()
    for t in [t for t, exp in _admin_tokens.items() if exp < now]:
        _admin_tokens.pop(t, None)
    if not token or token not in _admin_tokens:
        raise HTTPException(status_code=401, detail="Admin token missing or expired.")


# ----------------------------------------------------------------------------- models

class ReportIn(BaseModel):
    id: str = Field(min_length=3, max_length=40)
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default="", max_length=1000)
    location_name: str = Field(default="", max_length=200)
    hazard_type: str
    severity: str
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    timestamp: str = Field(default="", max_length=40)
    ai_confidence: float = Field(default=0.0, ge=0, le=1)
    ai_triage_note: str = Field(default="", max_length=500)
    reporter_email: str = Field(default="", max_length=120)
    timeline: str = Field(default="", max_length=2000)
    device_id: str = Field(min_length=8, max_length=64)


class VoteIn(BaseModel):
    device_id: str = Field(min_length=8, max_length=64)
    demo: bool = False


class StatusIn(BaseModel):
    status: str


class LoginIn(BaseModel):
    pin: str = Field(max_length=64)


# ----------------------------------------------------------------------------- reports

@router.get("/reports")
def list_reports(device_id: str = Query("", max_length=64), limit: int = Query(200, ge=1, le=500)):
    with _db() as db:
        rows = db.execute("SELECT * FROM reports ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
        ups, ress = set(), set()
        if device_id:
            for v in db.execute("SELECT report_id, vote_type FROM votes WHERE device_id=?", (device_id,)):
                (ups if v["vote_type"] == "UPVOTE" else ress).add(v["report_id"])
        return {
            "reports": [_report_json(r, device_id, r["id"] in ups, r["id"] in ress) for r in rows],
            "server_time": _now_ms(),
        }


@router.post("/reports")
def create_report(r: ReportIn):
    _check_id(r.id)
    if r.hazard_type not in VALID_HAZARD_TYPES:
        raise HTTPException(status_code=422, detail="Unknown hazard_type.")
    if r.severity not in VALID_SEVERITIES:
        raise HTTPException(status_code=422, detail="Unknown severity.")

    with _db() as db:
        existing = db.execute("SELECT * FROM reports WHERE id=?", (r.id,)).fetchone()
        if existing is not None:  # idempotent retry from the app's outbox
            return {"report": _single(db, existing, r.device_id), "merged_into": None, "created": False}

        recent = db.execute(
            "SELECT COUNT(*) FROM reports WHERE device_id=? AND created_at>?",
            (r.device_id, _now_ms() - 3_600_000),
        ).fetchone()[0]
        if recent >= MAX_CREATES_PER_HOUR:
            raise HTTPException(status_code=429, detail="Too many reports from this device. Try again later.")

        # Server-side duplicate merge: same type, still unresolved, within 50 m
        candidates = db.execute(
            "SELECT * FROM reports WHERE status != 'Resolved' AND hazard_type=?", (r.hazard_type,)
        ).fetchall()
        for c in candidates:
            if _haversine_m(c["latitude"], c["longitude"], r.latitude, r.longitude) <= MERGE_RADIUS_M:
                counted = _add_vote(db, c["id"], r.device_id, "UPVOTE")
                escalated = c["severity"] != "Critical" and r.severity == "Critical"
                line = (f"\n{_hhmm()} • Merged nearby duplicate report (<50m, Upvotes +{1 if counted else 0}"
                        f"{', escalated to Critical' if escalated else ''})")
                db.execute(
                    "UPDATE reports SET upvotes=upvotes+?, severity=?, timeline=?, updated_at=? WHERE id=?",
                    (1 if counted else 0, "Critical" if escalated else c["severity"],
                     (c["timeline"] or "") + line, _now_ms(), c["id"]),
                )
                merged = db.execute("SELECT * FROM reports WHERE id=?", (c["id"],)).fetchone()
                return {"report": _single(db, merged, r.device_id), "merged_into": c["id"], "created": False}

        now = _now_ms()
        timeline = r.timeline or f"{_hhmm()} • Submitted"
        db.execute(
            "INSERT INTO reports (id,title,description,location_name,hazard_type,severity,status,latitude,longitude,"
            "timestamp,upvotes,resolve_votes,ai_confidence,ai_triage_note,reporter_email,timeline,device_id,"
            "created_at,updated_at,has_photo) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
            (r.id, r.title, r.description, r.location_name, r.hazard_type, r.severity, "Submitted",
             r.latitude, r.longitude, r.timestamp, 1, 0, r.ai_confidence, r.ai_triage_note,
             r.reporter_email, timeline, r.device_id, now, now),
        )
        _add_vote(db, r.id, r.device_id, "UPVOTE")
        row = db.execute("SELECT * FROM reports WHERE id=?", (r.id,)).fetchone()
        return {"report": _single(db, row, r.device_id), "merged_into": None, "created": True}


@router.post("/reports/{report_id}/upvote")
def upvote(report_id: str, body: VoteIn):
    with _db() as db:
        row = _get_or_404(db, report_id)
        if _add_vote(db, report_id, body.device_id, "UPVOTE"):
            db.execute("UPDATE reports SET upvotes=upvotes+1, updated_at=? WHERE id=?", (_now_ms(), report_id))
        row = db.execute("SELECT * FROM reports WHERE id=?", (report_id,)).fetchone()
        return {"report": _single(db, row, body.device_id)}


@router.post("/reports/{report_id}/resolve-vote")
def resolve_vote(report_id: str, body: VoteIn):
    with _db() as db:
        row = _get_or_404(db, report_id)
        if row["status"] != "Resolved":
            voter = body.device_id
            if body.demo and DEMO_MODE:  # lets one phone simulate several users in a demo
                voter = f"{body.device_id}#demo{secrets.token_hex(4)}"
            if _add_vote(db, report_id, voter, "RESOLVE"):
                votes = row["resolve_votes"] + 1
                if votes >= RESOLVE_VOTES_NEEDED:
                    status = "Resolved"
                    line = f"\n{_hhmm()} • Marked Resolved by Community Verification ({votes}/{RESOLVE_VOTES_NEEDED} votes)"
                else:
                    status = row["status"]
                    line = f"\n{_hhmm()} • Community Clear Vote ({votes}/{RESOLVE_VOTES_NEEDED})"
                db.execute(
                    "UPDATE reports SET resolve_votes=?, status=?, timeline=?, updated_at=? WHERE id=?",
                    (votes, status, (row["timeline"] or "") + line, _now_ms(), report_id),
                )
        row = db.execute("SELECT * FROM reports WHERE id=?", (report_id,)).fetchone()
        return {"report": _single(db, row, body.device_id)}


@router.post("/reports/{report_id}/status")
def set_status(report_id: str, body: StatusIn, authorization: Optional[str] = Header(None)):
    _require_admin(authorization)
    if body.status not in VALID_STATUSES:
        raise HTTPException(status_code=422, detail="Unknown status.")
    with _db() as db:
        row = _get_or_404(db, report_id)
        line = f"\n{_hhmm()} • Status updated to [{body.status}] by JPPHB Admin"
        db.execute(
            "UPDATE reports SET status=?, timeline=?, updated_at=? WHERE id=?",
            (body.status, (row["timeline"] or "") + line, _now_ms(), report_id),
        )
        row = db.execute("SELECT * FROM reports WHERE id=?", (report_id,)).fetchone()
        return {"report": _single(db, row, "")}


# ----------------------------------------------------------------------------- photos

def _normalise_jpeg(raw: bytes) -> Optional[bytes]:
    """Re-encode to <=1280px JPEG. This also strips EXIF metadata such as GPS tags."""
    try:
        from PIL import Image

        img = Image.open(BytesIO(raw)).convert("RGB")
        img.thumbnail((1280, 1280))
        buf = BytesIO()
        img.save(buf, "JPEG", quality=85)
        return buf.getvalue()
    except Exception:
        return None


@router.post("/reports/{report_id}/photo")
def upload_photo(report_id: str, image: UploadFile = File(...), device_id: str = Form(..., max_length=64)):
    if image.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=415, detail="Upload a JPEG, PNG, or WebP image.")
    raw = image.file.read(MAX_PHOTO_BYTES + 1)
    if len(raw) > MAX_PHOTO_BYTES:
        raise HTTPException(status_code=413, detail="Image must be 8 MB or smaller.")
    jpeg = _normalise_jpeg(raw)
    if jpeg is None:
        raise HTTPException(status_code=415, detail="Unreadable image.")
    with _db() as db:
        row = _get_or_404(db, report_id)
        if row["has_photo"]:
            raise HTTPException(status_code=409, detail="This report already has a photo.")
        (PHOTO_DIR / f"{report_id}.jpg").write_bytes(jpeg)
        db.execute("UPDATE reports SET has_photo=1, updated_at=? WHERE id=?", (_now_ms(), report_id))
    return {"ok": True}


@router.get("/reports/{report_id}/photo")
def get_photo(report_id: str):
    _check_id(report_id)
    path = PHOTO_DIR / f"{report_id}.jpg"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="No photo.")
    return FileResponse(path, media_type="image/jpeg")


# ----------------------------------------------------------------------------- admin

@router.post("/admin/login")
def admin_login(body: LoginIn, request: Request):
    if not ADMIN_PIN:
        raise HTTPException(status_code=503, detail="Admin login is not configured on this server.")
    ip = request.client.host if request.client else "unknown"
    now = time.time()
    fails = [t for t in _login_fails.get(ip, []) if now - t < 60]
    if len(fails) >= 5:
        raise HTTPException(status_code=429, detail="Too many wrong PINs. Try again in a minute.")
    if hmac.compare_digest(body.pin.encode("utf-8"), ADMIN_PIN.encode("utf-8")):
        _login_fails.pop(ip, None)
        token = secrets.token_urlsafe(24)
        _admin_tokens[token] = now + 2 * 3600
        return {"token": token, "expires_in": 2 * 3600}
    fails.append(now)
    _login_fails[ip] = fails
    raise HTTPException(status_code=401, detail="Invalid PIN.")


@router.post("/admin/reset")
def admin_reset(authorization: Optional[str] = Header(None)):
    """Demo helper: wipe all reports, votes and photos."""
    if not DEMO_MODE:
        raise HTTPException(status_code=403, detail="Reset is only available in demo mode.")
    _require_admin(authorization)
    with _db() as db:
        db.execute("DELETE FROM reports")
        db.execute("DELETE FROM votes")
    shutil.rmtree(PHOTO_DIR, ignore_errors=True)
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    return {"ok": True}
