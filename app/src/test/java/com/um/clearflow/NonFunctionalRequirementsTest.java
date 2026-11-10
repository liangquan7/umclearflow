package com.um.clearflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence for the non-functional requirements (performance under load, data security).
 * Run: ./gradlew testDebugUnitTest   -> attach the HTML report to the project report / video.
 * Needs  testOptions { unitTests { isReturnDefaultValues = true } }  in app/build.gradle.kts.
 */
public class NonFunctionalRequirementsTest {

    private static int trackedTotal(String ranking) {
        Matcher m = Pattern.compile("Total Tracked: (\\d+)").matcher(ranking);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** NFR-Performance: heavy campus usage must not stall the UI-facing logic. */
    @Test
    public void triageAndDistanceStayFastUnderLoad() {
        CampusRepository repo = CampusRepository.getInstance();

        long t0 = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            CampusRepository.calculateDistanceMeters(3.1281, 101.6505, 3.1302 + i * 1e-7, 101.6485);
        }
        long distMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("100k Haversine calls took " + distMs + " ms", distMs < 500);

        long t1 = System.nanoTime();
        for (int i = 0; i < 2_000; i++) {
            repo.runVisionTriage(null, i % 4, "Overflowing drain " + i);
        }
        long triageMs = (System.nanoTime() - t1) / 1_000_000;
        assertTrue("2000 offline triages took " + triageMs + " ms", triageMs < 500);
    }

    /** NFR-Performance/Reliability: 150 reports submitted at once are all stored, none lost or corrupted. */
    @Test
    public void concurrentReportSubmissionsAreAllStored() throws InterruptedException {
        CampusRepository repo = CampusRepository.getInstance();
        Thread.sleep(150);
        int before = trackedTotal(repo.generateHotspotRankingText());
        assertTrue(before >= 0);

        final int n = 150;
        Thread[] workers = new Thread[10];
        for (int w = 0; w < workers.length; w++) {
            final int base = w * (n / workers.length);
            workers[w] = new Thread(() -> {
                for (int i = 0; i < n / workers.length; i++) {
                    int k = base + i;
                    // ~111 m apart -> outside the 50 m duplicate-merge radius
                    repo.addReport(new HazardReport(
                            "CF-LOAD-" + k, "Load test " + k, "load", "Load Test Zone (0,0)",
                            "Flash Flood", "Medium", "Submitted",
                            4.0 + k * 0.001, 101.0, "01 Jan, 10:00",
                            1, 0.8, "note"));
                }
            });
            workers[w].start();
        }
        for (Thread t : workers) t.join();

        long deadline = System.currentTimeMillis() + 5000;
        int after = before;
        while (System.currentTimeMillis() < deadline) {
            after = trackedTotal(repo.generateHotspotRankingText());
            if (after >= before + n) break;
            Thread.sleep(50);
        }
        assertEquals("Every submitted report must be stored", before + n, after);
    }

    /** NFR-Security: user-controlled text must not become a spreadsheet formula in the admin CSV export. */
    @Test
    public void csvExportNeutralisesFormulaInjection() throws InterruptedException {
        CampusRepository repo = CampusRepository.getInstance();
        repo.addReport(new HazardReport(
                "CF-INJECT-1", "=HYPERLINK(\"http://evil.example\",\"click\")", "x",
                "Faculty of Engineering (0,0)", "Fallen Tree", "Medium", "Submitted",
                5.5, 102.5, "01 Jan, 10:00", 1, 0.8, "note"));
        Thread.sleep(200);

        String csv = repo.exportReportsToCsv();
        assertTrue(csv.contains("\"'=HYPERLINK"));
        assertFalse("Raw formula must not start a cell", csv.contains(",\"=HYPERLINK"));
    }
}
