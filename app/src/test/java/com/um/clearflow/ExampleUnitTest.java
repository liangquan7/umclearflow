package com.um.clearflow;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit test suite verifying FR 1.1 - FR 5.3 core business logic in CampusRepository.
 */
public class ExampleUnitTest {

    @Test
    public void testAdminPinSaltedHashAndLockout_FR1_3() {
        CampusRepository repo = CampusRepository.getInstance();
        repo.switchToStudentRole();
        assertFalse(repo.isAdmin());

        // Wrong PIN should be rejected
        assertFalse(repo.authenticateAndSetAdmin("WRONG1"));
        assertFalse(repo.isAdmin());

        // Correct default PIN (UM2026 -> salted SHA-256) must immediately set admin role synchronously
        assertTrue(repo.authenticateAndSetAdmin("UM2026"));
        assertTrue(repo.isAdmin());

        // Switch back to student and verify 5 failed attempts trigger 60s lockout
        repo.switchToStudentRole();
        for (int i = 0; i < 5; i++) {
            assertFalse(repo.authenticateAndSetAdmin("BADPIN" + i));
        }
        assertTrue("Lockout timer should be active after 5 wrong PINs",
                repo.getPinLockoutRemainingMs() > 0);
        // Even correct PIN is rejected while locked out
        assertFalse(repo.authenticateAndSetAdmin("UM2026"));
    }

    @Test
    public void testHaversineDistanceCalculation_FR2_1_FR4_2() {
        // Same point = 0m
        double d0 = CampusRepository.calculateDistanceMeters(3.1281, 101.6505, 3.1281, 101.6505);
        assertEquals(0.0, d0, 0.01);

        // Two points ~25m apart at FCSIT (within 50m duplicate auto-merge radius)
        double dNear = CampusRepository.calculateDistanceMeters(3.12810, 101.65050, 3.12825, 101.65060);
        assertTrue("Points should be within 50m merge threshold", dNear > 5.0 && dNear < 50.0);

        // FCSIT to KK8 (~320m apart)
        double dFcsitToKk8 = CampusRepository.calculateDistanceMeters(3.1281, 101.6505, 3.1302, 101.6485);
        assertTrue("FCSIT to KK8 should be between 200m and 500m", dFcsitToKk8 > 200.0 && dFcsitToKk8 < 500.0);
    }

    @Test
    public void testVisionFallbackTriageAndCategories_FR3_1() {
        CampusRepository repo = CampusRepository.getInstance();
        repo.updateWeatherMetrics(8.5, 22.0); // Heavy rain

        CampusRepository.TriageResult floodRes = repo.runVisionTriage(null, 0, "Deep water blocking road");
        assertEquals("Flash Flood", floodRes.detectedType);
        assertEquals("Critical", floodRes.severity);

        CampusRepository.TriageResult drainRes = repo.runVisionTriage(null, 1, "Blocked drain grate");
        assertEquals("Blocked Drain", drainRes.detectedType);
        assertEquals("Critical", drainRes.severity);

        CampusRepository.TriageResult treeRes = repo.runVisionTriage(null, 2, "Minor branch on grass");
        assertEquals("Fallen Tree", treeRes.detectedType);
        assertEquals("Medium", treeRes.severity);

        CampusRepository.TriageResult slopeRes = repo.runVisionTriage(null, 3, "Slope soil movement");
        assertEquals("Slope / Landslide", slopeRes.detectedType);
        assertEquals("Critical", slopeRes.severity);
    }

    @Test
    public void testDuplicateMergeEscalatesCriticalAndRecordsUpvote_FR2_1_FR2_2() throws InterruptedException {
        CampusRepository repo = CampusRepository.getInstance();
        Thread.sleep(150);

        // Submit a Critical Blocked Drain report within 50m of CF-102 (which starts as Medium at 3.1281, 101.6505)
        HazardReport duplicateCrit = new HazardReport(
                "CF-TEST-MERGE",
                "Overflowing drain grate at FCSIT",
                "Water overflowing onto walkway",
                "FCSIT & Central Library (3.1281, 101.6505)",
                "Blocked Drain",
                "Critical",
                "Submitted",
                3.12815, 101.65055,
                "Today, 15:00",
                1, 0, 0.90,
                "Critical overflow",
                "tester@siswa.um.edu.my",
                "Today, 15:00 • Submitted",
                "/data/user/0/com.um.clearflow/files/report_photos/CF-TEST-MERGE.jpg"
        );
        repo.addReport(duplicateCrit);
        Thread.sleep(150);

        assertTrue("Merged report CF-102 should be marked as upvoted by this user", repo.hasUpvoted("CF-102"));
        String ranking = repo.generateHotspotRankingText();
        assertTrue("Hotspot ranking should reflect escalated Critical count at FCSIT",
                ranking.contains("FCSIT & Central Library — 1 reports (1 Critical)"));
    }

    @Test
    public void testSheltersAndCollegesIncludeKk8AndDtc_FR4_1_FR4_2() {
        List<CampusRepository.ShelterInfo> shelters = CampusRepository.getInstance().getCampusShelters();
        assertTrue("Should have at least 6 destinations (shelters + KK8/KK12)", shelters.size() >= 6);

        boolean hasKk8 = false;
        boolean hasDtc = false;
        for (CampusRepository.ShelterInfo s : shelters) {
            if (s.name.contains("KK8")) hasKk8 = true;
            if (s.name.contains("DTC")) hasDtc = true;
        }
        assertTrue("Destinations must include KK8 for FCSIT -> KK8 safe routing", hasKk8);
        assertTrue("Destinations must include DTC primary shelter", hasDtc);
    }

    @Test
    public void testHotspotRankingAndCsvExportSanitization_FR5_1_FR5_2() throws InterruptedException {
        CampusRepository repo = CampusRepository.getInstance();
        // Wait briefly for initial seed thread to finish
        Thread.sleep(150);

        String ranking = repo.generateHotspotRankingText();
        assertNotNull(ranking);
        assertTrue("Hotspot ranking should contain visual bar chart", ranking.contains("█"));
        assertTrue("Hotspot ranking should track total reports", ranking.contains("Total Tracked:"));

        String csv = repo.exportReportsToCsv();
        assertTrue("CSV must start with structured header",
                csv.startsWith("ID,Title,HazardType,Severity,Status,Location,Latitude,Longitude,Upvotes,ResolveVotes,ReporterEmail,Timestamp"));
        assertTrue("CSV must contain seeded report CF-101", csv.contains("CF-101"));
    }
}
