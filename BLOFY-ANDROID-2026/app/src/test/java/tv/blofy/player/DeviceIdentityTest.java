package tv.blofy.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DeviceIdentityTest {
    @Test public void v323IdentityIsPreservedForTheMigrationRequest() {
        assertEquals("BLOFY-SV",
                DeviceIdentity.registrationDisplayId("blofy-sv", "BLOFY-66HL-GB09"));
        assertEquals("772413",
                DeviceIdentity.registrationPairingCode("772413", "000000"));
    }

    @Test public void canonicalV324IdentityRemainsStable() {
        assertEquals("BLOFY-66HL-GB09",
                DeviceIdentity.registrationDisplayId("BLOFY-66HL-GB09", "BLOFY-ABCD-1234"));
    }

    @Test public void invalidLegacyValuesUseTheGeneratedCandidates() {
        assertEquals("BLOFY-66HL-GB09",
                DeviceIdentity.registrationDisplayId("BLOFY-S", "BLOFY-66HL-GB09"));
        assertEquals("135790",
                DeviceIdentity.registrationPairingCode("77241", "135790"));
    }

    @Test public void freshPrivateIdUsesTheRequiredPersistableFormat() {
        byte[] bytes = new byte[]{0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};
        String value = DeviceIdentity.formatPrivateId(bytes);
        assertEquals("BLOFY-0123-4567-89AB-CDEF", value);
        assertTrue(DeviceIdentity.isPrivateId(value));
        assertFalse(DeviceIdentity.isPrivateId("BLOFY-SV"));
    }

    @Test public void recoveryFallbackIsLimitedToTheExplicitServerConflict() {
        assertTrue(BlofyApi.isDeviceRecoveryConflict(
                new BlofyApi.ApiException(500, "تعذر استعادة الجهاز. تحقق من رقم الجهاز ورمز الربط.")));
        assertTrue(BlofyApi.isDeviceRecoveryConflict(
                new BlofyApi.ApiException(409, "DEVICE_IDENTITY_CONFLICT", "device identity conflict")));
        assertFalse(BlofyApi.isDeviceRecoveryConflict(
                new BlofyApi.ApiException(500, "SOME_OTHER_CONFLICT",
                        "تعذر استعادة الجهاز. تحقق من رقم الجهاز ورمز الربط.")));
        assertFalse(BlofyApi.isDeviceRecoveryConflict(
                new BlofyApi.ApiException(409, "SOME_OTHER_CONFLICT", "different conflict")));
        assertFalse(BlofyApi.isDeviceRecoveryConflict(
                new BlofyApi.ApiException(503, "الخادم غير متاح")));
        assertFalse(BlofyApi.isDeviceRecoveryConflict(
                new BlofyApi.ApiException(503,
                        "تعذر استعادة الجهاز. تحقق من رقم الجهاز ورمز الربط.")));
        assertFalse(BlofyApi.isDeviceRecoveryConflict(
                new Exception("تعذر استعادة الجهاز. تحقق من رقم الجهاز ورمز الربط.")));
        assertFalse(BlofyApi.isDeviceRecoveryConflict(new java.io.IOException("network timeout")));
    }
}
