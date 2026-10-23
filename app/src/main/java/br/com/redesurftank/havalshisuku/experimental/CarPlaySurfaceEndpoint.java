package br.com.redesurftank.havalshisuku.experimental;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.NoSuchElementException;

public final class CarPlaySurfaceEndpoint extends Binder implements AutoCloseable {
    private static final String DESCRIPTOR = "haval.impulse.experimental.SurfaceLease.v1";
    private static final int GRANT = IBinder.FIRST_CALL_TRANSACTION;
    private static final int REVOKE = GRANT + 1;
    private static final int NATIVE_INSTALLED = 8;
    private final Object lock = new Object();
    private final int authorizedUid;
    private final byte[] token;
    private long handle;
    private long generation;
    private IBinder owner;
    private IBinder.DeathRecipient deathRecipient;
    private boolean closed;

    public CarPlaySurfaceEndpoint(int authorizedUid, byte[] sessionToken) {
        if (authorizedUid < 0 || sessionToken == null || sessionToken.length != 32) {
            throw new IllegalArgumentException("Exact UID and 32-byte session token required");
        }
        this.authorizedUid = authorizedUid;
        this.token = sessionToken.clone();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != GRANT && code != REVOKE) {
            return super.onTransact(code, data, reply, flags);
        }
        if (Binder.getCallingUid() != authorizedUid || reply == null || flags != 0 ||
                data.dataSize() > 16384) {
            throw new SecurityException("Unauthorized Surface transport");
        }
        data.enforceInterface(DESCRIPTOR);
        byte[] presented = new byte[32];
        data.readByteArray(presented);
        synchronized (lock) {
            if (closed || !MessageDigest.isEqual(token, presented)) {
                throw new SecurityException("Invalid session token or closed endpoint");
            }
        }
        long requestedGeneration = data.readLong();
        if (code == REVOKE) {
            if (data.dataAvail() != 0) { throw new IllegalArgumentException("Trailing data"); }
            synchronized (lock) {
                if (generation != requestedGeneration || generation == 0) {
                    throw new IllegalStateException("Unknown generation");
                }
                close();
            }
            reply.writeNoException();
            return true;
        }
        Surface received = Surface.CREATOR.createFromParcel(data);
        try {
            IBinder requestedOwner = data.readStrongBinder();
            int framesPerSecond = data.readInt();
            int widthMillimeters = data.readInt();
            int heightMillimeters = data.readInt();
            if (data.dataAvail() != 0 || requestedGeneration <= 0 || requestedOwner == null ||
                    !received.isValid() || framesPerSecond <= 0 || framesPerSecond > 60 ||
                    widthMillimeters <= 0 || widthMillimeters > 2000 ||
                    heightMillimeters <= 0 || heightMillimeters > 2000) {
                throw new IllegalArgumentException("Invalid Surface grant");
            }
            synchronized (lock) {
                if (closed || handle != 0) { throw new IllegalStateException("Endpoint already consumed"); }
                IBinder.DeathRecipient recipient = this::close;
                requestedOwner.linkToDeath(recipient, 0);
                long imported = 0;
                try {
                    imported = nativeImportSurface(received);
                    if (imported == 0) { throw new IllegalStateException("Native Surface import failed"); }
                    int activation = nativeActivateSurface(imported, authorizedUid, requestedGeneration,
                            token, framesPerSecond, widthMillimeters, heightMillimeters);
                    if (activation != NATIVE_INSTALLED) {
                        throw new IllegalStateException("Native runtime activation failed: " + activation);
                    }
                    owner = requestedOwner;
                    deathRecipient = recipient;
                    generation = requestedGeneration;
                    handle = imported;
                } catch (RuntimeException | Error failure) {
                    unlink(requestedOwner, recipient);
                    if (imported != 0) { nativeRevokeSurface(imported); }
                    throw failure;
                }
            }
        } finally {
            received.release();
        }
        reply.writeNoException();
        return true;
    }

    public long localNativeHandle() {
        synchronized (lock) { return closed ? 0 : handle; }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) { return; }
            closed = true;
            Arrays.fill(token, (byte) 0);
            if (owner != null) { unlink(owner, deathRecipient); }
            owner = null;
            deathRecipient = null;
            long retired = handle;
            handle = 0;
            if (retired != 0) { nativeRevokeSurface(retired); }
        }
    }

    public static void grant(IBinder endpoint, byte[] sessionToken, long generation,
                             Surface independentSurface, IBinder ownerToken, int framesPerSecond,
                             int widthMillimeters, int heightMillimeters) throws RemoteException {
        validateClient(endpoint, sessionToken, generation);
        if (independentSurface == null || !independentSurface.isValid() || ownerToken == null) {
            throw new IllegalArgumentException("Independent Surface and owner required");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            writeHeader(data, sessionToken, generation);
            independentSurface.writeToParcel(data, 0);
            data.writeStrongBinder(ownerToken);
            data.writeInt(framesPerSecond);
            data.writeInt(widthMillimeters);
            data.writeInt(heightMillimeters);
            if (!endpoint.transact(GRANT, data, reply, 0)) { throw new RemoteException("Grant unsupported"); }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static void revoke(IBinder endpoint, byte[] sessionToken, long generation) throws RemoteException {
        validateClient(endpoint, sessionToken, generation);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            writeHeader(data, sessionToken, generation);
            if (!endpoint.transact(REVOKE, data, reply, 0)) { throw new RemoteException("Revoke unsupported"); }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void validateClient(IBinder endpoint, byte[] token, long generation) {
        if (endpoint == null || token == null || token.length != 32 || generation <= 0) {
            throw new IllegalArgumentException("Endpoint, session token and generation required");
        }
    }

    private static void unlink(IBinder binder, IBinder.DeathRecipient recipient) {
        try { binder.unlinkToDeath(recipient, 0); }
        catch (NoSuchElementException alreadyRemoved) {
        }
    }

    private static void writeHeader(Parcel data, byte[] token, long generation) {
        data.writeInterfaceToken(DESCRIPTOR);
        data.writeByteArray(token);
        data.writeLong(generation);
    }

    private static native long nativeImportSurface(Surface surface);
    private static native void nativeRevokeSurface(long handle);
    private static native int nativeActivateSurface(long handle, int authorizedUid, long requestId,
                                                    byte[] token, int framesPerSecond,
                                                    int widthMillimeters, int heightMillimeters);
}
