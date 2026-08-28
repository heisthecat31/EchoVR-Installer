package com.echovr.installer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Runs the mod-patch check (checkAndApplyUpdates) as a foreground service so it reliably
 * completes even if the user switches away - e.g. to confirm the base APK's system install
 * dialog - right after game data finishes. Game data itself stays a normal foreground,
 * user-initiated download (the "Download Game Data" button); only this smaller follow-up
 * step needs to survive backgrounding.
 */
public class ModPatchService extends Service {

    private static final String TAG = "ModPatchService";
    private static final String CHANNEL_ID = "echo_vr_background_install";
    private static final int NOTIF_ID = 4202;

    // The base-APK install (which is what writes the marker file checkAndApplyUpdates needs)
    // may still be in progress - e.g. the user hasn't tapped through the system install
    // dialog yet - when this runs. A silent check with no marker yet just no-ops with no
    // callback at all (correct for its normal caller, wrong here), so retry instead of
    // giving up after one try, bounded by a total window in case the APK install stalls.
    private static final long PATCH_CHECK_RETRY_INTERVAL_MS = 10_000;
    private static final long PATCH_CHECK_TOTAL_WINDOW_MS = 5 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryPatchCheck = this::attemptPatchCheck;

    private UpdateManager updateManager;
    private long patchCheckDeadline;
    private long lastNotifUpdateMs = 0;

    public static void start(Context context) {
        Intent intent = new Intent(context, ModPatchService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        showAsForeground(buildNotification("Checking for mod updates...", -1));

        InstallerManager.Listener listener = new InstallerManager.Listener() {
            @Override
            public void onProgress(int progress, String message) {
                updateNotification(progress, message);
            }

            @Override
            public void onSuccess(String message) {
                handler.removeCallbacks(retryPatchCheck);
                finishAndStop("Mod updates installed.");
            }

            @Override
            public void onError(String message) {
                handler.removeCallbacks(retryPatchCheck);
                failAndStop("Mod update check failed: " + message);
            }

            @Override
            public void onTaskStarted() {}

            @Override
            public void onTaskFinished() {}

            @Override
            public void onUpdateAvailable(String version, String notes, String downloadUrl) {}

            @Override
            public void onUpdateNotAvailable() {}

            @Override
            public void onBaseApkUpdateAvailable(UpdateManifest manifest) {
                // A different base APK is needed - that's a bigger, user-facing decision
                // than this background step should make. Leave it for the app's own
                // foreground check/popup to offer next time it's opened.
                handler.removeCallbacks(retryPatchCheck);
                finishAndStop("Echo VR base APK needs an update - open the installer to continue.");
            }
        };

        updateManager = new UpdateManager(this, listener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        patchCheckDeadline = System.currentTimeMillis() + PATCH_CHECK_TOTAL_WINDOW_MS;
        handler.removeCallbacks(retryPatchCheck);
        attemptPatchCheck();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(retryPatchCheck);
        super.onDestroy();
    }

    /**
     * Runs a silent checkAndApplyUpdates() and, if we're still within the total window,
     * schedules another attempt in case this one silently no-ops (marker file not written
     * yet because the base-APK install hasn't finished). A real result (success/error)
     * cancels the pending retry from within the listener above instead of waiting for it.
     */
    private void attemptPatchCheck() {
        if (System.currentTimeMillis() >= patchCheckDeadline) {
            finishAndStop("Mod update check timed out.");
            return;
        }
        updateManager.checkAndApplyUpdates(null, true);
        handler.postDelayed(retryPatchCheck, PATCH_CHECK_RETRY_INTERVAL_MS);
    }

    private void finishAndStop(String message) {
        updateNotification(100, message);
        stopForegroundCompat();
        stopSelf();
    }

    private void failAndStop(String message) {
        Log.e(TAG, message);
        updateNotification(-1, message);
        stopForegroundCompat();
        stopSelf();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private void showAsForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void updateNotification(int progress, String message) {
        long now = System.currentTimeMillis();
        boolean isFinal = progress < 0 || progress >= 100;
        if (!isFinal && now - lastNotifUpdateMs < 700) return;
        lastNotifUpdateMs = now;

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(message, progress));
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted - the service still runs in the foreground,
            // it just won't be visible. Not fatal.
            Log.w(TAG, "Notification permission not granted, progress will not be shown", e);
        }
    }

    private Notification buildNotification(String message, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Echo VR Installer")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setOngoing(progress >= 0 && progress < 100)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(contentIntent);

        if (progress >= 0 && progress < 100) {
            builder.setProgress(100, progress, false);
        } else if (progress < 0) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Echo VR Install Progress", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows progress while Echo VR mod updates install in the background.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
