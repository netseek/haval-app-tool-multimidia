package br.com.redesurftank.havalshisuku.experimental;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import br.com.redesurftank.havalshisuku.managers.AndroidAutoClusterController;
import br.com.redesurftank.havalshisuku.managers.CarPlayDisplayOrchestrator;
import br.com.redesurftank.havalshisuku.managers.CarPlayDisplayState;

public final class CarPlaySecondaryClusterHost implements AutoCloseable {
    public enum Failure { POLICY_DENIED, START_FAILED, CONSUMER_FAILURE }

    public interface SnapshotProvider {
        CarPlayClusterHostPolicy.Snapshot current();
    }

    public interface Listener {
        void onReady(long generation, int width, int height);
        void onFailure(Failure failure, CarPlaySecondarySurfaceConsumer.Failure consumerFailure);
        void onHidden();
        void onRetired();
    }

    private final FrameLayout slot;
    private final SnapshotProvider snapshots;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CarPlaySecondarySurfaceConsumer consumer;
    private ViewTreeObserver observer;
    private boolean started;
    private boolean closing;
    private boolean hidden;
    private boolean retirementComplete;
    private boolean retiredNotified;

    private final Runnable policyPoll = new Runnable() {
        @Override
        public void run() {
            if (closing) { return; }
            if (!allowed()) { deny(); return; }
            main.postDelayed(this, 250);
        }
    };

    private final ViewTreeObserver.OnPreDrawListener beforeDraw = () -> {
        if (closing) { return true; }
        if (!allowed()) { deny(); return false; }
        return true;
    };

    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View view) {
        }

        @Override
        public void onViewDetachedFromWindow(View view) { close(); }
    };

    public CarPlaySecondaryClusterHost(FrameLayout dedicatedSlot, SnapshotProvider snapshots, Listener listener) {
        if (dedicatedSlot == null || snapshots == null || listener == null) {
            throw new IllegalArgumentException("Dedicated slot, fresh observations and listener required");
        }
        this.slot = dedicatedSlot;
        this.snapshots = snapshots;
        this.listener = listener;
    }

    public boolean start(IBinder endpoint, byte[] token, long generation) {
        requireMain();
        if (started || closing) { throw new IllegalStateException("Host is one-shot"); }
        if (!allowed() || slot.getChildCount() != 0) { deny(); return false; }
        started = true;
        try {
            consumer = new CarPlaySecondarySurfaceConsumer(slot.getContext());
            slot.addView(consumer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            slot.addOnAttachStateChangeListener(attachment);
            observer = slot.getViewTreeObserver();
            observer.addOnPreDrawListener(beforeDraw);
            consumer.start(true, endpoint, token, generation, new CarPlaySecondarySurfaceConsumer.Listener() {
                @Override
                public void onReady(long activeGeneration, int width, int height) {
                    if (closing) { return; }
                    if (!allowed()) { deny(); return; }
                    try { listener.onReady(activeGeneration, width, height); }
                    catch (RuntimeException failure) { close(); }
                }

                @Override
                public void onFailure(CarPlaySecondarySurfaceConsumer.Failure failure) {
                    emitFailure(Failure.CONSUMER_FAILURE, failure);
                    close();
                }

                @Override
                public void onStopped() {
                    retirementComplete = true;
                    close();
                    notifyRetired();
                }
            });
            if (!closing) { main.post(policyPoll); }
            return !closing;
        } catch (RemoteException | RuntimeException failure) {
            emitFailure(Failure.START_FAILED, null);
            close();
            return false;
        }
    }

    public void onInputsChanged() {
        requireMain();
        if (started && !closing && !allowed()) { deny(); }
    }

    public void retryRetirement() {
        requireMain();
        if (closing && consumer != null && !retirementComplete) { consumer.retryRetirement(); }
    }

    @Override
    public void close() {
        requireMain();
        if (closing) { return; }
        closing = true;
        main.removeCallbacks(policyPoll);
        slot.removeOnAttachStateChangeListener(attachment);
        if (observer != null && observer.isAlive()) { observer.removeOnPreDrawListener(beforeDraw); }
        observer = null;
        if (consumer != null) {
            try {
                consumer.close();
                retirementComplete = consumer.isRetired();
            }
            catch (RuntimeException failure) { emitFailure(Failure.CONSUMER_FAILURE, null); }
            finally { if (consumer.getParent() == slot) { slot.removeView(consumer); } }
        } else {
            retirementComplete = true;
        }
        hidden = true;
        try { listener.onHidden(); } catch (RuntimeException failure) {}
        notifyRetired();
    }

    private boolean allowed() {
        if (consumer == null ? slot.getChildCount() != 0 :
                slot.getChildCount() != 1 || slot.getChildAt(0) != consumer) { return false; }
        if (!slot.isAttachedToWindow() || slot.getDisplay() == null || slot.getDisplay().getDisplayId() != 3 ||
                !slot.isHardwareAccelerated() || !slot.isShown() ||
                slot.getWidth() <= 0 || slot.getHeight() <= 0 || slot.getWidth() > 4096 || slot.getHeight() > 2160) {
            return false;
        }
        if (AndroidAutoClusterController.INSTANCE.isSessionActive() ||
                AndroidAutoClusterController.INSTANCE.isClusterRequested() ||
                AndroidAutoClusterController.INSTANCE.isSurfaceAttached() ||
                CarPlayDisplayOrchestrator.INSTANCE.getCurrentState() != CarPlayDisplayState.CONNECTED_ON_D0) {
            return false;
        }
        try { return CarPlayClusterHostPolicy.allows(snapshots.current(), SystemClock.elapsedRealtime()); }
        catch (RuntimeException failure) { return false; }
    }

    private void deny() {
        emitFailure(Failure.POLICY_DENIED, null);
        close();
    }

    private void emitFailure(Failure failure, CarPlaySecondarySurfaceConsumer.Failure consumerFailure) {
        try { listener.onFailure(failure, consumerFailure); } catch (RuntimeException ignored) {}
    }

    private void notifyRetired() {
        if (!hidden || !retirementComplete || retiredNotified) { return; }
        retiredNotified = true;
        try { listener.onRetired(); } catch (RuntimeException failure) {}
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Cluster host lifecycle requires main thread");
        }
    }
}
