package br.com.redesurftank.havalshisuku.experimental;

public final class CarPlayClusterHostPolicy {
    public static final long MAX_SNAPSHOT_AGE_MS = 1000;

    public static final class Snapshot {
        public final long observedAtElapsedMs;
        public final int displayId;
        public final boolean explicitlyEnabled;
        public final boolean slotOwned;
        public final boolean androidAutoKnownInactive;
        public final boolean carPlayOnMain;
        public final boolean safetyUiClear;
        public final boolean otherAppsClear;

        public Snapshot(long observedAtElapsedMs, int displayId, boolean explicitlyEnabled,
                        boolean slotOwned, boolean androidAutoKnownInactive, boolean carPlayOnMain,
                        boolean safetyUiClear, boolean otherAppsClear) {
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.displayId = displayId;
            this.explicitlyEnabled = explicitlyEnabled;
            this.slotOwned = slotOwned;
            this.androidAutoKnownInactive = androidAutoKnownInactive;
            this.carPlayOnMain = carPlayOnMain;
            this.safetyUiClear = safetyUiClear;
            this.otherAppsClear = otherAppsClear;
        }
    }

    private CarPlayClusterHostPolicy() {
    }

    public static boolean allows(Snapshot snapshot, long nowElapsedMs) {
        if (snapshot == null || snapshot.observedAtElapsedMs < 0 || nowElapsedMs < snapshot.observedAtElapsedMs) {
            return false;
        }
        return nowElapsedMs - snapshot.observedAtElapsedMs <= MAX_SNAPSHOT_AGE_MS && snapshot.displayId == 3 &&
            snapshot.explicitlyEnabled && snapshot.slotOwned && snapshot.androidAutoKnownInactive &&
            snapshot.carPlayOnMain && snapshot.safetyUiClear && snapshot.otherAppsClear;
    }
}
