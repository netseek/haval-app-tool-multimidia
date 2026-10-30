package br.com.redesurftank.havalshisuku.experimental;

import org.junit.Test;
import static org.junit.Assert.*;
import static br.com.redesurftank.havalshisuku.experimental.CarPlayCleanMapAdmission.Action.*;

public class CarPlayCleanMapAdmissionTest {
    @Test public void requestWaitsForEligibilityAndTheObservedLateOfferIsStillAccepted() {
        CarPlayCleanMapAdmission ledger = new CarPlayCleanMapAdmission(300000);
        assertEquals(WAIT, ledger.evaluate(1000, false, -1, false));
        assertEquals(0, ledger.requestCount());
        assertEquals(REQUEST, ledger.evaluate(2000, true, -1, false));
        ledger.requested(2000);
        assertEquals(WAIT, ledger.evaluate(26000, true, -1, false));
        assertTrue(ledger.acceptsOffer(26000));
        ledger.offered();
        assertEquals(START, ledger.evaluate(26000, true, 26000, true));
    }

    @Test public void transientDenialNeverRenewsTheOriginalFiveSecondOffer() {
        CarPlayCleanMapAdmission ledger = new CarPlayCleanMapAdmission(300000);
        ledger.requested(1000);
        ledger.offered();
        assertEquals(WAIT, ledger.evaluate(2000, false, 1500, true));
        assertEquals(WAIT, ledger.evaluate(6000, false, 1500, true));
        assertEquals(START, ledger.evaluate(6500, true, 1500, true));
        assertEquals(REVOKE, ledger.evaluate(6501, true, 1500, true));
    }

    @Test public void reoffersAreBoundedAndRequireBackoffAndFreshEligibility() {
        CarPlayCleanMapAdmission ledger = new CarPlayCleanMapAdmission(300000);
        long now = 1000;
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(REQUEST, ledger.evaluate(now, true, -1, false));
            ledger.requested(now);
            assertEquals(attempt, ledger.requestCount());
            long expired = now + CarPlayCleanMapAdmission.REQUEST_MAX_WAIT_MS;
            assertFalse(ledger.acceptsOffer(expired));
            assertEquals(REVOKE, ledger.evaluate(expired, false, -1, false));
            ledger.revoked(expired);
            if (attempt < 3) {
                assertEquals(WAIT, ledger.evaluate(expired + 999, true, -1, false));
                assertEquals(WAIT, ledger.evaluate(expired + 1000, false, -1, false));
            }
            now = expired + 1000;
        }
        assertEquals(EXHAUSTED, ledger.evaluate(now, true, -1, false));
        assertFalse(ledger.acceptsOffer(now));
    }

    @Test public void consumerStartPermanentlyEndsAutomaticRequestsEvenAfterPolicyLoss() {
        CarPlayCleanMapAdmission ledger = new CarPlayCleanMapAdmission(300000);
        ledger.requested(1000);
        ledger.offered();
        ledger.consumerStarted();
        assertTrue(ledger.hasStartedConsumer());
        assertEquals(TERMINAL, ledger.evaluate(60000, false, -1, false));
        assertFalse(ledger.acceptsOffer(60000));
        assertThrows(IllegalStateException.class, () -> ledger.requested(61000));
        assertThrows(IllegalStateException.class, () -> ledger.revoked(61000));
    }

    @Test public void absoluteDeadlineStopsWaitingAndActiveConsumers() {
        CarPlayCleanMapAdmission ledger = new CarPlayCleanMapAdmission(5000);
        ledger.requested(1000);
        assertEquals(STOP, ledger.evaluate(5000, true, 4000, true));
        assertFalse(ledger.acceptsOffer(5000));
        ledger.consumerStarted();
        assertEquals(STOP, ledger.evaluate(5001, true, -1, false));
    }

    @Test public void futureOrDeadOffersAreRevokedAndDuplicateRequestsAreRejected() {
        CarPlayCleanMapAdmission ledger = new CarPlayCleanMapAdmission(300000);
        ledger.requested(1000);
        ledger.offered();
        assertEquals(REVOKE, ledger.evaluate(2000, true, 2001, true));
        assertEquals(REVOKE, ledger.evaluate(2000, true, 1500, false));
        assertThrows(IllegalStateException.class, () -> ledger.requested(2000));
    }
}
