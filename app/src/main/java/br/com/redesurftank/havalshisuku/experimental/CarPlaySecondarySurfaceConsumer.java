package br.com.redesurftank.havalshisuku.experimental;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CarPlaySecondarySurfaceConsumer extends TextureView
        implements TextureView.SurfaceTextureListener, AutoCloseable {
    public enum Failure { GRANT_FAILED, REVOKE_UNCONFIRMED, ENDPOINT_DIED, INVALID_SURFACE,
        SIZE_CHANGED, RELEASE_FAILED }

    public interface Listener {
        void onReady(long generation, int width, int height);
        void onFailure(Failure failure);
        void onStopped();
    }

    private static final AtomicBoolean IN_USE = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CarPlaySecondarySurface");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;
    private ConsumerLease lease;
    private Listener listener;
    private boolean started;
    private boolean closed;
    private boolean finished;
    private boolean ownsSlot;

    private static final class Connection {
        final IBinder endpoint;
        final byte[] token;
        final long generation;
        final IBinder owner = new Binder();
        final AtomicBoolean dead = new AtomicBoolean();
        IBinder.DeathRecipient recipient;

        Connection(IBinder endpoint, byte[] token, long generation) {
            this.endpoint = endpoint;
            this.token = token.clone();
            this.generation = generation;
        }
    }

    private static final class ConsumerLease {
        final Connection connection;
        final SurfaceTexture texture;
        final Surface surface;
        final int width;
        final int height;
        final CarPlayConsumerRetirement retirement;
        boolean retirementQueued;

        ConsumerLease(Connection connection, SurfaceTexture texture, int width, int height,
                      Runnable onFinished) {
            this.connection = connection;
            this.texture = texture;
            this.surface = new Surface(texture);
            this.width = width;
            this.height = height;
            this.retirement = new CarPlayConsumerRetirement(surface::release, texture::release, onFinished);
        }
    }

    public CarPlaySecondarySurfaceConsumer(Context context) {
        super(context);
        setVisibility(GONE);
        setSurfaceTextureListener(this);
    }

    @Override
    public void setSurfaceTexture(SurfaceTexture texture) {
        throw new UnsupportedOperationException("Borrowed SurfaceTextures are not accepted");
    }

    public void start(boolean explicitlyEnabled, IBinder endpoint, byte[] token, long generation,
                      Listener listener) throws RemoteException {
        requireMain();
        if (!explicitlyEnabled || started || closed || !isAttachedToWindow() ||
                getDisplay() == null || getDisplay().getDisplayId() != 3 || !isHardwareAccelerated() ||
                isAvailable() || getSurfaceTexture() != null || getSurfaceTextureListener() != this) {
            throw new IllegalStateException("Fresh attached accelerated cluster consumer required");
        }
        if (endpoint == null || token == null || token.length != 32 || generation <= 0 || listener == null) {
            throw new IllegalArgumentException("Authenticated endpoint, token, generation and listener required");
        }
        if (!IN_USE.compareAndSet(false, true)) {
            throw new IllegalStateException("Another consumer is active or awaiting confirmed retirement");
        }
        ownsSlot = true;
        started = true;
        this.listener = listener;
        Connection next = new Connection(endpoint, token, generation);
        connection = next;
        next.recipient = () -> {
            next.dead.set(true);
            main.post(() -> peerDied(next));
        };
        try {
            endpoint.linkToDeath(next.recipient, 0);
            setVisibility(VISIBLE);
        } catch (RemoteException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        requireMain();
        if (closed || !started) { return; }
        if (lease != null || getDisplay() == null || getDisplay().getDisplayId() != 3 ||
                width <= 0 || height <= 0 || width > 4096 || height > 2160) {
            notifyFailure(Failure.INVALID_SURFACE);
            close();
            return;
        }
        Connection active = connection;
        ConsumerLease created;
        try {
            created = new ConsumerLease(active, texture, width, height, () -> main.post(this::finish));
        } catch (RuntimeException failure) {
            notifyFailure(Failure.INVALID_SURFACE);
            close();
            return;
        }
        lease = created;
        DisplayMetrics metrics = new DisplayMetrics();
        getDisplay().getRealMetrics(metrics);
        final int widthMillimeters;
        final int heightMillimeters;
        try {
            widthMillimeters = millimeters(width, metrics.xdpi);
            heightMillimeters = millimeters(height, metrics.ydpi);
        } catch (IllegalArgumentException failure) {
            notifyFailure(Failure.INVALID_SURFACE);
            close();
            return;
        }
        worker.execute(() -> {
            try {
                CarPlaySurfaceEndpoint.grant(active.endpoint, active.token, active.generation,
                    created.surface, active.owner, 30, widthMillimeters, heightMillimeters);
                main.post(() -> {
                    if (!closed && lease == created && !active.dead.get()) {
                        try { listener.onReady(active.generation, width, height); }
                        catch (RuntimeException failure) { close(); }
                    }
                });
            } catch (RemoteException | RuntimeException | LinkageError failure) {
                main.post(() -> { notifyFailure(Failure.GRANT_FAILED); close(); });
            }
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        requireMain();
        if (!closed && lease != null && (lease.width != width || lease.height != height)) {
            notifyFailure(Failure.SIZE_CHANGED);
            close();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        requireMain();
        ConsumerLease retired = lease;
        if (retired != null && retired.texture == texture) {
            try { retired.retirement.detached(); }
            catch (RuntimeException failure) { notifyFailure(Failure.RELEASE_FAILED); }
            requestRetirement(retired);
            close();
            return false;
        }
        close();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    @Override
    protected void onDetachedFromWindow() {
        close();
        super.onDetachedFromWindow();
    }

    @Override
    public void close() {
        requireMain();
        if (closed) { return; }
        closed = true;
        if (lease != null) { requestRetirement(lease); }
        setVisibility(GONE);
        if (lease == null) { finish(); }
    }

    public void retryRetirement() {
        requireMain();
        if (closed && !finished && lease != null) {
            ConsumerLease retired = lease;
            worker.execute(() -> retire(retired));
        }
    }

    public boolean isRetired() {
        requireMain();
        return finished;
    }

    private void requestRetirement(ConsumerLease retired) {
        if (retired.retirementQueued || finished) { return; }
        retired.retirementQueued = true;
        worker.execute(() -> retire(retired));
    }

    private void retire(ConsumerLease retired) {
        if (retired.retirement.isFinished()) { return; }
        Connection active = retired.connection;
        boolean stopped = retired.retirement.isStopped() || active.dead.get() || !active.endpoint.isBinderAlive();
        if (!stopped) {
            try {
                CarPlaySurfaceEndpoint.revoke(active.endpoint, active.token, active.generation);
                stopped = true;
            } catch (RemoteException | RuntimeException | LinkageError failure) {
                main.post(() -> notifyFailure(Failure.REVOKE_UNCONFIRMED));
            }
        }
        if (stopped) {
            Arrays.fill(active.token, (byte) 0);
            try { retired.retirement.confirmStopped(); }
            catch (RuntimeException failure) { main.post(() -> notifyFailure(Failure.RELEASE_FAILED)); }
        }
    }

    private void peerDied(Connection dead) {
        if (connection != dead || finished) { return; }
        notifyFailure(Failure.ENDPOINT_DIED);
        close();
        retryRetirement();
    }

    private void finish() {
        requireMain();
        if (finished) { return; }
        finished = true;
        if (connection != null) {
            Arrays.fill(connection.token, (byte) 0);
            try { connection.endpoint.unlinkToDeath(connection.recipient, 0); }
            catch (NoSuchElementException alreadyRemoved) {
            }
        }
        if (ownsSlot) { IN_USE.set(false); ownsSlot = false; }
        worker.shutdown();
        if (listener != null) {
            try { listener.onStopped(); } catch (RuntimeException failure) {}
        }
    }

    private void notifyFailure(Failure failure) {
        requireMain();
        if (listener != null && !finished) {
            try { listener.onFailure(failure); } catch (RuntimeException callbackFailure) { close(); }
        }
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Consumer lifecycle requires main thread");
        }
    }

    private static int millimeters(int pixels, float dotsPerInch) {
        if (pixels <= 0 || !Float.isFinite(dotsPerInch) || dotsPerInch <= 0f) {
            throw new IllegalArgumentException("Valid display density required");
        }
        int value = Math.round((pixels / dotsPerInch) * 25.4f);
        if (value <= 0 || value > 2000) {
            throw new IllegalArgumentException("Physical display geometry out of range");
        }
        return value;
    }
}
