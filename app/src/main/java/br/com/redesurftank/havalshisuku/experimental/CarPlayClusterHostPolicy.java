package br.com.redesurftank.havalshisuku.experimental;

public final class CarPlayClusterHostPolicy {
    public static final long MAX_SNAPSHOT_AGE_MS = 1000;
    public enum Reason {
        ALLOWED, SNAPSHOT_MISSING, TASK_OBSERVATION_UNKNOWN, SNAPSHOT_FUTURE, SNAPSHOT_STALE,
        WRONG_DISPLAY, EXPERIMENT_DISABLED, SLOT_NOT_OWNED, AA_NOT_KNOWN_INACTIVE,
        AA_SESSION_ACTIVE, AA_SURFACE_ATTACHED, CARPLAY_MAIN_UNCONFIRMED, CARPLAY_SECONDARY_PRESENT,
        SAFETY_UI_ACTIVE, OTHER_APP_ACTIVE, SLOT_OCCUPIED, SLOT_DETACHED, SLOT_NOT_ACCELERATED,
        SLOT_HIDDEN, SLOT_NOT_LAID_OUT, SLOT_SIZE_INVALID, SNAPSHOT_ERROR
    }

    public static final class Snapshot {
        public final long observedAtElapsedMs;
        public final int displayId;
        public final boolean explicitlyEnabled;
        public final boolean slotOwned;
        public final boolean androidAutoKnownInactive;
        public final boolean carPlayOnMain;
        public final boolean safetyUiClear;
        public final boolean otherAppsClear;
        public final boolean taskObservationKnown;
        public final boolean carPlayOnSecondary;
        public final boolean androidAutoRequested;

        public Snapshot(long observedAtElapsedMs, int displayId, boolean explicitlyEnabled,
                        boolean slotOwned, boolean androidAutoKnownInactive, boolean carPlayOnMain,
                        boolean safetyUiClear, boolean otherAppsClear) {
            this(observedAtElapsedMs, displayId, explicitlyEnabled, slotOwned, androidAutoKnownInactive,
                carPlayOnMain, safetyUiClear, otherAppsClear, true, false, false);
        }

        public Snapshot(long observedAtElapsedMs, int displayId, boolean explicitlyEnabled,
                        boolean slotOwned, boolean androidAutoKnownInactive, boolean carPlayOnMain,
                        boolean safetyUiClear, boolean otherAppsClear, boolean taskObservationKnown,
                        boolean carPlayOnSecondary, boolean androidAutoRequested) {
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.displayId = displayId;
            this.explicitlyEnabled = explicitlyEnabled;
            this.slotOwned = slotOwned;
            this.androidAutoKnownInactive = androidAutoKnownInactive;
            this.carPlayOnMain = carPlayOnMain;
            this.safetyUiClear = safetyUiClear;
            this.otherAppsClear = otherAppsClear;
            this.taskObservationKnown = taskObservationKnown;
            this.carPlayOnSecondary = carPlayOnSecondary;
            this.androidAutoRequested = androidAutoRequested;
        }
    }

    private CarPlayClusterHostPolicy() {
    }

    public static boolean allows(Snapshot snapshot, long nowElapsedMs) {
        return reason(snapshot, nowElapsedMs) == Reason.ALLOWED;
    }

    public static Reason reason(Snapshot snapshot, long nowElapsedMs) {
        if (snapshot == null) return Reason.SNAPSHOT_MISSING;
        if (!snapshot.taskObservationKnown || snapshot.observedAtElapsedMs < 0) return Reason.TASK_OBSERVATION_UNKNOWN;
        if (nowElapsedMs < snapshot.observedAtElapsedMs) return Reason.SNAPSHOT_FUTURE;
        if (nowElapsedMs - snapshot.observedAtElapsedMs > MAX_SNAPSHOT_AGE_MS) return Reason.SNAPSHOT_STALE;
        if (snapshot.displayId != 3) return Reason.WRONG_DISPLAY;
        if (!snapshot.explicitlyEnabled) return Reason.EXPERIMENT_DISABLED;
        if (!snapshot.slotOwned) return Reason.SLOT_NOT_OWNED;
        if (!snapshot.androidAutoKnownInactive) return Reason.AA_NOT_KNOWN_INACTIVE;
        if (snapshot.carPlayOnSecondary) return Reason.CARPLAY_SECONDARY_PRESENT;
        if (!snapshot.carPlayOnMain) return Reason.CARPLAY_MAIN_UNCONFIRMED;
        if (!snapshot.safetyUiClear) return Reason.SAFETY_UI_ACTIVE;
        if (!snapshot.otherAppsClear) return Reason.OTHER_APP_ACTIVE;
        return Reason.ALLOWED;
    }
}
