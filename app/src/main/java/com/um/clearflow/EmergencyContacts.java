package com.um.clearflow;

/**
 * Single source of truth for emergency numbers (previously duplicated in MainActivity,
 * WeatherAiViewModel and the backend). Verified against UM Registrar / Security Office pages:
 *  - Security Office hotline (24h): 03-7967 7070
 *  - UM Clinic: 03-7967 3544 / 6444   (the old value 6445 was not an official number)
 * Re-verify before any real deployment.
 */
public final class EmergencyContacts {
    private EmergencyContacts() {}

    public static final String NATIONAL_EMERGENCY = "999";

    public static final String UM_SECURITY_DISPLAY = "03-7967 7070";
    public static final String UM_SECURITY_DIAL = "0379677070";

    public static final String UMMC_DISPLAY = "03-7949 4422";
    public static final String UMMC_DIAL = "0379494422";

    public static final String UM_CLINIC_DISPLAY = "03-7967 6444";
    public static final String UM_CLINIC_DIAL = "0379676444";
}
