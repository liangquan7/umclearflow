package com.um.clearflow;

import java.io.Serializable;

public class HazardReport implements Serializable {
    public String id;
    public String title;
    public String hazardType;       // "Flash Flood", "Fallen Tree", "Blocked Drain"
    public String severity;         // "Critical", "Medium", "Low"
    public String campusLocation;   // e.g., "Jalan Lingkungan Budi (Near FCSIT)"
    public double latitude;
    public double longitude;
    public String aiAnalysis;       // AI 视觉诊断结论 (FR3.1)
    public double aiConfidence;     // AI 置信度
    public String status;           // "Pending", "In Progress (JPPHB)", "Resolved"
    public int upvotes;             // 社区验证票数 (FR2.2)
    public String reporterEmail;
    public String timestamp;
    public boolean isSynced;        // 用于演示 NFR 断网离线缓存状态

    public HazardReport(String id, String title, String hazardType, String severity,
                        String campusLocation, double latitude, double longitude,
                        String aiAnalysis, double aiConfidence, String status,
                        int upvotes, String reporterEmail, String timestamp, boolean isSynced) {
        this.id = id;
        this.title = title;
        this.hazardType = hazardType;
        this.severity = severity;
        this.campusLocation = campusLocation;
        this.latitude = latitude;
        this.longitude = longitude;
        this.aiAnalysis = aiAnalysis;
        this.aiConfidence = aiConfidence;
        this.status = status;
        this.upvotes = upvotes;
        this.reporterEmail = reporterEmail;
        this.timestamp = timestamp;
        this.isSynced = isSynced;
    }
}