package br.com.redesurftank.havalshisuku.services;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import java.util.Arrays;

import br.com.redesurftank.havalshisuku.experimental.CarPlayCleanMapCoordinator;

public final class CarPlayClusterBridgeService extends Service {
    private static final String TRUSTED_OEM_PACKAGE = "com.ts.carplay";

    private final ICarPlayClusterBridge.Stub binder = new ICarPlayClusterBridge.Stub() {
        @Override
        public void offerEndpoint(IBinder endpoint, byte[] token, long generation) throws RemoteException {
            int uid = Binder.getCallingUid();
            String[] packages = getPackageManager().getPackagesForUid(uid);
            if (packages == null || !Arrays.asList(packages).contains(TRUSTED_OEM_PACKAGE)) {
                throw new SecurityException("Only the installed OEM CarPlay host may offer an endpoint");
            }
            if (endpoint == null || !endpoint.isBinderAlive() || token == null || token.length != 32 ||
                    generation <= 0) {
                throw new IllegalArgumentException("Live endpoint, token and generation required");
            }
            CarPlayCleanMapCoordinator.INSTANCE.offerEndpoint(endpoint, token, generation);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
