package br.com.redesurftank.havalshisuku.experimental;

/** Pure, bounded admission ledger. Once a consumer starts, automatic retries end. */
public final class CarPlayCleanMapAdmission {
    public static final long OFFER_MAX_AGE_MS = 5000;
    public static final long RETRY_BACKOFF_MS = 1000;
    public static final long REQUEST_MAX_WAIT_MS = 30000;
    public static final int MAX_REQUESTS = 3;
    public enum Action { WAIT, REQUEST, REVOKE, START, TERMINAL, EXHAUSTED, STOP }

    private final long deadline;
    private int requests;
    private long requestedAt = -1;
    private long revokedAt = -1;
    private boolean awaitingOffer;
    private boolean revoked;
    private boolean consumerStarted;

    public CarPlayCleanMapAdmission(long deadlineElapsedMs) {
        if (deadlineElapsedMs <= 0) throw new IllegalArgumentException("Explicit deadline required");
        deadline = deadlineElapsedMs;
    }

    public Action evaluate(long now, boolean eligible, long offeredAt, boolean endpointAlive) {
        if (now < 0 || now >= deadline) return Action.STOP;
        if (consumerStarted) return Action.TERMINAL;
        if (offeredAt >= 0) {
            if (!endpointAlive || now < offeredAt || now - offeredAt > OFFER_MAX_AGE_MS) return Action.REVOKE;
            return eligible ? Action.START : Action.WAIT;
        }
        if (awaitingOffer) return now - requestedAt >= REQUEST_MAX_WAIT_MS ? Action.REVOKE : Action.WAIT;
        if (revoked) {
            if (requests >= MAX_REQUESTS) return Action.EXHAUSTED;
            return eligible && now - revokedAt >= RETRY_BACKOFF_MS ? Action.REQUEST : Action.WAIT;
        }
        return requests == 0 && eligible ? Action.REQUEST : Action.WAIT;
    }

    public void requested(long now) {
        if (consumerStarted || requests >= MAX_REQUESTS || now < 0 || now >= deadline || awaitingOffer ||
                (requests > 0 && !revoked) ||
                (revoked && now - revokedAt < RETRY_BACKOFF_MS)) {
            throw new IllegalStateException("Request outside the bounded admission ledger");
        }
        requests++;
        requestedAt = now;
        awaitingOffer = true;
        revoked = false;
    }

    public boolean acceptsOffer(long now) {
        return !consumerStarted && awaitingOffer && now >= requestedAt && now < deadline &&
            now - requestedAt < REQUEST_MAX_WAIT_MS;
    }

    public void offered() {
        if (!awaitingOffer || consumerStarted) throw new IllegalStateException("No pending request");
        awaitingOffer = false;
    }
    public void revoked(long now) {
        if (consumerStarted) throw new IllegalStateException("Consumer retirement cannot rearm admission");
        awaitingOffer = false; revoked = true; revokedAt = now;
    }
    public void consumerStarted() { consumerStarted = true; awaitingOffer = false; }
    public boolean hasStartedConsumer() { return consumerStarted; }
    public int requestCount() { return requests; }
}
