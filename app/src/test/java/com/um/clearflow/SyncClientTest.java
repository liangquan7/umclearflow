package com.um.clearflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Needs  testImplementation("org.json:json:20240303")  so that org.json really parses on the JVM
 * (the android.jar stubs do nothing). See PATCHES_SYNC.md.
 */
public class SyncClientTest {

    @Test
    public void reportSerialisesToServerSchema() throws Exception {
        HazardReport r = new HazardReport(
                "CF-ABC12345", "Blocked drain", "leaves", "FCSIT & Central Library (3.1281, 101.6505)",
                "Blocked Drain", "Critical", "Submitted", 3.1281, 101.6505, "10 Nov, 10:00",
                1, 0, 1.7, "note", "ali@siswa.um.edu.my", "10:00 • Submitted", "");
        JSONObject o = SyncClient.reportToJson(r, "device-1234567890");
        assertEquals("CF-ABC12345", o.getString("id"));
        assertEquals("Blocked Drain", o.getString("hazard_type"));
        assertEquals("device-1234567890", o.getString("device_id"));
        assertEquals("confidence must be clamped to the server's 0..1 range", 1.0, o.getDouble("ai_confidence"), 1e-9);
        assertEquals(3.1281, o.getDouble("latitude"), 1e-9);
    }

    @Test
    public void remoteJsonParsesIntoReportAndFlags() throws Exception {
        JSONObject o = new JSONObject()
                .put("id", "CF-REMOTE01").put("title", "Fallen branch").put("description", "d")
                .put("location_name", "KK12").put("hazard_type", "Fallen Tree").put("severity", "Medium")
                .put("status", "Verified").put("latitude", 3.1338).put("longitude", 101.6518)
                .put("timestamp", "10 Nov, 09:00").put("upvotes", 4).put("resolve_votes", 2)
                .put("ai_confidence", 0.8).put("ai_triage_note", "n").put("reporter_email", "a***@siswa.um.edu.my")
                .put("timeline", "09:00 • Submitted").put("created_at", 1760000000000L)
                .put("has_photo", true).put("my_upvote", true).put("my_resolve", false);

        SyncClient.Remote rm = SyncClient.parseRemote(o);
        assertEquals("CF-REMOTE01", rm.report.id);
        assertEquals("Verified", rm.report.status);
        assertEquals(4, rm.report.upvotes);
        assertEquals(2, rm.report.resolveVotes);
        assertEquals(1760000000000L, rm.report.createdAt);
        assertTrue(rm.hasPhoto);
        assertTrue(rm.myUpvote);
        assertFalse(rm.myResolve);
        assertEquals("", rm.report.photoUri);
    }

    @Test
    public void missingFieldsFallBackToSafeDefaults() throws Exception {
        SyncClient.Remote rm = SyncClient.parseRemote(new JSONObject().put("id", "CF-MIN00001"));
        assertEquals("Submitted", rm.report.status);
        assertEquals(0, rm.report.upvotes);
        assertFalse(rm.hasPhoto);
    }
}
