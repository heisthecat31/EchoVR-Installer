package com.echovr.installer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Holds the app process in the foreground for the duration of the Discord sign-in flow.
 * Runs no logic of its own - DiscordAuth keeps doing exactly what it already did, this just
 * brackets it.
 *
 * Why this exists: DiscordAuth opens the system browser, which backgrounds MainActivity.
 * On stock Android and especially on Samsung (One UI's "Freecess"), a backgrounded app with
 * no active foreground component gets its process frozen within seconds. Once frozen, the
 * loopback HTTP server DiscordAuth is listening on (127.0.0.1:53124) can't complete the TCP
 * handshake for the browser's redirect back to it - confirmed via adb: the connection sits
 * in SYN_SENT (never ESTABLISHED, never refused) for as long as you wait, which is exactly
 * why "stays loading, never goes through" never resolves on its own. A foreground service
 * with a visible notification tells Android not to freeze the process.
 */
public class DiscordAuthKeepAliveService extends Service {

    private static final String CHANNEL_ID = "echo_vr_background_install";
    private static final int NOTIF_ID = 4203;

    public static void start(Context context) {
        Intent intent = new Intent(context, DiscordAuthKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, DiscordAuthKeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Echo VR Installer")
                .setContentText("Waiting for Discord sign-in - don't swipe the app away")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Echo VR Install Progress", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
