"""Run:  pip install pytest httpx  &&  pytest -q   (from the backend folder)"""
import os
import tempfile

_tmp = tempfile.mkdtemp()
os.environ["CLEARFLOW_DB"] = os.path.join(_tmp, "t.db")
os.environ["CLEARFLOW_PHOTOS"] = os.path.join(_tmp, "photos")
os.environ["CLEARFLOW_DEMO"] = "1"
os.environ["MAX_REPORTS_PER_DEVICE_HOUR"] = "100"

from io import BytesIO  # noqa: E402

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from reports_api import router  # noqa: E402

app = FastAPI()
app.include_router(router)
client = TestClient(app)

DEV_A = "device-aaaaaaaa"
DEV_B = "device-bbbbbbbb"
DEV_C = "device-cccccccc"
DEV_D = "device-dddddddd"


def _report(rid, lat=3.1281, lon=101.6505, htype="Blocked Drain", sev="Medium", dev=DEV_A, email="ali@siswa.um.edu.my"):
    return {
        "id": rid, "title": "Drain blocked", "description": "d", "location_name": "FCSIT",
        "hazard_type": htype, "severity": sev, "latitude": lat, "longitude": lon,
        "timestamp": "10 Nov, 10:00", "ai_confidence": 0.8, "ai_triage_note": "n",
        "reporter_email": email, "timeline": f"10:00 • Submitted by {email}", "device_id": dev,
    }


def _admin_token():
    r = client.post("/api/v1/admin/login", json={"pin": "UM2026"})
    assert r.status_code == 200
    return r.json()["token"]


def test_create_is_idempotent_and_listed():
    r = client.post("/api/v1/reports", json=_report("CF-T1", lat=3.2000))
    assert r.status_code == 200 and r.json()["created"] is True
    again = client.post("/api/v1/reports", json=_report("CF-T1", lat=3.2000))
    assert again.status_code == 200 and again.json()["created"] is False
    ids = [x["id"] for x in client.get("/api/v1/reports", params={"device_id": DEV_A}).json()["reports"]]
    assert ids.count("CF-T1") == 1


def test_duplicate_within_50m_merges_and_escalates():
    client.post("/api/v1/reports", json=_report("CF-M1", lat=3.3000, dev=DEV_A))
    r = client.post("/api/v1/reports", json=_report("CF-M2", lat=3.30005, sev="Critical", dev=DEV_B))
    body = r.json()
    assert body["merged_into"] == "CF-M1" and body["created"] is False
    assert body["report"]["severity"] == "Critical" and body["report"]["upvotes"] == 2
    # far away -> not merged
    far = client.post("/api/v1/reports", json=_report("CF-M3", lat=3.3100, dev=DEV_B))
    assert far.json()["merged_into"] is None


def test_one_upvote_per_device():
    client.post("/api/v1/reports", json=_report("CF-U1", lat=3.4000, dev=DEV_A))
    first = client.post("/api/v1/reports/CF-U1/upvote", json={"device_id": DEV_B}).json()["report"]
    second = client.post("/api/v1/reports/CF-U1/upvote", json={"device_id": DEV_B}).json()["report"]
    assert first["upvotes"] == 2 and second["upvotes"] == 2 and second["my_upvote"] is True


def test_three_devices_resolve_report():
    client.post("/api/v1/reports", json=_report("CF-R1", lat=3.5000, dev=DEV_A))
    for dev in (DEV_A, DEV_B):
        rep = client.post("/api/v1/reports/CF-R1/resolve-vote", json={"device_id": dev}).json()["report"]
    assert rep["status"] != "Resolved" and rep["resolve_votes"] == 2
    # same device twice does not count
    rep = client.post("/api/v1/reports/CF-R1/resolve-vote", json={"device_id": DEV_B}).json()["report"]
    assert rep["resolve_votes"] == 2
    rep = client.post("/api/v1/reports/CF-R1/resolve-vote", json={"device_id": DEV_C}).json()["report"]
    assert rep["status"] == "Resolved" and rep["resolve_votes"] == 3


def test_demo_votes_allow_one_device_to_close_a_report():
    client.post("/api/v1/reports", json=_report("CF-D1", lat=3.6000, dev=DEV_A))
    rep = None
    for i in range(3):
        rep = client.post("/api/v1/reports/CF-D1/resolve-vote", json={"device_id": DEV_A, "demo": i > 0}).json()["report"]
    assert rep["status"] == "Resolved"


def test_status_requires_admin_token():
    client.post("/api/v1/reports", json=_report("CF-S1", lat=3.7000))
    assert client.post("/api/v1/reports/CF-S1/status", json={"status": "Verified"}).status_code == 401
    bad = client.post("/api/v1/reports/CF-S1/status", json={"status": "Verified"}, headers={"Authorization": "Bearer nope"})
    assert bad.status_code == 401
    ok = client.post("/api/v1/reports/CF-S1/status", json={"status": "In Progress - JPPHB"},
                     headers={"Authorization": f"Bearer {_admin_token()}"})
    assert ok.status_code == 200 and ok.json()["report"]["status"] == "In Progress - JPPHB"
    invalid = client.post("/api/v1/reports/CF-S1/status", json={"status": "Hacked"},
                          headers={"Authorization": f"Bearer {_admin_token()}"})
    assert invalid.status_code == 422


def test_wrong_pin_is_rejected_then_locked_out():
    codes = [client.post("/api/v1/admin/login", json={"pin": f"bad{i}"}).status_code for i in range(6)]
    assert codes[:5] == [401] * 5 and codes[5] == 429


def test_other_devices_do_not_see_email_addresses():
    client.post("/api/v1/reports", json=_report("CF-P1", lat=3.8000, dev=DEV_A, email="ali@siswa.um.edu.my"))
    mine = [r for r in client.get("/api/v1/reports", params={"device_id": DEV_A}).json()["reports"] if r["id"] == "CF-P1"][0]
    other = [r for r in client.get("/api/v1/reports", params={"device_id": DEV_B}).json()["reports"] if r["id"] == "CF-P1"][0]
    assert mine["reporter_email"] == "ali@siswa.um.edu.my"
    assert "ali@" not in other["reporter_email"] and "ali@" not in other["timeline"]
    assert other["reporter_email"].startswith("a***@")


def test_validation_and_rate_limit(monkeypatch):
    import reports_api
    monkeypatch.setattr(reports_api, "MAX_CREATES_PER_HOUR", 5)
    assert client.post("/api/v1/reports", json=_report("CF-V1", htype="Alien")).status_code == 422
    assert client.post("/api/v1/reports", json=_report("../etc", lat=3.9)).status_code == 422
    codes = [client.post("/api/v1/reports", json=_report(f"CF-RL{i}", lat=4.0 + i * 0.01, dev=DEV_D)).status_code
             for i in range(7)]
    assert codes[:5] == [200] * 5 and 429 in codes[5:]


def test_photo_upload_download_and_single_photo_rule():
    from PIL import Image

    client.post("/api/v1/reports", json=_report("CF-PH1", lat=4.5000))
    buf = BytesIO()
    Image.new("RGB", (2000, 1500), (120, 80, 40)).save(buf, "JPEG")
    files = {"image": ("p.jpg", buf.getvalue(), "image/jpeg")}
    assert client.post("/api/v1/reports/CF-PH1/photo", files=files, data={"device_id": DEV_A}).status_code == 200
    got = client.get("/api/v1/reports/CF-PH1/photo")
    assert got.status_code == 200 and Image.open(BytesIO(got.content)).size[0] <= 1280
    files = {"image": ("p.jpg", buf.getvalue(), "image/jpeg")}
    assert client.post("/api/v1/reports/CF-PH1/photo", files=files, data={"device_id": DEV_A}).status_code == 409
    assert client.get("/api/v1/reports/CF-NOPE/photo").status_code == 404
    bad = {"image": ("x.txt", b"hello", "text/plain")}
    assert client.post("/api/v1/reports/CF-PH1/photo", files=bad, data={"device_id": DEV_A}).status_code == 415
