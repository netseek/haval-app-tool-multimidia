package br.com.redesurftank.havalshisuku.broadcastReceivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import br.com.redesurftank.havalshisuku.managers.CarPlayBridgeBootstrapManager;

/** Boot/configuration entry point; callers are restricted by the manifest's DUMP permission. */
public final class CarPlayBridgeBootstrapReceiver extends BroadcastReceiver {
    private static final String TAG = "CarPlayBridgeBootstrap";
    public static final String ACTION_CONFIGURE =
            "br.com.redesurftank.havalshisuku.CONFIGURE_CARPLAY_BRIDGE";
    public static final String EXTRA_ENABLED = "extra_enabled";
    public static final String EXTRA_RETRY = "extra_retry";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        try {
            if (ACTION_CONFIGURE.equals(action)) {
                if (!intent.hasExtra(EXTRA_ENABLED)) {
                    Log.w(TAG, "Ignoring bridge configuration without an explicit enabled value");
                    return;
                }
                Object value = intent.getExtras() != null ? intent.getExtras().get(EXTRA_ENABLED) : null;
                if (!(value instanceof Boolean)) {
                    Log.w(TAG, "Ignoring bridge configuration with a non-boolean enabled value");
                    return;
                }
                CarPlayBridgeBootstrapManager.setAutomaticEnabled(
                        context, (Boolean) value);
                if ((Boolean) value && Boolean.TRUE.equals(intent.getExtras().get(EXTRA_RETRY))) {
                    // Same guarded explicit retry as the parked-user UI. Existing owners/mappings
                    // still prevent reset, and the worker rechecks all live preparation gates.
                    CarPlayBridgeBootstrapManager.resetFailureCircuit(context);
                }
            } else if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                    && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                return;
            }
            CarPlayBridgeBootstrapManager.start(context);
        } catch (Exception error) {
            Log.e(TAG, "Unable to schedule CarPlay preparation", error);
        }
    }
}
