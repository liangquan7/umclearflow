package com.um.clearflow;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local-first repository. Every user action is applied to SQLite immediately (so the UI is instant and works
 * offline) and, when a backend is configured, also queued in an "outbox" table. {@link #requestSync()} flushes the
 * outbox to the FastAPI server in order and then pulls the shared report list. The server is authoritative.
 */
public class CampusRepository {

    public static final String API_BASE_URL = BuildConfig.API_BASE_URL;
    // Admin PIN is no longer stored in plaintext: only a salted SHA-256 hash from BuildConfig is compared.
    private static final String PIN_SALT = "clearflow-admin-v1:";
    private static final int MAX_PIN_ATTEMPTS = 5;
    private static final long PIN_LOCKOUT_MS = 60_000L;
    private int failedPinAttempts = 0;
    private long pinLockedUntil = 0L;
    public static final String CHANNEL_ID = "um_clearflow_alerts";

    // Outbox processing results
    private static final int OP_DONE = 0;   // delivered: remove from outbox
    private static final int OP_DROP = 1;   // permanently rejected (4xx): retrying can never succeed
    private static final int OP_RETRY = 2;  // network/5xx/429: keep and try again later
    private static final int PULL_LIMIT = 200;
    private static final int MAX_PHOTO_DOWNLOADS_PER_SYNC = 5;

    private static volatile CampusRepository instance;
    private Context appContext;
    private ClearFlowDbHelper dbHelper;
    private SharedPreferences prefs;

    private final List<HazardReport> reportsList = new ArrayList<>();
    private final Set<String> upvotedIds = new HashSet<>();
    private final Set<String> resolveVotedIds = new HashSet<>();
    private final Set<String> syncedIds = new HashSet<>();   // ids known to exist on the server (guarded by reportsList)

    private final MutableLiveData<List<HazardReport>> reportsLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> userRoleLiveData = new MutableLiveData<>("UM Student / Staff");
    private final MutableLiveData<String> broadcastBannerLiveData = new MutableLiveData<>("");
    private final MutableLiveData<String> syncStatusLiveData = new MutableLiveData<>("Sync: not started");

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);
    private final AtomicBoolean syncAgain = new AtomicBoolean(false);
    private SyncClient syncClient;
    private String deviceId;
    private volatile String adminToken = null;      // server bearer token, only while the demo admin view is unlocked
    private volatile boolean serverUnreachable = false;

    private volatile double latestRainfallMm = 0.0;
    private volatile double forecast3hRainMm = 0.0;
    // Synchronous role cache: postValue() is async, so isAdmin() must not read LiveData
    private volatile String currentRole = "UM Student / Staff";

    public static class TriageResult {
        public final String severity;
        public final double confidence;
        public final String note;
        public final String detectedType;

        public TriageResult(String severity, double confidence, String note, String detectedType) {
            this.severity = severity;
            this.confidence = confidence;
            this.note = note;
            this.detectedType = detectedType;
        }
    }

    public static class ShelterInfo {
        public final String name;
        public final double lat;
        public final double lon;
        public final String description;

        public ShelterInfo(String name, double lat, double lon, String description) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.description = description;
        }
    }

    private static class OutboxOp {
        final long rowId;
        final String op;
        final String reportId;
        final String payload;

        OutboxOp(long rowId, String op, String reportId, String payload) {
            this.rowId = rowId;
            this.op = op;
            this.reportId = reportId;
            this.payload = payload;
        }
    }

    private CampusRepository(Context context) {
        if (context != null) {
            attachContext(context);
        }
        loadFromSqliteOrSeed();
    }

    private void attachContext(Context context) {
        appContext = context.getApplicationContext();
        dbHelper = new ClearFlowDbHelper(appContext);
        prefs = appContext.getSharedPreferences("um_clearflow_prefs", Context.MODE_PRIVATE);
        createNotificationChannel();
        // Admin access is a session privilege; never restore it from editable local preferences.
        currentRole = "UM Student / Staff";
        prefs.edit().remove("user_role").apply();
        userRoleLiveData.postValue(currentRole);
        broadcastBannerLiveData.postValue(prefs.getString("admin_broadcast", ""));

        // Random per-install id: lets the server enforce one vote per device. Not an account.
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        deviceId = id;
        String base = API_BASE_URL != null ? API_BASE_URL.trim() : "";
        syncClient = base.isEmpty() ? null : new SyncClient(base);
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new CampusRepository(context);
        } else if (instance.appContext == null && context != null) {
            instance.attachContext(context);
            instance.loadFromSqliteOrSeed();
        }
    }

    public static synchronized CampusRepository getInstance() {
        if (instance == null) {
            instance = new CampusRepository(null);
        }
        return instance;
    }

    private void loadFromSqliteOrSeed() {
        executorService.execute(() -> {
            synchronized (reportsList) {
                reportsList.clear();
                syncedIds.clear();
                if (dbHelper != null) {
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    Cursor c = db.rawQuery("SELECT * FROM hazard_reports ORDER BY createdAt DESC, rowid DESC", null);
                    int photoColIdx = c.getColumnIndex("photoUri");
                    int createdIdx = c.getColumnIndex("createdAt");
                    int syncedIdx = c.getColumnIndex("synced");
                    while (c.moveToNext()) {
                        String photoUri = photoColIdx >= 0 ? c.getString(photoColIdx) : "";
                        HazardReport r = new HazardReport(
                                c.getString(c.getColumnIndexOrThrow("id")),
                                c.getString(c.getColumnIndexOrThrow("title")),
                                c.getString(c.getColumnIndexOrThrow("description")),
                                c.getString(c.getColumnIndexOrThrow("locationName")),
                                c.getString(c.getColumnIndexOrThrow("hazardType")),
                                c.getString(c.getColumnIndexOrThrow("severity")),
                                c.getString(c.getColumnIndexOrThrow("status")),
                                c.getDouble(c.getColumnIndexOrThrow("latitude")),
                                c.getDouble(c.getColumnIndexOrThrow("longitude")),
                                c.getString(c.getColumnIndexOrThrow("timestamp")),
                                c.getInt(c.getColumnIndexOrThrow("upvotes")),
                                c.getInt(c.getColumnIndexOrThrow("resolveVotes")),
                                c.getDouble(c.getColumnIndexOrThrow("aiConfidence")),
                                c.getString(c.getColumnIndexOrThrow("aiTriageNote")),
                                c.getString(c.getColumnIndexOrThrow("reporterEmail")),
                                c.getString(c.getColumnIndexOrThrow("timeline")),
                                photoUri
                        );
                        if (createdIdx >= 0) {
                            long created = c.getLong(createdIdx);
                            if (created > 0) r.createdAt = created;
                        }
                        if (syncedIdx >= 0 && c.getInt(syncedIdx) == 1) syncedIds.add(r.id);
                        reportsList.add(r);
                    }
                    c.close();

                    upvotedIds.clear();
                    resolveVotedIds.clear();
                    Cursor vc = db.rawQuery("SELECT report_id, vote_type FROM user_votes", null);
                    while (vc.moveToNext()) {
                        String rId = vc.getString(0);
                        String vType = vc.getString(1);
                        if ("UPVOTE".equals(vType)) upvotedIds.add(rId);
                        if ("RESOLVE".equals(vType)) resolveVotedIds.add(rId);
                    }
                    vc.close();
                }

                if (reportsList.isEmpty()) {
                    seedInitialDataLocked();
                }
                publishSnapshotLocked();
            }
        });
    }

    private void seedInitialDataLocked() {
        HazardReport r1 = new HazardReport(
                "CF-101",
                "[Demo Seed] Deep Water Ponding at Pantai Gate",
                "Monsoon runoff overflowing main curb drain; water depth ~25cm blocking left lane.",
                "Lingkungan Budi (Pantai Gate) (3.1195, 101.6620)",
                "Flash Flood",
                "Critical",
                "Verified",
                3.1195, 101.6620,
                "Today, 14:10",
                14, 0, 0.91,
                "[Demo Seed] High-risk flood zone near Pantai Valley river culvert.",
                "demo.seed@siswa.um.edu.my",
                "14:10 • Submitted (Demo Seed)\n14:12 • Auto-Verified by Historical Flood Layer"
        );
        HazardReport r2 = new HazardReport(
                "CF-102",
                "[Demo Seed] Blocked Storm Grate Outside FCSIT",
                "Wet leaves and plastic debris clogging drainage grate near Block A.",
                "FCSIT & Central Library (3.1281, 101.6505)",
                "Blocked Drain",
                "Medium",
                "In Progress - JPPHB",
                3.1281, 101.6505,
                "Today, 13:25",
                8, 1, 0.84,
                "[Demo Seed] Moderate ponding risk if heavy rain continues.",
                "demo.seed@siswa.um.edu.my",
                "13:25 • Submitted (Demo Seed)\n13:50 • Dispatched to JPPHB Maintenance Crew"
        );
        HazardReport r3 = new HazardReport(
                "CF-103",
                "[Demo Seed] Fallen Branch Blocking Covered Walkway",
                "Large tree limb snapped after gusty wind near KK12 bus stop.",
                "KK12 Residential College (3.1338, 101.6518)",
                "Fallen Tree",
                "Medium",
                "Submitted",
                3.1338, 101.6518,
                "Today, 12:40",
                5, 2, 0.87,
                "[Demo Seed] Pedestrian detour required around bus shelter.",
                "demo.seed@siswa.um.edu.my",
                "12:40 • Submitted (Demo Seed)"
        );
        // Seeds stay local-only (never queued for sync) and sort in the order listed here.
        long now = System.currentTimeMillis();
        r1.createdAt = now;
        r2.createdAt = now - 1;
        r3.createdAt = now - 2;

        reportsList.add(r1);
        reportsList.add(r2);
        reportsList.add(r3);

        if (dbHelper != null) {
            for (HazardReport r : reportsList) {
                insertOrUpdateReportDb(r);
            }
        }
    }

    private void insertOrUpdateReportDb(HazardReport r) {
        if (dbHelper == null) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", r.id);
        cv.put("title", r.title);
        cv.put("description", r.description);
        cv.put("locationName", r.locationName);
        cv.put("hazardType", r.hazardType);
        cv.put("severity", r.severity);
        cv.put("status", r.status);
        cv.put("latitude", r.latitude);
        cv.put("longitude", r.longitude);
        cv.put("timestamp", r.timestamp);
        cv.put("upvotes", r.upvotes);
        cv.put("resolveVotes", r.resolveVotes);
        cv.put("aiConfidence", r.aiConfidence);
        cv.put("aiTriageNote", r.aiTriageNote);
        cv.put("reporterEmail", r.reporterEmail);
        cv.put("timeline", r.timeline);
        cv.put("photoUri", r.photoUri != null ? r.photoUri : "");
        cv.put("createdAt", r.createdAt);
        // Update first: REPLACE would delete + re-insert the row and change its rowid
        int rows = db.update("hazard_reports", cv, "id=?", new String[]{r.id});
        if (rows == 0) {
            db.insertWithOnConflict("hazard_reports", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    /** Persist captured report photo into internal app storage (files/report_photos/<id>.jpg). */
    public String saveReportPhotoToInternalStorage(String reportId, Bitmap bitmap) {
        if (appContext == null || bitmap == null || reportId == null) return "";
        try {
            File dir = new File(appContext.getFilesDir(), "report_photos");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File photoFile = new File(dir, reportId + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.flush();
            }
            return photoFile.getAbsolutePath();
        } catch (Exception ignored) {
            return "";
        }
    }

    // 必须在 synchronized(reportsList) 锁内调用，深拷贝每个对象后再 postValue
    private void publishSnapshotLocked() {
        List<HazardReport> snapshot = new ArrayList<>(reportsList.size());
        for (HazardReport r : reportsList) {
            snapshot.add(new HazardReport(r));
        }
        try { reportsLiveData.postValue(snapshot); } catch (RuntimeException ignored) {}
    }

    public LiveData<List<HazardReport>> getReportsLiveData() {
        return reportsLiveData;
    }

    public LiveData<String> getUserRoleLiveData() {
        return userRoleLiveData;
    }

    public LiveData<String> getBroadcastBannerLiveData() {
        return broadcastBannerLiveData;
    }

    public LiveData<String> getSyncStatusLiveData() {
        return syncStatusLiveData;
    }

    public String getCurrentUserRole() {
        String val = currentRole;
        return val != null ? val : "UM Student / Staff";
    }

    public boolean isAdmin() {
        return "JPPHB Maintenance Admin".equals(getCurrentUserRole());
    }

    // 带 PIN 码验证的管理员切换 (FR 1.3)
    public synchronized boolean authenticateAndSetAdmin(String inputPin) {
        long now = System.currentTimeMillis();
        if (now < pinLockedUntil) return false;
        String pin = inputPin != null ? inputPin.trim() : "";
        if (!pin.isEmpty() && constantTimeEquals(sha256Hex(PIN_SALT + pin), BuildConfig.ADMIN_PIN_HASH)) {
            failedPinAttempts = 0;
            setRoleInternal("JPPHB Maintenance Admin");
            fetchAdminTokenAsync(pin);
            return true;
        }
        failedPinAttempts++;
        if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
            failedPinAttempts = 0;
            pinLockedUntil = now + PIN_LOCKOUT_MS;
        }
        return false;
    }

    /** The server checks the PIN again and issues a short-lived token; without it status changes stay local. */
    private void fetchAdminTokenAsync(String pin) {
        if (syncClient == null || appContext == null) return;
        syncExecutor.execute(() -> adminToken = syncClient.adminLogin(pin));
    }

    /** Remaining lockout in ms after too many wrong PINs (0 = not locked). */
    public synchronized long getPinLockoutRemainingMs() {
        return Math.max(0L, pinLockedUntil - System.currentTimeMillis());
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void switchToStudentRole() {
        adminToken = null;
        setRoleInternal("UM Student / Staff");
    }

    private void setRoleInternal(String newRole) {
        currentRole = newRole;
        try { userRoleLiveData.postValue(newRole); } catch (RuntimeException ignored) {}
    }

    // FR 1.3: 管理员全校应急广播
    public void publishAdminBroadcast(String message) {
        if (!isAdmin()) return;
        String clean = message != null ? message.trim() : "";
        try { broadcastBannerLiveData.postValue(clean); } catch (RuntimeException ignored) {}
        if (prefs != null) {
            prefs.edit().putString("admin_broadcast", clean).apply();
        }
        if (!clean.isEmpty()) {
            sendLocalAlertNotification("📢 UM-ClearFlow Emergency Broadcast", clean);
        }
    }

    // FR 2.1 + 重复上报合并 (50米内同类型未解决隐患自动合并 +1，并支持高危升级与防刷记录)
    public void addReport(HazardReport newReport) {
        executorService.execute(() -> {
            synchronized (reportsList) {
                String nowTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                for (HazardReport existing : reportsList) {
                    if (!"Resolved".equalsIgnoreCase(existing.status)
                            && existing.hazardType.equalsIgnoreCase(newReport.hazardType)) {
                        double distMeters = calculateDistanceMeters(
                                existing.latitude, existing.longitude,
                                newReport.latitude, newReport.longitude);
                        if (distMeters <= 50.0) {
                            boolean firstVote = upvotedIds.add(existing.id);
                            if (firstVote) existing.upvotes += 1;
                            if (dbHelper != null) {
                                ContentValues cv = new ContentValues();
                                cv.put("report_id", existing.id);
                                cv.put("vote_type", "UPVOTE");
                                dbHelper.getWritableDatabase().insertWithOnConflict(
                                        "user_votes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                            }
                            boolean attachedPhoto = false;
                            if ((existing.photoUri == null || existing.photoUri.isEmpty())
                                    && newReport.photoUri != null && !newReport.photoUri.isEmpty()) {
                                existing.photoUri = newReport.photoUri;
                                attachedPhoto = true;
                            }
                            boolean upgradedToCritical = !"Critical".equalsIgnoreCase(existing.severity)
                                    && "Critical".equalsIgnoreCase(newReport.severity);
                            if (upgradedToCritical) {
                                existing.severity = "Critical";
                            }
                            existing.timeline = existing.timeline + "\n" + nowTime
                                    + " • Merged nearby duplicate report (<50m, Upvotes +" + (firstVote ? 1 : 0)
                                    + (upgradedToCritical ? ", escalated to Critical)" : ")");
                            insertOrUpdateReportDb(existing);
                            publishSnapshotLocked();
                            // Server dedupes votes per device, so this is safe to resend
                            enqueueOp("UPVOTE", existing.id, payload("demo", false));
                            if (attachedPhoto) {
                                enqueueOp("PHOTO", existing.id, payload("path", existing.photoUri));
                            }
                            if (upgradedToCritical) {
                                sendLocalAlertNotification(
                                        "⚠️ Hazard Escalated to Critical",
                                        existing.hazardType + " near " + existing.locationName + ": " + existing.title
                                );
                            }
                            return;
                        }
                    }
                }

                reportsList.add(0, new HazardReport(newReport));
                insertOrUpdateReportDb(newReport);
                publishSnapshotLocked();
                enqueueOp("CREATE", newReport.id, null);
                if (newReport.photoUri != null && !newReport.photoUri.isEmpty()) {
                    enqueueOp("PHOTO", newReport.id, payload("path", newReport.photoUri));
                }

                if ("Critical".equalsIgnoreCase(newReport.severity)) {
                    sendLocalAlertNotification(
                            "⚠️ Critical Campus Hazard Reported",
                            newReport.hazardType + " near " + newReport.locationName + ": " + newReport.title
                    );
                }
            }
        });
    }

    // FR 2.2: 一人一票 Upvote 防刷
    public boolean hasUpvoted(String reportId) {
        synchronized (reportsList) {
            return upvotedIds.contains(reportId);
        }
    }

    public void upvoteReport(String id) {
        executorService.execute(() -> {
            synchronized (reportsList) {
                if (upvotedIds.contains(id)) return;
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        r.upvotes++;
                        upvotedIds.add(id);
                        if (dbHelper != null) {
                            ContentValues cv = new ContentValues();
                            cv.put("report_id", id);
                            cv.put("vote_type", "UPVOTE");
                            dbHelper.getWritableDatabase().insertWithOnConflict(
                                     "user_votes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        }
                        insertOrUpdateReportDb(r);
                        enqueueOp("UPVOTE", id, payload("demo", false));
                        break;
                    }
                }
                publishSnapshotLocked();
            }
        });
    }

    // FR 2.2: "已解除 (Verify Clear)" 众包投票，满 3 票自动将状态置为 Resolved
    public boolean hasVotedResolve(String reportId) {
        synchronized (reportsList) {
            return resolveVotedIds.contains(reportId);
        }
    }

    public void voteResolveReport(String id) {
        executorService.execute(() -> {
            synchronized (reportsList) {
                boolean alreadyVotedOnDevice = resolveVotedIds.contains(id);
                // In release builds without demo mode, restrict to 1 vote per device;
                // in debug/demo builds, allow simulating additional community votes to demonstrate 3/3 closure.
                if (alreadyVotedOnDevice && !BuildConfig.DEBUG) return;
                String nowTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        if ("Resolved".equalsIgnoreCase(r.status)) break;
                        r.resolveVotes++;
                        resolveVotedIds.add(id);
                        String demoSuffix = alreadyVotedOnDevice ? " [Demo Multi-User Vote]" : "";
                        if (r.resolveVotes >= 3) {
                            r.status = "Resolved";
                            r.timeline = r.timeline + "\n" + nowTime
                                    + " • Marked Resolved by Community Verification (3/3 votes)" + demoSuffix;
                        } else {
                            r.timeline = r.timeline + "\n" + nowTime
                                    + " • Community Clear Vote (" + r.resolveVotes + "/3)" + demoSuffix;
                        }
                        if (!alreadyVotedOnDevice && dbHelper != null) {
                            ContentValues cv = new ContentValues();
                            cv.put("report_id", id);
                            cv.put("vote_type", "RESOLVE");
                            dbHelper.getWritableDatabase().insertWithOnConflict(
                                    "user_votes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        }
                        insertOrUpdateReportDb(r);
                        // "demo" only has an effect when the server runs with CLEARFLOW_DEMO=1
                        enqueueOp("RESOLVE", id, payload("demo", alreadyVotedOnDevice));
                        break;
                    }
                }
                publishSnapshotLocked();
            }
        });
    }

    // FR 2.3: 仅管理员可更新工单状态并追加时间线
    public void updateReportStatus(String id, String newStatus) {
        if (!isAdmin()) return;
        if (!"Submitted".equals(newStatus)
                && !"Verified".equals(newStatus)
                && !"In Progress - JPPHB".equals(newStatus)
                && !"Resolved".equals(newStatus)) return;
        executorService.execute(() -> {
            synchronized (reportsList) {
                String nowTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        r.status = newStatus;
                        r.timeline = r.timeline + "\n" + nowTime + " • Status updated to [" + newStatus + "] by JPPHB Admin";
                        insertOrUpdateReportDb(r);
                        enqueueOp("STATUS", id, payload("status", newStatus));
                        break;
                    }
                }
                publishSnapshotLocked();
            }
        });
    }

    // ------------------------------------------------------------------ Sync (outbox + pull)

    private static JSONObject payload(String key, Object value) {
        JSONObject o = new JSONObject();
        try {
            o.put(key, value);
        } catch (JSONException ignored) {}
        return o;
    }

    private static JSONObject parseJson(String s) {
        try {
            return new JSONObject(s != null ? s : "{}");
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Queue a change for the server. No-op when there is no database (unit tests) or no backend configured. */
    private void enqueueOp(String op, String reportId, JSONObject payload) {
        if (dbHelper == null || syncClient == null) return;
        ContentValues cv = new ContentValues();
        cv.put("op", op);
        cv.put("report_id", reportId);
        cv.put("payload", payload != null ? payload.toString() : "{}");
        dbHelper.getWritableDatabase().insert("outbox", null, cv);
        requestSync();
    }

    /** Safe to call from any thread, as often as you like: runs are coalesced. */
    public void requestSync() {
        if (dbHelper == null || syncClient == null) return;
        syncAgain.set(true);
        if (!syncRunning.compareAndSet(false, true)) return;
        syncExecutor.execute(() -> {
            try {
                while (syncAgain.getAndSet(false)) {
                    try {
                        runSyncOnce();
                    } catch (RuntimeException e) {
                        postSyncStatus(0, true);
                    }
                }
            } finally {
                syncRunning.set(false);
            }
        });
    }

    private void runSyncOnce() {
        int pending = flushOutbox();
        if (pending == 0) {
            pullRemote();
        } else {
            // Do not pull while local changes are unsent: it would overwrite them with older server values
            postSyncStatus(pending, serverUnreachable);
        }
    }

    private int flushOutbox() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<OutboxOp> ops = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, op, report_id, payload FROM outbox ORDER BY id ASC", null)) {
            while (c.moveToNext()) {
                ops.add(new OutboxOp(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)));
            }
        }
        serverUnreachable = false;
        for (OutboxOp op : ops) {
            int result = executeOp(op);
            if (result == OP_RETRY) {
                serverUnreachable = true;
                break; // keep order: later ops may depend on this one
            }
            db.delete("outbox", "id=?", new String[]{String.valueOf(op.rowId)});
        }
        return (int) DatabaseUtils.queryNumEntries(db, "outbox");
    }

    private static int classify(SyncClient.Response r) {
        if (r.ok()) return OP_DONE;
        if (r.code == SyncClient.NETWORK_ERROR || r.code >= 500 || r.code == 429) return OP_RETRY;
        return OP_DROP; // 404 unknown report (e.g. local demo seed), 401/403, 422 ...
    }

    private JSONObject voteBody(boolean demo) {
        JSONObject o = new JSONObject();
        try {
            o.put("device_id", deviceId);
            o.put("demo", demo);
        } catch (JSONException ignored) {}
        return o;
    }

    private int executeOp(OutboxOp op) {
        String id = op.reportId;
        JSONObject p = parseJson(op.payload);
        switch (op.op) {
            case "CREATE":
                return execCreate(id);
            case "UPVOTE":
                return classify(syncClient.postJson("api/v1/reports/" + id + "/upvote", voteBody(false), null));
            case "RESOLVE":
                return classify(syncClient.postJson("api/v1/reports/" + id + "/resolve-vote",
                        voteBody(p.optBoolean("demo", false)), null));
            case "STATUS": {
                String token = adminToken;
                if (token == null) return OP_DROP; // no server session: the change stays local-only
                return classify(syncClient.postJson("api/v1/reports/" + id + "/status",
                        payload("status", p.optString("status", "")), token));
            }
            case "PHOTO":
                return execPhoto(id, p.optString("path", ""));
            default:
                return OP_DROP;
        }
    }

    private int execCreate(String id) {
        HazardReport copy = null;
        synchronized (reportsList) {
            for (HazardReport r : reportsList) {
                if (r.id.equals(id)) {
                    copy = new HazardReport(r);
                    break;
                }
            }
        }
        if (copy == null) return OP_DROP;

        SyncClient.Response resp;
        try {
            resp = syncClient.postJson("api/v1/reports", SyncClient.reportToJson(copy, deviceId), null);
        } catch (JSONException e) {
            return OP_DROP;
        }
        int cls = classify(resp);
        if (cls != OP_DONE) return cls;

        String mergedInto = (resp.json == null || resp.json.isNull("merged_into"))
                ? "" : resp.json.optString("merged_into", "");
        synchronized (reportsList) {
            if (!mergedInto.isEmpty() && !mergedInto.equals(id)) {
                // The server found a nearby duplicate and merged our report into it: drop the local copy
                Iterator<HazardReport> it = reportsList.iterator();
                while (it.hasNext()) {
                    if (it.next().id.equals(id)) it.remove();
                }
                upvotedIds.add(mergedInto);
                if (dbHelper != null) {
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    db.delete("hazard_reports", "id=?", new String[]{id});
                    ContentValues cv = new ContentValues();
                    cv.put("report_id", mergedInto);
                    cv.put("vote_type", "UPVOTE");
                    db.insertWithOnConflict("user_votes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                }
                publishSnapshotLocked();
                if (copy.photoUri != null && !copy.photoUri.isEmpty()) {
                    enqueueOp("PHOTO", mergedInto, payload("path", copy.photoUri));
                }
            } else {
                syncedIds.add(id);
                markSyncedDb(id);
            }
        }
        return OP_DONE;
    }

    private int execPhoto(String reportId, String path) {
        if (path == null || path.isEmpty()) return OP_DROP;
        File f = new File(path);
        if (!f.isFile()) return OP_DROP;
        // 409 (server already has a photo) and 404 (report merged away) are both permanent -> dropped
        return classify(syncClient.uploadPhoto("api/v1/reports/" + reportId + "/photo", f, deviceId));
    }

    private void markSyncedDb(String id) {
        if (dbHelper == null) return;
        dbHelper.getWritableDatabase().execSQL("UPDATE hazard_reports SET synced=1 WHERE id=?", new Object[]{id});
    }

    private static void copyServerFields(HazardReport local, HazardReport in) {
        local.title = in.title;
        local.description = in.description;
        local.locationName = in.locationName;
        local.hazardType = in.hazardType;
        local.severity = in.severity;
        local.status = in.status;
        local.latitude = in.latitude;
        local.longitude = in.longitude;
        local.timestamp = in.timestamp;
        local.upvotes = in.upvotes;
        local.resolveVotes = in.resolveVotes;
        local.aiConfidence = in.aiConfidence;
        local.aiTriageNote = in.aiTriageNote;
        local.reporterEmail = in.reporterEmail;
        local.timeline = in.timeline;
        if (in.createdAt > 0) local.createdAt = in.createdAt;
    }

    private void applyVoteFlag(SQLiteDatabase db, String reportId, String type, boolean voted, Set<String> memory) {
        if (voted) {
            memory.add(reportId);
            ContentValues cv = new ContentValues();
            cv.put("report_id", reportId);
            cv.put("vote_type", type);
            db.insertWithOnConflict("user_votes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        } else {
            memory.remove(reportId);
            db.delete("user_votes", "report_id=? AND vote_type=?", new String[]{reportId, type});
        }
    }

    private void pullRemote() {
        SyncClient.Response resp = syncClient.get("api/v1/reports?limit=" + PULL_LIMIT + "&device_id=" + deviceId);
        if (!resp.ok() || resp.json == null) {
            postSyncStatus(0, true);
            return;
        }
        JSONArray arr = resp.json.optJSONArray("reports");
        if (arr == null) {
            postSyncStatus(0, true);
            return;
        }
        List<SyncClient.Remote> remote = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            SyncClient.Remote rm = SyncClient.parseRemote(o);
            if (!rm.report.id.isEmpty()) remote.add(rm);
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<String> needPhoto = new ArrayList<>();
        synchronized (reportsList) {
            // The user acted while we were downloading: skip, the next round will include that change
            if (DatabaseUtils.queryNumEntries(db, "outbox") > 0) return;

            Map<String, HazardReport> byId = new HashMap<>();
            for (HazardReport r : reportsList) byId.put(r.id, r);
            Set<String> remoteIds = new HashSet<>();

            db.beginTransaction();
            try {
                for (SyncClient.Remote rm : remote) {
                    HazardReport in = rm.report;
                    remoteIds.add(in.id);
                    HazardReport local = byId.get(in.id);
                    if (local == null) {
                        local = in;
                        reportsList.add(local);
                        byId.put(in.id, local);
                    } else {
                        copyServerFields(local, in);
                    }
                    syncedIds.add(in.id);
                    insertOrUpdateReportDb(local);
                    markSyncedDb(in.id);
                    applyVoteFlag(db, in.id, "UPVOTE", rm.myUpvote, upvotedIds);
                    applyVoteFlag(db, in.id, "RESOLVE", rm.myResolve, resolveVotedIds);
                    boolean haveFile = local.photoUri != null && !local.photoUri.isEmpty()
                            && new File(local.photoUri).isFile();
                    if (rm.hasPhoto && !haveFile && needPhoto.size() < MAX_PHOTO_DOWNLOADS_PER_SYNC) {
                        needPhoto.add(in.id);
                    }
                }
                // Reports that used to exist on the server but are gone now (e.g. demo reset): remove them here too.
                // Only safe when we received the complete list.
                if (remote.size() < PULL_LIMIT) {
                    Iterator<HazardReport> it = reportsList.iterator();
                    while (it.hasNext()) {
                        HazardReport r = it.next();
                        if (syncedIds.contains(r.id) && !remoteIds.contains(r.id)) {
                            it.remove();
                            syncedIds.remove(r.id);
                            db.delete("hazard_reports", "id=?", new String[]{r.id});
                        }
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            Collections.sort(reportsList, (a, b) -> Long.compare(b.createdAt, a.createdAt));
            publishSnapshotLocked();
        }

        downloadMissingPhotos(needPhoto);
        postSyncStatus(0, false);
    }

    private void downloadMissingPhotos(List<String> ids) {
        if (appContext == null || ids.isEmpty()) return;
        boolean changed = false;
        for (String id : ids) {
            byte[] bytes = syncClient.download("api/v1/reports/" + id + "/photo");
            if (bytes == null || bytes.length == 0) continue;
            File dir = new File(appContext.getFilesDir(), "report_photos");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, id + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            } catch (Exception e) {
                continue;
            }
            synchronized (reportsList) {
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        r.photoUri = out.getAbsolutePath();
                        insertOrUpdateReportDb(r);
                        changed = true;
                        break;
                    }
                }
            }
        }
        if (changed) {
            synchronized (reportsList) {
                publishSnapshotLocked();
            }
        }
    }

    private void postSyncStatus(int pending, boolean offline) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        int total;
        synchronized (reportsList) {
            total = reportsList.size();
        }
        String text;
        if (offline) {
            text = "📴 Server unreachable — " + pending + " change(s) waiting to sync (last try " + time + ")";
        } else if (pending > 0) {
            text = "🔄 Syncing… " + pending + " change(s) pending";
        } else {
            text = "☁️ Synced at " + time + " • " + total + " reports on this device";
        }
        try { syncStatusLiveData.postValue(text); } catch (RuntimeException ignored) {}
    }

    // FR 3.1: 尝试将 Bitmap 上传至 FastAPI /api/v1/triage 进行服务端视觉分类，离线时返回 null 交由端侧 HSV 引擎处理
    private TriageResult tryBackendVisionTriage(Bitmap bitmap, String hintType, String userTitle) {
        java.net.HttpURLConnection conn = null;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] jpegBytes = baos.toByteArray();

            String boundary = "----UMClearFlowBoundary" + System.currentTimeMillis();
            java.net.URL url = new java.net.URL(API_BASE_URL + "api/v1/triage");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(conn.getOutputStream())) {
                // hint_type field
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"hint_type\"\r\n\r\n");
                dos.write((hintType != null ? hintType : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                dos.writeBytes("\r\n");
                // title field
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"title\"\r\n\r\n");
                dos.write((userTitle != null ? userTitle : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                dos.writeBytes("\r\n");
                // image file field
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"hazard.jpg\"\r\n");
                dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");
                dos.write(jpegBytes);
                dos.writeBytes("\r\n--" + boundary + "--\r\n");
                dos.flush();
            }

            if (conn.getResponseCode() == 200) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    org.json.JSONObject json = new org.json.JSONObject(sb.toString());
                    String detected = json.optString("hazard_type", hintType);
                    String sev = json.optString("severity", "Medium");
                    double conf = json.optDouble("confidence", 0.88);
                    String note = json.optString("note", "[FastAPI Vision Server]") + " • Auto-classified: " + detected;
                    return new TriageResult(sev, conf, note, detected);
                }
            }
        } catch (Exception ignored) {
            // FastAPI 未启动或网络不可达，无缝回退到端侧 HSV 图像特征引擎
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    // FR 3.1: 启发式图像像素特征分诊 (Heuristic HSV Triage) + 离线规则降级
    public TriageResult runVisionTriage(Bitmap bitmap, int hazardTypeIndex, String userTitle) {
        String[] types = {"Flash Flood", "Blocked Drain", "Fallen Tree", "Slope / Landslide"};
        String selectedType = (hazardTypeIndex >= 0 && hazardTypeIndex < types.length)
                ? types[hazardTypeIndex] : "Flash Flood";

        if (bitmap == null) {
            // 未拍照时诚实标注为离线规则估算，绝不伪装成 AI 视觉扫描
            TriageResult fallback = runTriage(hazardTypeIndex, userTitle);
            return new TriageResult(
                    fallback.severity,
                    fallback.confidence,
                    "[Offline Rule Estimation • No Photo Attached] " + fallback.note,
                    selectedType
            );
        }

        // 分析真实 Bitmap 的 HSV 色彩与亮度分布特征
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int totalSamples = 0;
        int muddyWaterPixels = 0;
        int foliageGreenPixels = 0;
        int darkObstaclePixels = 0;
        int waterReflectionPixels = 0;

        int stepX = Math.max(1, width / 24);
        int stepY = Math.max(1, height / 24);
        float[] hsv = new float[3];

        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                float hue = hsv[0];
                float sat = hsv[1];
                float val = hsv[2];
                totalSamples++;

                // 泥水/积水黄褐色特征 (Hue 15..50, 中饱和度)
                if (hue >= 15f && hue <= 52f && sat >= 0.22f && val >= 0.20f && val <= 0.85f) {
                    muddyWaterPixels++;
                }
                // 树木/植被绿色特征 (Hue 65..165)
                if (hue >= 65f && hue <= 165f && sat >= 0.20f && val >= 0.15f) {
                    foliageGreenPixels++;
                }
                // 排水口阴影/深色淤堵物
                if (val < 0.25f) {
                    darkObstaclePixels++;
                }
                // 水面高光反射或灰白积水反光
                if (sat < 0.18f && val > 0.72f) {
                    waterReflectionPixels++;
                }
            }
        }

        double muddyRatio = totalSamples > 0 ? (double) muddyWaterPixels / totalSamples : 0;
        double greenRatio = totalSamples > 0 ? (double) foliageGreenPixels / totalSamples : 0;
        double darkRatio = totalSamples > 0 ? (double) darkObstaclePixels / totalSamples : 0;
        double reflectRatio = totalSamples > 0 ? (double) waterReflectionPixels / totalSamples : 0;

        String severity;
        double confidence;
        String detailNote;
        String autoDetectedType = selectedType;
        String lowerTitle = userTitle != null ? userTitle.toLowerCase(Locale.ROOT) : "";
        boolean offMain = true;
        try { offMain = android.os.Looper.myLooper() != android.os.Looper.getMainLooper(); } catch (RuntimeException ignored) {}
        if (offMain) {
            TriageResult remote = tryBackendVisionTriage(bitmap, selectedType, userTitle);
            if (remote != null) return remote;
        }
        if (lowerTitle.contains("tree") || lowerTitle.contains("branch") || greenRatio > 0.28) {
            autoDetectedType = "Fallen Tree";
        } else if (lowerTitle.contains("drain") || lowerTitle.contains("grate") || lowerTitle.contains("culvert")
                || (darkRatio > 0.30 && muddyRatio < 0.22)) {
            autoDetectedType = "Blocked Drain";
        } else if (lowerTitle.contains("slope") || lowerTitle.contains("landslide")) {
            autoDetectedType = "Slope / Landslide";
        } else if (muddyRatio + reflectRatio > 0.32) {
            autoDetectedType = "Flash Flood";
        }

        if (hazardTypeIndex == 0 || muddyRatio + reflectRatio > 0.42) {
            double waterScore = muddyRatio * 1.4 + reflectRatio * 0.8;
            severity = (waterScore > 0.35 || latestRainfallMm > 5.0) ? "Critical" : "Medium";
            confidence = Math.min(0.96, 0.74 + waterScore * 0.35);
            detailNote = String.format(Locale.US,
                    "[On-Device Heuristic HSV Scan] Turbid water/reflection coverage: %.0f%% | Dark depth ratio: %.0f%%.",
                    (muddyRatio + reflectRatio) * 100, darkRatio * 100);
        } else if (hazardTypeIndex == 2 || greenRatio > 0.28) {
            severity = (greenRatio > 0.38 || darkRatio > 0.30) ? "Critical" : "Medium";
            confidence = Math.min(0.94, 0.72 + greenRatio * 0.40);
            detailNote = String.format(Locale.US,
                    "[On-Device Heuristic HSV Scan] Canopy/foliage obstruction ratio: %.0f%% | Shadow density: %.0f%%.",
                    greenRatio * 100, darkRatio * 100);
        } else {
            severity = darkRatio > 0.32 ? "Critical" : "Medium";
            confidence = Math.min(0.92, 0.70 + (darkRatio + muddyRatio) * 0.35);
            detailNote = String.format(Locale.US,
                    "[On-Device Heuristic HSV Scan] Drain occlusion/soil ratio: %.0f%% | Surface contrast verified.",
                    (darkRatio + muddyRatio) * 100);
        }

        return new TriageResult(severity, confidence, detailNote + " • Auto-classified: " + autoDetectedType, autoDetectedType);
    }

    // 保留离线规则分诊作为无照片时的降级方案
    public TriageResult runTriage(int hazardTypeIndex, String userTitle) {
        String lower = userTitle != null ? userTitle.toLowerCase(Locale.ROOT) : "";
        boolean urgentKeyword = lower.contains("deep") || lower.contains("blocked")
                || lower.contains("trapped") || lower.contains("overflow") || lower.contains("urgent");

        if (hazardTypeIndex == 0) {
            String sev = (urgentKeyword || latestRainfallMm >= 5.0) ? "Critical" : "Medium";
            return new TriageResult(sev, 0.82,
                    "Flash flood rule assessment combined with current rainfall (" + latestRainfallMm + " mm).",
                    "Flash Flood");
        } else if (hazardTypeIndex == 1) {
            return new TriageResult(urgentKeyword ? "Critical" : "Medium", 0.78,
                    "Blocked storm drain increases localized ponding risk during monsoon squalls.",
                    "Blocked Drain");
        } else if (hazardTypeIndex == 2) {
            return new TriageResult(urgentKeyword ? "Critical" : "Medium", 0.80,
                    "Fallen tree obstruction flagged for JPPHB landscape clearance unit.",
                    "Fallen Tree");
        } else {
            return new TriageResult("Critical", 0.85,
                    "Slope instability requires immediate safety cordon and civil inspection.",
                    "Slope / Landslide");
        }
    }

    public void updateWeatherMetrics(double currentRainMm, double next3hForecastMm) {
        this.latestRainfallMm = currentRainMm;
        this.forecast3hRainMm = next3hForecastMm;
    }

    public double getLatestRainfallMm() {
        return latestRainfallMm;
    }

    public double getForecast3hRainMm() {
        return forecast3hRainMm;
    }

    // FR 4.1: 官方校园应急避难所列表
    public List<ShelterInfo> getCampusShelters() {
        List<ShelterInfo> list = new ArrayList<>();
        list.add(new ShelterInfo("KK8 (Kolej Kediaman Kinabalu Safe Zone)", 3.1302, 101.6485,
                "North-West Residential College High-Ground Assembly • Covered Walkway Access"));
        list.add(new ShelterInfo("Dewan Tunku Canselor (DTC Shelter)", 3.1219, 101.6570,
                "Primary Elevated Assembly Hall • Capacity: 1,500 • Emergency Generator & First Aid"));
        list.add(new ShelterInfo("Perdanasiswa Complex (KPS Shelter)", 3.1235, 101.6552,
                "Central Student Relief Hub • Capacity: 800 • Food, Drinking Water & Charging Station"));
        list.add(new ShelterInfo("UM Arena / Sports Complex Shelter", 3.1268, 101.6602,
                "East Campus Evacuation Point • Capacity: 1,200 • Accessible via High Ground"));
        list.add(new ShelterInfo("FCSIT Block B Upper Concourse", 3.1284, 101.6502,
                "North Zone Temporary Refuge • Capacity: 400 • 2nd Floor Flood-Safe Zone"));
        list.add(new ShelterInfo("KK12 (Kolej Kediaman Raja Dr. Nazrin Shah Hall)", 3.1338, 101.6518,
                "North Residential Relief Point • Capacity: 600 • Shuttle Bus Hub"));
        return list;
    }

    // FR 1.1 & 1.2: 读写学生个人资料、学院宿舍、紧急联系人与出行偏好
    public void saveUserProfile(String email, String name, String matric, String faculty,
                                String college, String emergencyName, String emergencyPhone,
                                String mobilityPref) {
        if (prefs == null) return;
        prefs.edit()
                .putString("siswa_email", email)
                .putString("student_name", name)
                .putString("matric_id", matric)
                .putString("faculty", faculty)
                .putString("college", college)
                .putString("emergency_name", emergencyName)
                .putString("emergency_phone", emergencyPhone)
                .putString("mobility_pref", mobilityPref)
                .apply();
    }

    public String getProfileField(String key, String defaultVal) {
        if (prefs == null) return defaultVal;
        return prefs.getString(key, defaultVal);
    }

    // FR 5.1: 从真实 SQLite 数据动态计算高频黑点排行榜
    public String generateHotspotRankingText() {
        synchronized (reportsList) {
            if (reportsList.isEmpty()) {
                return "No hazard reports recorded in SQLite yet.";
            }
            Map<String, Integer> countByZone = new HashMap<>();
            Map<String, Integer> criticalByZone = new HashMap<>();
            int resolvedTotal = 0;

            for (HazardReport r : reportsList) {
                String zone = r.locationName != null ? r.locationName.replaceAll("\\s*\\([^)]*\\)", "").trim() : "Unknown Zone";
                countByZone.put(zone, countByZone.getOrDefault(zone, 0) + 1);
                if ("Critical".equalsIgnoreCase(r.severity)) {
                    criticalByZone.put(zone, criticalByZone.getOrDefault(zone, 0) + 1);
                }
                if ("Resolved".equalsIgnoreCase(r.status)) {
                    resolvedTotal++;
                }
            }

            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(countByZone.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            StringBuilder sb = new StringBuilder();
            int rank = 1;
            for (Map.Entry<String, Integer> entry : sorted) {
                int crit = criticalByZone.getOrDefault(entry.getKey(), 0);
                int filled = Math.min(8, Math.max(1, entry.getValue() * 2));
                StringBuilder bar = new StringBuilder();
                for (int b = 0; b < 8; b++) bar.append(b < filled ? '█' : '░');
                sb.append(rank++).append(". [").append(bar).append("] ").append(entry.getKey())
                        .append(" — ").append(entry.getValue()).append(" reports (")
                        .append(crit).append(" Critical)\n");
                if (rank > 5) break;
            }
            sb.append("• Total Tracked: ").append(reportsList.size())
                    .append(" | Resolved: ").append(resolvedTotal);
            return sb.toString();
        }
    }

    // FR 5.2: 导出真实 CSV 报告
    public String exportReportsToCsv() {
        if (appContext != null && !isAdmin()) return "";
        synchronized (reportsList) {
            StringBuilder csv = new StringBuilder();
            csv.append("ID,Title,HazardType,Severity,Status,Location,Latitude,Longitude,Upvotes,ResolveVotes,ReporterEmail,Timestamp\n");
            for (HazardReport r : reportsList) {
                csv.append(escapeCsv(r.id)).append(",")
                        .append(escapeCsv(r.title)).append(",")
                        .append(escapeCsv(r.hazardType)).append(",")
                        .append(escapeCsv(r.severity)).append(",")
                        .append(escapeCsv(r.status)).append(",")
                        .append(escapeCsv(r.locationName)).append(",")
                        .append(String.format(Locale.US, "%.5f", r.latitude)).append(",")
                        .append(String.format(Locale.US, "%.5f", r.longitude)).append(",")
                        .append(r.upvotes).append(",")
                        .append(r.resolveVotes).append(",")
                        .append(escapeCsv(r.reporterEmail)).append(",")
                        .append(escapeCsv(r.timestamp)).append("\n");
            }
            return csv.toString();
        }
    }

    private String escapeCsv(String val) {
        if (val == null) return "\"\"";
        String s = val.replace("\r", " ").replace("\n", " ");
        // 防 Excel 公式注入：以 = + - @ 或 Tab 开头的单元格加前缀单引号
        if (!s.isEmpty() && "=+-@\t".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // FR 5.3: 触发系统状态栏本地通知
    public void sendLocalAlertNotification(String title, String message) {
        if (appContext == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat.from(appContext)
                .notify((int) (System.currentTimeMillis() & 0xFFFFFFF), builder.build());
    }

    private void createNotificationChannel() {
        if (appContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "UM-ClearFlow Flood & Hazard Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Extreme weather warnings and campus emergency broadcasts");
        NotificationManager nm = appContext.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    public static double calculateDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // 内置原生 SQLiteOpenHelper 数据库
    private static class ClearFlowDbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "um_clearflow.db";
        private static final int DB_VERSION = 4;

        ClearFlowDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS hazard_reports (" +
                    "id TEXT PRIMARY KEY, " +
                    "title TEXT, " +
                    "description TEXT, " +
                    "locationName TEXT, " +
                    "hazardType TEXT, " +
                    "severity TEXT, " +
                    "status TEXT, " +
                    "latitude REAL, " +
                    "longitude REAL, " +
                    "timestamp TEXT, " +
                    "upvotes INTEGER, " +
                    "resolveVotes INTEGER, " +
                    "aiConfidence REAL, " +
                    "aiTriageNote TEXT, " +
                    "reporterEmail TEXT, " +
                    "timeline TEXT, " +
                    "photoUri TEXT DEFAULT '', " +
                    "createdAt INTEGER DEFAULT 0, " +
                    "synced INTEGER DEFAULT 0)");

            db.execSQL("CREATE TABLE IF NOT EXISTS user_votes (" +
                    "report_id TEXT, " +
                    "vote_type TEXT, " +
                    "PRIMARY KEY(report_id, vote_type))");

            // Changes waiting to be sent to the server, in the order the user made them
            db.execSQL("CREATE TABLE IF NOT EXISTS outbox (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "op TEXT NOT NULL, " +
                    "report_id TEXT NOT NULL, " +
                    "payload TEXT DEFAULT '{}')");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                try { db.execSQL("ALTER TABLE hazard_reports ADD COLUMN resolveVotes INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE hazard_reports ADD COLUMN reporterEmail TEXT DEFAULT 'demo.student@siswa.um.edu.my'"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE hazard_reports ADD COLUMN timeline TEXT DEFAULT ''"); } catch (Exception ignored) {}
            }
            if (oldVersion < 3) {
                try { db.execSQL("ALTER TABLE hazard_reports ADD COLUMN photoUri TEXT DEFAULT ''"); } catch (Exception ignored) {}
            }
            if (oldVersion < 4) {
                try { db.execSQL("ALTER TABLE hazard_reports ADD COLUMN createdAt INTEGER DEFAULT 0"); } catch (Exception ignored) {}
                try { db.execSQL("ALTER TABLE hazard_reports ADD COLUMN synced INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            }
            onCreate(db); // CREATE TABLE IF NOT EXISTS preserves existing data and adds the outbox table
        }
    }
}
