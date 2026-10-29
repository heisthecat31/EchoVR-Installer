package com.echovr.installer;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


public class MainActivity extends AppCompatActivity implements InstallerManager.Listener {

    private InstallerManager manager;
    private SharedPreferences prefs;

    // UI Elements
    private View gameSelectionLayout, mainContentLayout;
    private TextView statusText;
    private Button downloadButton, reinstallButton, checkGameUpdatesButton, grantPermissionsButton, launchEchoVRButton;
    private View uninstallEchoVRButton;
    private View helpButtonGame, helpButtonMain;
    private AlertDialog progressDialog;

    private static final String PREFS_NAME = "EchoVRInstallerPrefs";
    private static final String PREF_PERMISSION_POPUP_SHOWN = "permission_popup_shown";
    private static final String ECHO_VR_PACKAGE = "com.readyatdawn.r15";
    private static final String OLD_INSTALLER_PACKAGE = "com.echovr.installer";
    private static final String CURRENT_VERSION = "5.0";
    private boolean justInstalledEchoVr = false;
    private boolean hasCheckedForNevrUpdate = false;

    // Permissions Launchers
    private final ActivityResultLauncher<Intent> manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) checkState();
                }
            }
    );

    private final ActivityResultLauncher<Intent> installPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> checkPermissions()
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreenTransparent();
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        manager = new InstallerManager(this, this);
        manager.checkForUpdates(CURRENT_VERSION, true);

        initializeViews();
        checkInstallPermission();
        offerOldInstallerRemoval();

        // On Quest the system install/uninstall dialogs open as separate panels and this activity stays
        // resumed behind them, so onResume() never fires afterwards - refresh when Echo VR changes instead.
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageFilter.addDataScheme("package");
        ContextCompat.registerReceiver(this, packageChangeReceiver, packageFilter, ContextCompat.RECEIVER_EXPORTED);
    }

    private final BroadcastReceiver packageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            if (data != null && ECHO_VR_PACKAGE.equals(data.getSchemeSpecificPart()) && !isFinishing() && !isDestroyed()) {
                checkState();
            }
        }
    };

    /**
     * Up to 4.8 the installer was "com.echovr.installer", signed with a debug key. This build has a new
     * key, so it's a new package: the old app's self-update installs it alongside rather than over it.
     * Remove the old one here (Android always asks the user to confirm uninstalling another app).
     */
    private void offerOldInstallerRemoval() {
        if (!isPackageInstalled(OLD_INSTALLER_PACKAGE)) return;
        new AlertDialog.Builder(this)
                .setTitle("Remove Old Installer")
                .setMessage("You're on the new Echo VR Installer. The old one is still on this headset and won't get updates any more.\n\n" +
                        "Remove it now? Echo VR and your game data stay as they are.")
                .setPositiveButton("REMOVE", (d, w) -> uninstallPackage(OLD_INSTALLER_PACKAGE))
                .setNegativeButton("LATER", null)
                .show();
    }

    private void setupFullscreenTransparent() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        getWindow().setStatusBarColor(Color.TRANSPARENT);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
    }

    @SuppressLint("SetTextI18n")
    private void initializeViews() {
        gameSelectionLayout = findViewById(R.id.gameSelectionLayout);
        mainContentLayout = findViewById(R.id.mainContentLayout);
        statusText = findViewById(R.id.statusText);

        downloadButton = findViewById(R.id.downloadButton);
        reinstallButton = findViewById(R.id.reinstallButton);
        checkGameUpdatesButton = findViewById(R.id.checkGameUpdatesButton);
        grantPermissionsButton = findViewById(R.id.grantPermissionsButton);
        launchEchoVRButton = findViewById(R.id.launchEchoVRButton);
        uninstallEchoVRButton = findViewById(R.id.viewLobbyLinkButton);

        // Options
        findViewById(R.id.newPlayerOption).setOnClickListener(v -> showNewPlayerDialog());

        // Actions
        downloadButton.setOnClickListener(v -> manager.installGameData());
        reinstallButton.setOnClickListener(v -> showConfirmDialog("Reinstall Game Data", "This will re-download all game data.", () -> manager.installGameData()));
        checkGameUpdatesButton.setOnClickListener(v -> manager.checkNevrRuntimeUpdate(this::onNevrUpdateAvailable, true));
        grantPermissionsButton.setOnClickListener(v -> openAppPermissions());
        launchEchoVRButton.setOnClickListener(v -> launchEchoVR());

        // Uninstall Action. Game data lives in Echo VR's own media folder, which Android removes with the
        // app - and only once the uninstall is actually confirmed, so cancelling the system prompt keeps it.
        uninstallEchoVRButton.setOnClickListener(v -> showConfirmDialog("Uninstall Echo VR",
                "This uninstalls Echo VR, and its game data goes with it. This can't be undone.\n\nAre you sure you want to proceed?",
                this::uninstallEchoVR));

        // Utility Buttons
        View.OnClickListener clearCache = v -> showConfirmDialog("Clear Cache", "Delete temporary files? This does not affect installed game data.", () -> manager.clearCache());
        View.OnClickListener checkUpdates = v -> manager.checkForUpdates(CURRENT_VERSION);

        View clearCacheButtonGame = findViewById(R.id.clearCacheButtonGame);
        View clearCacheButtonMain = findViewById(R.id.clearCacheButtonMain);
        clearCacheButtonGame.setOnClickListener(clearCache);
        clearCacheButtonMain.setOnClickListener(clearCache);

        View checkUpdatesButtonGame = findViewById(R.id.checkUpdatesButtonGame);
        View checkUpdatesButtonMain = findViewById(R.id.checkUpdatesButtonMain);
        checkUpdatesButtonGame.setOnClickListener(checkUpdates);
        checkUpdatesButtonMain.setOnClickListener(checkUpdates);

        helpButtonGame = findViewById(R.id.helpButtonGame);
        helpButtonMain = findViewById(R.id.helpButtonMain);
        View.OnClickListener help = v -> showHelpDialog();
        helpButtonGame.setOnClickListener(help);
        helpButtonMain.setOnClickListener(help);

        View settingsButtonGame = findViewById(R.id.settingsButtonGame);
        View settingsButtonMain = findViewById(R.id.settingsButtonMain);
        View.OnClickListener settings = v -> showSettingsDialog();
        settingsButtonGame.setOnClickListener(settings);
        settingsButtonMain.setOnClickListener(settings);
    }

    private void showSettingsDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        TextView stable = view.findViewById(R.id.channelStable);
        TextView beta = view.findViewById(R.id.channelBeta);
        TextView description = view.findViewById(R.id.channelDescription);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        Runnable render = () -> {
            boolean isBeta = NevrRuntime.CHANNEL_BETA.equals(NevrRuntime.getChannel(this));
            styleSegment(stable, !isBeta);
            styleSegment(beta, isBeta);
            description.setText(isBeta
                    ? "Get new Echo VR builds as soon as they're out. They may have bugs."
                    : "Get Echo VR builds once they've been tested. Uses beta until the first stable release.");
        };
        View.OnClickListener pick = v -> {
            String channel = v == beta ? NevrRuntime.CHANNEL_BETA : NevrRuntime.CHANNEL_STABLE;
            if (channel.equals(NevrRuntime.getChannel(this))) return;
            NevrRuntime.setChannel(this, channel);
            render.run();
            // Moving to a channel with a newer build offers it right away; an older one can't be
            // installed over a newer one, so that just waits for the channel's next release.
            if (isPackageInstalled(ECHO_VR_PACKAGE)) manager.checkNevrRuntimeUpdate(this::onNevrUpdateAvailable);
        };
        stable.setOnClickListener(pick);
        beta.setOnClickListener(pick);
        render.run();

        view.findViewById(R.id.settingsDone).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void styleSegment(TextView segment, boolean selected) {
        segment.setBackgroundResource(selected ? R.drawable.segment_selected_background : 0);
        segment.setTextColor(getColor(selected ? R.color.echo_text : R.color.echo_text_faint));
    }

    private void onNevrUpdateAvailable(NevrRuntime.Release release) {
        if (isFinishing() || isDestroyed()) return;
        String channel = NevrRuntime.CHANNEL_BETA.equals(release.channel) ? " (beta)" : "";
        new AlertDialog.Builder(this)
                .setTitle("Update Echo VR")
                .setMessage("Echo VR " + release.version + channel + " is available (about "
                        + (release.apk.size / (1024 * 1024)) + " MB). It installs over your current version and keeps your game data.\n\nDownload and install now?")
                .setPositiveButton("DOWNLOAD & INSTALL", (d, w) -> manager.installNevrRuntime())
                .setNegativeButton("LATER", null)
                .show();
    }

    // --- DIALOGS ---

    private void checkState() {
        boolean isInstalled = isPackageInstalled(ECHO_VR_PACKAGE);

        if (isInstalled) {
            gameSelectionLayout.setVisibility(View.GONE);
            mainContentLayout.setVisibility(View.VISIBLE);
            helpButtonGame.setVisibility(View.GONE);
            helpButtonMain.setVisibility(View.VISIBLE);

            if (manager.verifyDataInstallation()) {
                statusText.setText("●  Ready to play");
                statusText.setTextColor(getColor(R.color.echo_good));
                statusText.setBackgroundResource(R.drawable.chip_good_background);
                downloadButton.setVisibility(View.GONE);
                reinstallButton.setVisibility(View.VISIBLE);
                checkGameUpdatesButton.setVisibility(View.VISIBLE);
                grantPermissionsButton.setVisibility(View.VISIBLE);
                launchEchoVRButton.setVisibility(View.VISIBLE);
                uninstallEchoVRButton.setVisibility(View.VISIBLE);

                if (!hasCheckedForNevrUpdate) {
                    hasCheckedForNevrUpdate = true;
                    manager.checkNevrRuntimeUpdate(this::onNevrUpdateAvailable);
                }
            } else {
                statusText.setText("●  Game data missing");
                statusText.setTextColor(getColor(R.color.echo_warn));
                statusText.setBackgroundResource(R.drawable.chip_warn_background);
                downloadButton.setVisibility(View.VISIBLE);
                reinstallButton.setVisibility(View.GONE);
                checkGameUpdatesButton.setVisibility(View.GONE);
                grantPermissionsButton.setVisibility(View.GONE);
                launchEchoVRButton.setVisibility(View.GONE);
                uninstallEchoVRButton.setVisibility(View.GONE);
            }

            if (!prefs.getBoolean(PREF_PERMISSION_POPUP_SHOWN, false)) {
                showPermissionGuidance();
            }
            justInstalledEchoVr = false;

        } else {
            gameSelectionLayout.setVisibility(View.VISIBLE);
            mainContentLayout.setVisibility(View.GONE);
            helpButtonGame.setVisibility(View.VISIBLE);
            helpButtonMain.setVisibility(View.GONE);
            prefs.edit().remove(PREF_PERMISSION_POPUP_SHOWN).apply();
        }
    }

    // --- InstallerManager Callbacks ---

    @Override
    public void onProgress(int progress, String message) {
        if (progressDialog == null || !progressDialog.isShowing()) {
            showProgressDialog(message);
        }

        TextView percent = progressDialog.findViewById(R.id.dialogProgressPercent);
        ProgressBar bar = progressDialog.findViewById(R.id.dialogProgressBar);

        setProgressMessage(progressDialog.getWindow().getDecorView(), message);
        if (percent != null) {
            percent.setVisibility(progress >= 0 ? View.VISIBLE : View.GONE);
            percent.setText(progress + "%");
        }
        if (bar != null) {
            if (progress >= 0) {
                bar.setIndeterminate(false);
                bar.setProgress(progress);
            } else {
                bar.setIndeterminate(true);
            }
        }
    }

    /** A progress message's first line is the popup's title; anything after it is the detail line. */
    private void setProgressMessage(View root, String message) {
        TextView title = root.findViewById(R.id.dialogProgressTitle);
        TextView detail = root.findViewById(R.id.dialogProgressText);
        int newline = message.indexOf('\n');
        if (title != null) title.setText(newline < 0 ? message : message.substring(0, newline));
        if (detail != null) {
            detail.setVisibility(newline < 0 ? View.GONE : View.VISIBLE);
            if (newline >= 0) detail.setText(message.substring(newline + 1));
        }
    }

    @Override
    public void onSuccess(String message) {
        if (isFinishing() || isDestroyed()) return;
        dismissProgressDialog();
        checkState();
        new AlertDialog.Builder(this).setTitle("Success").setMessage(message).setPositiveButton("OK", null).show();
    }

    @Override
    public void onError(String message) {
        if (isFinishing() || isDestroyed()) return;
        dismissProgressDialog();
        checkState();
        new AlertDialog.Builder(this).setTitle("Error").setMessage(message).setPositiveButton("OK", null).show();
    }

    @Override
    public void onTaskStarted() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onTaskFinished() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dismissProgressDialog();
    }

    @Override
    public void onUpdateAvailable(String version, String notes, String url) {
        String displayNotes = notes.length() > 500 ? notes.substring(0, 500) + "..." : notes;
        new AlertDialog.Builder(this)
                .setTitle("Update Available: " + version)
                .setMessage(displayNotes + "\n\nWould you like to download and install the update now?")
                .setPositiveButton("DOWNLOAD UPDATE", (d, w) -> manager.downloadAndInstallUpdate(url, version))
                .setNegativeButton("LATER", null)
                .show();
    }

    @Override
    public void onUpdateNotAvailable() {
        Toast.makeText(this, "No updates available", Toast.LENGTH_SHORT).show();
    }

    // --- Permissions ---

    private void checkInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("This app needs permission to install APK files. Please grant the 'Install unknown apps' permission to continue.")
                    .setPositiveButton("Grant", (d, w) -> {
                        installPermissionLauncher.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    })
                    .setNegativeButton("Exit", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            checkPermissions();
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("Storage Permission Required")
                        .setMessage("This app needs storage permission to download and install game files.")
                        .setPositiveButton("Grant Permission", (d, w) -> {
                            try {
                                manageStorageLauncher.launch(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                            } catch (Exception e) {
                                manageStorageLauncher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        })
                        .setNegativeButton("Exit", (d, w) -> finish())
                        .setCancelable(false)
                        .show();
            } else {
                checkState();
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
            } else {
                checkState();
            }
        }
        checkNotificationPermission();
    }

    private static final int STORAGE_PERMISSION_REQUEST_CODE = 100;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 101;

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (granted) {
                checkState();
            } else {
                Toast.makeText(this, "Permission denied. App cannot function.", Toast.LENGTH_SHORT).show();
            }
        }
        // NOTIFICATION_PERMISSION_REQUEST_CODE is optional - background installs still work
        // without it, just without a visible progress notification. Nothing to do either way.
    }


    private void showProgressDialog(String message) {
        showProgressDialog(message, true);
    }

    /**
     * @param cancelable whether to offer a Cancel button wired to the download-based managers'
     *                    own cancel() methods - wrong to show for a flow those managers aren't
     *                    actually running (e.g. the tester bundle install), since tapping it
     *                    would dismiss the dialog without actually stopping anything.
     */
    private void showProgressDialog(String message, boolean cancelable) {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) return;
            View view = getLayoutInflater().inflate(R.layout.dialog_progress, null);
            setProgressMessage(view, message);
            progressDialog = new AlertDialog.Builder(this).setView(view).setCancelable(false).create();
            progressDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            View cancel = view.findViewById(R.id.dialogCancelButton);
            if (cancelable) {
                cancel.setOnClickListener(v -> manager.cancelCurrentTask());
            } else {
                cancel.setVisibility(View.GONE);
            }
            progressDialog.show();
        });
    }

    private void dismissProgressDialog() {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        });
    }

    private void showNewPlayerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Install Echo VR");

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(50, 40, 50, 30);
        mainLayout.setBackgroundColor(Color.parseColor("#FF111928"));

        TextView instruction = new TextView(this);
        instruction.setText("Installs the latest Echo VR (about 175 MB). It downloads and installs automatically, and updates install over it.");
        instruction.setTextColor(Color.parseColor("#EEF3F8"));
        instruction.setTextSize(14);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 0, 0, 25);
        mainLayout.addView(instruction);

        Button installBtn = dialogButton("INSTALL ECHO VR", R.drawable.button_background);
        mainLayout.addView(installBtn);

        Button cancelBtn = dialogButton("CANCEL", R.drawable.button_background_secondary);
        mainLayout.addView(cancelBtn);

        builder.setView(mainLayout);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        installBtn.setOnClickListener(v -> {
            dialog.dismiss();
            manager.installNevrRuntime();
        });
        dialog.show();
    }

    private Button dialogButton(String label, int backgroundRes) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.parseColor("#EEF3F8"));
        button.setBackground(getResources().getDrawable(backgroundRes));
        button.setPadding(40, 20, 40, 20);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 15);
        button.setLayoutParams(params);
        return button;
    }

    private void showHelpDialog() {
        String message = "If you're experiencing issues:\n\n" +
                "1. If links don't work, restart your device or switch WiFi networks.\n\n" +
                "2. If game crashes when opening:\n" +
                "   - Ensure all permissions for Echo VR are granted\n" +
                "   - Reinstall game data\n" +
                "   - Restart headset after doing both\n\n" +
                "3. If it still crashes:\n" +
                "   - Uninstall Echo VR\n" +
                "   - Tap 'Install Echo VR' to reinstall it\n" +
                "   - Restart device";
        new AlertDialog.Builder(this)
                .setTitle("Troubleshooting Guide")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showConfirmDialog(String title, String msg, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> action.run())
                .setNegativeButton("No", null)
                .show();
    }

    private void showPermissionGuidance() {
        // Android requires a human tap to grant another app's runtime permissions - there's
        // no API for us to flip them on for Echo VR automatically. Explain first, then hand
        // off to its App Info screen so the toggles are one tap away instead of the user
        // having to find it themselves.
        new AlertDialog.Builder(this)
                .setTitle("Permissions Recommended")
                .setMessage("Echo VR needs its own permissions - Microphone for voice chat, plus any others it requests.\n\nTap Continue to open its App Info page, then grant them there. Android doesn't allow us to do this for you automatically.")
                .setPositiveButton("Continue", (d, w) -> openAppPermissions())
                .setNegativeButton("Later", null)
                .setCancelable(false)
                .show();
        prefs.edit().putBoolean(PREF_PERMISSION_POPUP_SHOWN, true).apply();
    }

    private void openAppPermissions() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + ECHO_VR_PACKAGE));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchEchoVR() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(ECHO_VR_PACKAGE);
            if (intent != null) startActivity(intent);
            else Toast.makeText(this, "Echo VR not installed", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error launching Echo VR", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void uninstallEchoVR() {
        uninstallPackage(ECHO_VR_PACKAGE);
    }

    /**
     * A Quest with several accounts has a separate Android user per account, and a plain uninstall only
     * removes the app for the current one - the APK stays on the headset for the others, so a differently
     * signed build can't be installed afterwards. Ask for all users instead; Android only allows that from
     * the headset's main user, so other profiles still get a plain uninstall.
     */
    private void uninstallPackage(String pkg) {
        try {
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
            uninstallIntent.setData(Uri.parse("package:" + pkg));
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UserManager userManager = (UserManager) getSystemService(USER_SERVICE);
            if (userManager != null && userManager.isSystemUser()) {
                // Intent.EXTRA_UNINSTALL_ALL_USERS - hidden from the SDK, but the system uninstaller honours it.
                uninstallIntent.putExtra("android.intent.extra.UNINSTALL_ALL_USERS", true);
            }
            startActivity(uninstallIntent);
        } catch (ActivityNotFoundException e) {
            Log.e("Uninstall", "Could not launch uninstall dialog.", e);
            Toast.makeText(this, "Could not open uninstaller.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean hasFilePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private String getCurrentVersion() {
        return CURRENT_VERSION;
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkState();

        // PackageManager can take a while to settle after returning from the system
        // installer, and focus bounces between us/the installer/Echo VR/etc. repeatedly
        // during that flow - each bounce used to cancel the poll before its delay ever
        // elapsed, so it could go a full 30s+ without ever firing once. Don't cancel on
        // pause: keep an already-scheduled poll running in the background regardless of
        // whether this Activity is currently visible, and only start a new polling run if
        // one isn't already in flight (so repeated resumes don't keep resetting it to 0).
        if (!pollingActive && !(isPackageInstalled(ECHO_VR_PACKAGE) && manager.verifyDataInstallation())) {
            pollingActive = true;
            resumePollAttempts = 0;
            resumePollHandler.postDelayed(this::pollInstallState, RESUME_POLL_INTERVAL_MS);
        }
    }

    private final Handler resumePollHandler = new Handler(Looper.getMainLooper());
    private boolean pollingActive;
    private int resumePollAttempts;
    private static final int RESUME_POLL_MAX_ATTEMPTS = 20;
    private static final long RESUME_POLL_INTERVAL_MS = 1500;

    private void pollInstallState() {
        checkState();
        resumePollAttempts++;
        boolean settled = isPackageInstalled(ECHO_VR_PACKAGE) && manager.verifyDataInstallation();
        if (settled || resumePollAttempts >= RESUME_POLL_MAX_ATTEMPTS) {
            pollingActive = false;
            return;
        }
        resumePollHandler.postDelayed(this::pollInstallState, RESUME_POLL_INTERVAL_MS);
    }

    @Override
    protected void onDestroy() {
        resumePollHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(packageChangeReceiver);
        manager.shutdown();
        super.onDestroy();
    }
}