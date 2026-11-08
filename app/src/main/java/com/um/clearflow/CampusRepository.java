package com.um.clearflow;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CampusRepository {
    private static CampusRepository instance;

    private final MutableLiveData<List<HazardReport>> reportsLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> userRoleLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> userEmailLiveData = new MutableLiveData<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    private final List<HazardReport> reportsList = new ArrayList<>();

    private CampusRepository() {
        userEmailLiveData.setValue("24231773@siswa.um.edu.my");
        userRoleLiveData.setValue("Student (FCSIT)");
        seedInitialUmData();
    }

    public static synchronized CampusRepository getInstance() {
        if (instance == null) {
            instance = new CampusRepository();
        }
        return instance;
    }

    private void seedInitialUmData() {
        reportsList.add(new HazardReport(
                "UM-101",
                "[Demo Data] Severe Road Flooding near KL Gate",
                "Flash Flood",
                "Critical",
                "Jalan Lingkungan Budi (Near KL Gate & Rimba Ilmu)",
                3.1285, 101.6582,
                "[Demo Data] Assessment: Flash Flood. Water depth estimated > 25cm. Unsafe for compact vehicles.",
                0.95,
                "In Progress (JPPHB)",
                24,
                "24231773@siswa.um.edu.my",
                "10 mins ago",
                true
        ));
        reportsList.add(new HazardReport(
                "UM-102",
                "[Demo Data] Storm Drain Blocked by Leaves",
                "Blocked Drain",
                "Medium",
                "Walkway outside FCSIT Block A",
                3.1281, 101.6505,
                "[Demo Data] Assessment: Blocked Drain. Grate obstructed by wet leaves.",
                0.90,
                "Pending",
                11,
                "ali.ahmad@siswa.um.edu.my",
                "35 mins ago",
                true
        ));
        reportsList.add(new HazardReport(
                "UM-103",
                "[Demo Data] Fallen Tree Branch Blocking Bus Stop",
                "Fallen Tree",
                "Critical",
                "Jalan Ilmu (Near KK8 / Kinabalu Residential College)",
                3.1268, 101.6462,
                "[Demo Data] Assessment: Fallen Tree. Large limb obstructing pedestrian shelter.",
                0.93,
                "In Progress (JPPHB)",
                19,
                "tan.mei@siswa.um.edu.my",
                "1 hour ago",
                true
        ));
        reportsList.add(new HazardReport(
                "UM-104",
                "[Demo Data] Overflowing Roadside Drain",
                "Blocked Drain",
                "Low",
                "Dewan Tunku Canselor (DTC) Parking Lot",
                3.1209, 101.6538,
                "[Demo Data] Assessment: Blocked Drain. Minor silt accumulation observed.",
                0.85,
                "Resolved",
                6,
                "staff_fcsit@um.edu.my",
                "Yesterday",
                true
        ));
        reportsLiveData.setValue(new ArrayList<>(reportsList));
    }

    public LiveData<List<HazardReport>> getReportsLiveData() {
        return reportsLiveData;
    }

    public LiveData<String> getUserRoleLiveData() {
        return userRoleLiveData;
    }

    public LiveData<String> getUserEmailLiveData() {
        return userEmailLiveData;
    }

    public String getCurrentUserEmail() {
        return userEmailLiveData.getValue();
    }

    public String getCurrentUserRole() {
        return userRoleLiveData.getValue();
    }

    public void setUserRole(String role) {
        userRoleLiveData.setValue(role);
    }

    public void addReport(HazardReport report) {
        executorService.execute(() -> {
            synchronized (reportsList) {
                reportsList.add(0, report);
            }
            reportsLiveData.postValue(new ArrayList<>(reportsList));
        });
    }

    public void upvoteReport(String id) {
        executorService.execute(() -> {
            synchronized (reportsList) {
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        r.upvotes++;
                        break;
                    }
                }
            }
            reportsLiveData.postValue(new ArrayList<>(reportsList));
        });
    }

    public void updateReportStatus(String id, String newStatus, boolean synced) {
        executorService.execute(() -> {
            synchronized (reportsList) {
                for (HazardReport r : reportsList) {
                    if (r.id.equals(id)) {
                        r.status = newStatus;
                        r.isSynced = synced;
                        break;
                    }
                }
            }
            reportsLiveData.postValue(new ArrayList<>(reportsList));
        });
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }
}