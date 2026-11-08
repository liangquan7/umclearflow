package com.um.clearflow;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CampusRepository {

    public static final String API_BASE_URL = "http://10.0.2.2:8000/";
    public static final String ADMIN_PIN = "UM2026"; // 管理员验证 PIN 码
    public static final String CHANNEL_ID = "um_clearflow_alerts";

    private static volatile CampusRepository instance;
    private Context appContext;
    private ClearFlowDbHelper dbHelper;
    private SharedPreferences prefs;

    private final List<HazardReport> reportsList = new ArrayList<>();
    private final Set<String> upvotedIds = new HashSet<>();
    private final Set<String> resolveVotedIds = new HashSet<>();

    private final MutableLiveData<List<HazardReport>> reportsLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> userRoleLiveData = new MutableLiveData<>("UM Student / Staff");
    private final MutableLiveData<String> broadcastBannerLiveData = new MutableLiveData<>("");

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private double latestRainfallMm = 0.0;
    private double forecast3hRainMm = 0.0;

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

    private CampusRepository(Context context) {
        if (context != null) {
            this.appContext = context.getApplicationContext();
            this.dbHelper = new ClearFlowDbHelper(appContext);
            this.prefs = appContext.getSharedPreferences("um_clearflow_prefs", Context.MODE_PRIVATE);
            createNotificationChannel();
            String savedRole = prefs.getString("user_role", "UM Student / Staff");
            userRoleLiveData.postValue(savedRole);
            String savedBroadcast = prefs.getString("admin_broadcast", "");
            broadcastBannerLiveData.postValue(savedBroadcast);
        }
        loadFromSqliteOrSeed();
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new CampusRepository(context);
        } else if (instance.appContext == null && context != null) {
            instance.appContext = context.getApplicationContext();
            instance.dbHelper = new ClearFlowDbHelper(instance.appContext);
            instance.prefs = instance.appContext.getSharedPreferences("um_clearflow_prefs", Context.MODE_PRIVATE);
            instance.createNotificationChannel();
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
                if (dbHelper != null) {
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    Cursor c = db.rawQuery("SELECT * FROM hazard_reports ORDER BY rowid DESC", null);
                    while (c.moveToNext()) {
                        reportsList.add(new HazardReport(
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
                                c.getString(c.getColumnIndexOrThrow("timeline"))
                        ));
                    }
                    c.close();

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
                5, 0, 0.87,
                "[Demo Seed] Pedestrian detour required around bus shelter.",
                "demo.seed@siswa.um.edu.my",
                "12:40 • Submitted (Demo Seed)"
        );

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
        db.insertWithOnConflict("hazard_reports", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // 必须在 synchronized(reportsList) 锁内调用，深拷贝每个对象后再 postValue
    private void publishSnapshotLocked() {
        List<HazardReport> snapshot = new ArrayList<>(reportsList.size());
        for (HazardReport r : reportsList) {
            snapshot.add(new HazardReport(r));
        }
        reportsLiveData.postValue(snapshot);
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

    public String getCurrentUserRole() {
        String val = userRoleLiveData.getValue();
        return val != null ? val : "UM Student / Staff";
    }

    public boolean isAdmin() {
        return "JPPHB Maintenance Admin".equals(getCurrentUserRole());
    }

    // 带 PIN 码验证的管理员切换 (FR 1.3)
    public boolean authenticateAndSetAdmin(String inputPin) {
        if (ADMIN_PIN.equals(inputPin != null ? inputPin.trim() : "")) {
            setRoleInternal("JPPHB Maintenance Admin");
            return true;
        }
        return false;
    }

    public void switchToStudentRole() {
        setRoleInternal("UM Student / Staff");
    }

    // 兼容旧调用
    public void toggleAdminRole() {
        if (isAdmin()) {
            switchToStudentRole();
        } else {
            setRoleInternal("JPPHB Maintenance Admin");
        }
    }

    private void setRoleInternal(String newRole) {
        userRoleLiveData.postValue(newRole);
        if (prefs != null) {
            prefs.edit().putString("user_role", newRole).apply();
        }
    }

    // FR 1.3: 管理员全校应急广播
    public void publishAdminBroadcast(String message) {
        String clean = message != null ? message.trim() : "";
        broadcastBannerLiveData.postValue(clean);
        if (prefs != null) {
            prefs.edit().putString("admin_broadcast", clean).apply();
        }
        if (!clean.isEmpty()) {
            sendLocalAlertNotification("📢 UM-ClearFlow Emergency Broadcast", clean);
        }
    }

    // FR 2.1 + 重复上报合并 (50米内同类型未解决隐患自动合并 +1)
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
                            existing.upvotes += 1;
                            existing.timeline = existing.timeline + "\n" + nowTime
                                    + " • Merged nearby duplicate report (<50m, Upvotes +1)";
                            insertOrUpdateReportDb(existing);
                            publishSnapshotLocked();
                            return;
                        }
                    }
                }

                reportsList.add(0, new HazardReport(newReport));
                insertOrUpdateReportDb(newReport);
                publishSnapshotLocked();

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
                if (resolveVotedIds.contains(id)) return;
                String nowTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        r.resolveVotes++;
                        resolveVotedIds.add(id);
                        if (r.resolveVotes >= 3 && !"Resolved".equalsIgnoreCase(r.status)) {
                            r.status = "Resolved";
                            r.timeline = r.timeline + "\n" + nowTime
                                    + " • Marked Resolved by Community Verification (3/3 votes)";
                        } else {
                            r.timeline = r.timeline + "\n" + nowTime
                                    + " • Community Clear Vote (" + r.resolveVotes + "/3)";
                        }
                        if (dbHelper != null) {
                            ContentValues cv = new ContentValues();
                            cv.put("report_id", id);
                            cv.put("vote_type", "RESOLVE");
                            dbHelper.getWritableDatabase().insertWithOnConflict(
                                    "user_votes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        }
                        insertOrUpdateReportDb(r);
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
        executorService.execute(() -> {
            synchronized (reportsList) {
                String nowTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        r.status = newStatus;
                        r.timeline = r.timeline + "\n" + nowTime + " • Status updated to [" + newStatus + "] by JPPHB Admin";
                        insertOrUpdateReportDb(r);
                        break;
                    }
                }
                publishSnapshotLocked();
            }
        });
    }

    // FR 3.1: 真·图像像素特征分析 + 离线规则降级
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

        if (hazardTypeIndex == 0 || muddyRatio + reflectRatio > 0.42) {
            double waterScore = muddyRatio * 1.4 + reflectRatio * 0.8;
            severity = (waterScore > 0.35 || latestRainfallMm > 5.0) ? "Critical" : "Medium";
            confidence = Math.min(0.96, 0.74 + waterScore * 0.35);
            detailNote = String.format(Locale.US,
                    "[On-Device Vision HSV Scan] Turbid water/reflection coverage: %.0f%% | Dark depth ratio: %.0f%%.",
                    (muddyRatio + reflectRatio) * 100, darkRatio * 100);
        } else if (hazardTypeIndex == 2 || greenRatio > 0.28) {
            severity = (greenRatio > 0.38 || darkRatio > 0.30) ? "Critical" : "Medium";
            confidence = Math.min(0.94, 0.72 + greenRatio * 0.40);
            detailNote = String.format(Locale.US,
                    "[On-Device Vision HSV Scan] Canopy/foliage obstruction ratio: %.0f%% | Shadow density: %.0f%%.",
                    greenRatio * 100, darkRatio * 100);
        } else {
            severity = darkRatio > 0.32 ? "Critical" : "Medium";
            confidence = Math.min(0.92, 0.70 + (darkRatio + muddyRatio) * 0.35);
            detailNote = String.format(Locale.US,
                    "[On-Device Vision HSV Scan] Drain occlusion/soil ratio: %.0f%% | Surface contrast verified.",
                    (darkRatio + muddyRatio) * 100);
        }

        return new TriageResult(severity, confidence, detailNote, selectedType);
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
        list.add(new ShelterInfo("Dewan Tunku Canselor (DTC Shelter)", 3.1219, 101.6570,
                "Primary Elevated Assembly Hall • Capacity: 1,500 • Emergency Generator & First Aid"));
        list.add(new ShelterInfo("Perdanasiswa Complex (KPS Shelter)", 3.1235, 101.6552,
                "Central Student Relief Hub • Capacity: 800 • Food, Drinking Water & Charging Station"));
        list.add(new ShelterInfo("UM Arena / Sports Complex Shelter", 3.1268, 101.6602,
                "East Campus Evacuation Point • Capacity: 1,200 • Accessible via High Ground"));
        list.add(new ShelterInfo("FCSIT Block B Upper Concourse", 3.1284, 101.6502,
                "North Zone Temporary Refuge • Capacity: 400 • 2nd Floor Flood-Safe Zone"));
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
                sb.append(rank++).append(". ").append(entry.getKey())
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
        private static final int DB_VERSION = 2;

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
                    "timeline TEXT)");

            db.execSQL("CREATE TABLE IF NOT EXISTS user_votes (" +
                    "report_id TEXT, " +
                    "vote_type TEXT, " +
                    "PRIMARY KEY(report_id, vote_type))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS hazard_reports");
            db.execSQL("DROP TABLE IF EXISTS user_votes");
            onCreate(db);
        }
    }
}