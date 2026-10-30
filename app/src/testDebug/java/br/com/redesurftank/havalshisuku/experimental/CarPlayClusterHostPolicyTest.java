package br.com.redesurftank.havalshisuku.experimental;

import org.junit.Test;

import static org.junit.Assert.*;

public class CarPlayClusterHostPolicyTest {
    private static CarPlayClusterHostPolicy.Snapshot snapshot(long observedAt, int display, int deniedField) {
        return new CarPlayClusterHostPolicy.Snapshot(observedAt, display, deniedField != 0,
            deniedField != 1, deniedField != 2, deniedField != 3, deniedField != 4, deniedField != 5);
    }

    @Test
    public void freshExplicitExclusiveClusterStateIsRequired() {
        assertTrue(CarPlayClusterHostPolicy.allows(snapshot(1000, 3, -1), 1000));
        for (int field = 0; field < 6; field++) {
            assertFalse(CarPlayClusterHostPolicy.allows(snapshot(1000, 3, field), 1000));
        }
    }

    @Test
    public void missingFutureAndStaleObservationsFailClosed() {
        assertFalse(CarPlayClusterHostPolicy.allows(null, 1000));
        assertFalse(CarPlayClusterHostPolicy.allows(snapshot(-1, 3, -1), 1000));
        assertFalse(CarPlayClusterHostPolicy.allows(snapshot(1001, 3, -1), 1000));
        assertFalse(CarPlayClusterHostPolicy.allows(snapshot(1000, 3, -1), 2001));
        assertFalse(CarPlayClusterHostPolicy.allows(snapshot(1000, 3, -1), 3601000));
        assertTrue(CarPlayClusterHostPolicy.allows(snapshot(1000, 3, -1), 2000));
    }

    @Test
    public void otherDisplaysCannotHostTheExperiment() {
        for (int display : new int[]{-1, 0, 1, 2, 4096}) {
            assertFalse(CarPlayClusterHostPolicy.allows(snapshot(1000, display, -1), 1000));
        }
    }

    @Test public void themeDemandAloneDoesNotOverrideFreshExplicitAndroidAutoAbsence() {
        CarPlayClusterHostPolicy.Snapshot explicitInactive = new CarPlayClusterHostPolicy.Snapshot(
            1000, 3, true, true, true, true, true, true, true, false, true);
        assertTrue(CarPlayClusterHostPolicy.allows(explicitInactive, 1000));
        CarPlayClusterHostPolicy.Snapshot unknown = new CarPlayClusterHostPolicy.Snapshot(
            1000, 3, true, true, false, true, true, true, true, false, true);
        assertEquals(CarPlayClusterHostPolicy.Reason.AA_NOT_KNOWN_INACTIVE,
            CarPlayClusterHostPolicy.reason(unknown, 1000));
    }

    @Test public void unknownTaskEvidenceAndSecondaryCarPlayHaveDistinctReasons() {
        assertEquals(CarPlayClusterHostPolicy.Reason.TASK_OBSERVATION_UNKNOWN,
            CarPlayClusterHostPolicy.reason(new CarPlayClusterHostPolicy.Snapshot(
                1000, 3, true, true, true, true, true, true, false, false, false), 1000));
        assertEquals(CarPlayClusterHostPolicy.Reason.CARPLAY_SECONDARY_PRESENT,
            CarPlayClusterHostPolicy.reason(new CarPlayClusterHostPolicy.Snapshot(
                1000, 3, true, true, true, true, true, true, true, true, false), 1000));
    }
}
