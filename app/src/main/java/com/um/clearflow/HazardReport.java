package com.um.clearflow;

public class HazardReport {
    public String id;
    public String title;
    public String description;
    public String locationName;
    public String hazardType;      // "Flash Flood", "Fallen Tree", "Blocked Drain", "Slope / Landslide"
    public String severity;        // "Low", "Medium", "Critical"
    public String status;          // "Submitted", "Verified", "In Progress - JPPHB", "Resolved"
    public double latitude;
    public double longitude;
    public String timestamp;
    public int upvotes;
    public int resolveVotes;       // FR 2.2: 众包"已解除"投票数 (满3票自动变为 Resolved)
    public double aiConfidence;
    public String aiTriageNote;
    public String reporterEmail;   // FR 2.3: 绑定上报人邮箱，支持筛选"我的上报"
    public String timeline;        // FR 2.3: 工单状态流转时间线记录

    public HazardReport(String id, String title, String description, String locationName,
                        String hazardType, String severity, String status,
                        double latitude, double longitude, String timestamp,
                        int upvotes, double aiConfidence, String aiTriageNote) {
        this(id, title, description, locationName, hazardType, severity, status,
                latitude, longitude, timestamp, upvotes, 0, aiConfidence, aiTriageNote,
                "demo.student@siswa.um.edu.my", timestamp + " • Report Submitted");
    }

    public HazardReport(String id, String title, String description, String locationName,
                        String hazardType, String severity, String status,
                        double latitude, double longitude, String timestamp,
                        int upvotes, int resolveVotes, double aiConfidence, String aiTriageNote,
                        String reporterEmail, String timeline) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.locationName = locationName;
        this.hazardType = hazardType;
        this.severity = severity;
        this.status = status;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
        this.upvotes = upvotes;
        this.resolveVotes = resolveVotes;
        this.aiConfidence = aiConfidence;
        this.aiTriageNote = aiTriageNote;
        this.reporterEmail = reporterEmail != null ? reporterEmail : "anonymous@siswa.um.edu.my";
        this.timeline = timeline != null ? timeline : (timestamp + " • Submitted");
    }

    // 深拷贝构造函数：防止 UI 线程与后台线程并发修改同一个对象引用
    public HazardReport(HazardReport other) {
        this.id = other.id;
        this.title = other.title;
        this.description = other.description;
        this.locationName = other.locationName;
        this.hazardType = other.hazardType;
        this.severity = other.severity;
        this.status = other.status;
        this.latitude = other.latitude;
        this.longitude = other.longitude;
        this.timestamp = other.timestamp;
        this.upvotes = other.upvotes;
        this.resolveVotes = other.resolveVotes;
        this.aiConfidence = other.aiConfidence;
        this.aiTriageNote = other.aiTriageNote;
        this.reporterEmail = other.reporterEmail;
        this.timeline = other.timeline;
    }
}