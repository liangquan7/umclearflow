package com.um.clearflow;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Plain HttpURLConnection client for the FastAPI report-sync endpoints. Every call blocks, so it must only be
 * used from a background thread. Never throws: network failure is reported as Response.code == -1.
 */
public final class SyncClient {

    public static final int NETWORK_ERROR = -1;
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;

    public static final class Response {
        public final int code;
        public final JSONObject json; // may be null (empty or non-JSON body)

        Response(int code, JSONObject json) {
            this.code = code;
            this.json = json;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    /** One report as returned by the server, plus per-device flags. */
    public static final class Remote {
        public final HazardReport report;
        public final boolean hasPhoto;
        public final boolean myUpvote;
        public final boolean myResolve;

        Remote(HazardReport report, boolean hasPhoto, boolean myUpvote, boolean myResolve) {
            this.report = report;
            this.hasPhoto = hasPhoto;
            this.myUpvote = myUpvote;
            this.myResolve = myResolve;
        }
    }

    private final String baseUrl;

    public SyncClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    // ------------------------------------------------------------------ requests

    public Response get(String path) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "GET");
            return read(conn);
        } catch (IOException e) {
            return new Response(NETWORK_ERROR, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public Response postJson(String path, JSONObject body, String bearerToken) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (bearerToken != null) conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            conn.setDoOutput(true);
            conn.getOutputStream().write((body != null ? body.toString() : "{}").getBytes(StandardCharsets.UTF_8));
            return read(conn);
        } catch (IOException e) {
            return new Response(NETWORK_ERROR, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public Response uploadPhoto(String path, File file, String deviceId) {
        HttpURLConnection conn = null;
        try {
            byte[] jpeg = readFile(file);
            String boundary = "----UMClearFlowSync" + System.currentTimeMillis();
            conn = open(path, "POST");
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setDoOutput(true);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"device_id\"\r\n\r\n");
                out.write(deviceId.getBytes(StandardCharsets.UTF_8));
                out.writeBytes("\r\n--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"hazard.jpg\"\r\n");
                out.writeBytes("Content-Type: image/jpeg\r\n\r\n");
                out.write(jpeg);
                out.writeBytes("\r\n--" + boundary + "--\r\n");
            }
            return read(conn);
        } catch (IOException e) {
            return new Response(NETWORK_ERROR, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Returns the bytes, or null if the photo does not exist or the request failed. */
    public byte[] download(String path) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "GET");
            conn.setReadTimeout(20000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_DOWNLOAD_BYTES) return null;
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Exchanges the staff PIN for a short-lived bearer token; null on any failure. */
    public String adminLogin(String pin) {
        try {
            Response r = postJson("api/v1/admin/login", new JSONObject().put("pin", pin), null);
            if (r.ok() && r.json != null && r.json.has("token")) return r.json.getString("token");
        } catch (JSONException ignored) {}
        return null;
    }

    // ------------------------------------------------------------------ JSON mapping

    public static JSONObject reportToJson(HazardReport r, String deviceId) throws JSONException {
        return new JSONObject()
                .put("id", r.id)
                .put("title", r.title)
                .put("description", r.description != null ? r.description : "")
                .put("location_name", r.locationName != null ? r.locationName : "")
                .put("hazard_type", r.hazardType)
                .put("severity", r.severity)
                .put("latitude", r.latitude)
                .put("longitude", r.longitude)
                .put("timestamp", r.timestamp != null ? r.timestamp : "")
                .put("ai_confidence", Math.max(0.0, Math.min(1.0, r.aiConfidence)))
                .put("ai_triage_note", r.aiTriageNote != null ? r.aiTriageNote : "")
                .put("reporter_email", r.reporterEmail != null ? r.reporterEmail : "")
                .put("timeline", r.timeline != null ? r.timeline : "")
                .put("device_id", deviceId);
    }

    public static Remote parseRemote(JSONObject o) {
        HazardReport r = new HazardReport(
                o.optString("id", ""),
                o.optString("title", ""),
                o.optString("description", ""),
                o.optString("location_name", ""),
                o.optString("hazard_type", "Flash Flood"),
                o.optString("severity", "Medium"),
                o.optString("status", "Submitted"),
                o.optDouble("latitude", 0.0),
                o.optDouble("longitude", 0.0),
                o.optString("timestamp", ""),
                o.optInt("upvotes", 0),
                o.optInt("resolve_votes", 0),
                o.optDouble("ai_confidence", 0.0),
                o.optString("ai_triage_note", ""),
                o.optString("reporter_email", ""),
                o.optString("timeline", ""),
                "");
        long created = o.optLong("created_at", 0L);
        if (created > 0) r.createdAt = created;
        return new Remote(r, o.optBoolean("has_photo", false),
                o.optBoolean("my_upvote", false), o.optBoolean("my_resolve", false));
    }

    // ------------------------------------------------------------------ internals

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(8000);
        return conn;
    }

    private static Response read(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (in != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                body = sb.toString();
            }
        }
        JSONObject json = null;
        if (!body.isEmpty()) {
            try {
                json = new JSONObject(body);
            } catch (JSONException ignored) {}
        }
        return new Response(code, json);
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
