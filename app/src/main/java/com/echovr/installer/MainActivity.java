package com.echovr.installer;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.provider.Settings;
import android.text.TextUtils;
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements InstallerManager.Listener, DiscordAuth.Callback, TesterBundleInstaller.Listener {

    private InstallerManager manager;
    private UpdateManager updateManager;
    private DiscordAuth discordAuth;
    private TesterBundleInstaller testerInstaller;
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
    /** The Quest dev folder code (dash stripped) - also what unlocks Developer Settings. */
    private static final String PREF_DEV_CODE = "dev_folder_code";
    private static final String ECHO_VR_PACKAGE = "com.readyatdawn.r15";
    private static final String CURRENT_VERSION = "4.8";
    private boolean justInstalledEchoVr = false;
    private boolean hasCheckedForModUpdates = false;

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
        updateManager = new UpdateManager(this, this);
        discordAuth = new DiscordAuth(this, this);
        testerInstaller = new TesterBundleInstaller(this, this);
        manager.fetchRemoteConfig();
        manager.checkForUpdates(CURRENT_VERSION, true);

        initializeViews();
        checkInstallPermission();
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
        checkGameUpdatesButton.setOnClickListener(v -> updateManager.checkAndApplyUpdates(null, false));
        grantPermissionsButton.setOnClickListener(v -> openAppPermissions());
        launchEchoVRButton.setOnClickListener(v -> launchEchoVR());

        // Uninstall Action
        uninstallEchoVRButton.setOnClickListener(v -> showConfirmDialog("Uninstall Echo VR",
                "This will uninstall the Echo VR app and delete all game data files. This action cannot be undone.\n\nAre you sure you want to proceed?",
                () -> {
                    manager.deleteGameDataFiles(); 
                    uninstallEchoVR(); 
                }));

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
        if (prefs.getString(PREF_DEV_CODE, null) != null) {
            startTesterBundleFlow();
            return;
        }

        EditText codeInput = new EditText(this);
        codeInput.setHint("Developer code (xxxxx-xxxxx)");
        codeInput.setTextColor(Color.WHITE);
        codeInput.setHintTextColor(Color.parseColor("#888888"));
        codeInput.setBackground(getResources().getDrawable(R.drawable.edit_text_background));
        codeInput.setPadding(25, 20, 25, 20);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 10);
        layout.addView(codeInput);

        new AlertDialog.Builder(this)
                .setTitle("Developer Settings")
                .setView(layout)
                .setPositiveButton("Submit", (d, w) -> {
                    String code = TesterBundleInstaller.normalizeDevCode(codeInput.getText().toString());
                    if (code == null) {
                        Toast.makeText(this, "Invalid code", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showProgressDialog("Checking code...", false);
                    testerInstaller.verifyDevCode(code, (ok, error) -> {
                        if (isFinishing() || isDestroyed()) return;
                        dismissProgressDialog();
                        if (ok) {
                            prefs.edit().putString(PREF_DEV_CODE, code).apply();
                            startTesterBundleFlow();
                        } else if (error == null) {
                            Toast.makeText(this, "Invalid code", Toast.LENGTH_SHORT).show();
                        } else {
                            new AlertDialog.Builder(this).setTitle("Error").setMessage(error).setPositiveButton("OK", null).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Tester bundle (dev code) install flow ---

    private void startTesterBundleFlow() {
        new AlertDialog.Builder(this)
                .setTitle("Install Test Build")
                .setMessage("This downloads the tester bundle and installs a side-by-side \"Echo VR (afk)\" test app, " +
                        "signed with a key kept only on this headset, and copies stock Echo VR's base data into it.\n\n" +
                        "Stock Echo VR must already be installed and have been launched once to its main menu.")
                .setPositiveButton("Continue", (d, w) -> promptDisplayNameForTesterBundle())
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Change Code", (d, w) -> {
                    prefs.edit().remove(PREF_DEV_CODE).apply();
                    showSettingsDialog();
                })
                .show();
    }

    private void promptDisplayNameForTesterBundle() {
        String existing = testerInstaller.peekExistingDisplayName();

        EditText nameInput = new EditText(this);
        nameInput.setHint("Display name (1-20 characters)");
        nameInput.setTextColor(Color.WHITE);
        nameInput.setHintTextColor(Color.parseColor("#888888"));
        nameInput.setBackground(getResources().getDrawable(R.drawable.edit_text_background));
        nameInput.setPadding(25, 20, 25, 20);
        if (existing != null) nameInput.setText(existing);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 10);
        layout.addView(nameInput);

        new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Display name (currently " + existing + ")" : "Choose a display name")
                .setView(layout)
                .setPositiveButton("Install", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter a display name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showProgressDialog("Starting...", false);
                    testerInstaller.downloadAndInstall(prefs.getString(PREF_DEV_CODE, null), name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onProgress(String message) {
        if (isFinishing() || isDestroyed()) return;
        if (progressDialog == null || !progressDialog.isShowing()) {
            showProgressDialog(message, false);
        }
        TextView text = progressDialog.findViewById(R.id.dialogProgressText);
        if (text != null) text.setText(message);
    }

    @Override
    public void onNeedUninstallConfirm(Runnable proceed, Runnable cancel) {
        if (isFinishing() || isDestroyed()) {
            // No UI left to ask through - don't leave the background flow hanging forever waiting
            // for a tap that can never come, but also don't uninstall anything without someone
            // having actually confirmed it.
            cancel.run();
            return;
        }
        dismissProgressDialog();
        new AlertDialog.Builder(this)
                .setTitle("Replace Existing Test Build?")
                .setMessage("The test build already on this headset is signed with a different key and can't be " +
                        "updated in place. Uninstalling it removes its files; this installer puts them back " +
                        "afterwards (base data, pack, switches, and your name).\n\nUninstall the old test build and continue?")
                .setPositiveButton("Uninstall & Continue", (d, w) -> {
                    showProgressDialog("Uninstalling old test build...", false);
                    proceed.run();
                })
                .setNegativeButton("Cancel", (d, w) -> cancel.run())
                .setCancelable(false)
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

                if (!hasCheckedForModUpdates) {
                    hasCheckedForModUpdates = true;
                    ModPatchService.start(this);
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

        TextView text = progressDialog.findViewById(R.id.dialogProgressText);
        ProgressBar bar = progressDialog.findViewById(R.id.dialogProgressBar);

        if (text != null) text.setText(message);
        if (bar != null) {
            if (progress >= 0) {
                bar.setIndeterminate(false);
                bar.setProgress(progress);
            } else {
                bar.setIndeterminate(true);
            }
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

    @Override
    public void onBaseApkUpdateAvailable(UpdateManifest manifest) {
        new AlertDialog.Builder(this)
                .setTitle("Update EchoVR")
                .setMessage(buildEchoVrChangeSummary(manifest))
                .setPositiveButton("DOWNLOAD & INSTALL", (d, w) -> updateManager.downloadAndInstallBaseApk(manifest))
                .setNegativeButton("LATER", null)
                .show();
    }

    @SuppressLint("SetTextI18n")
    private String buildEchoVrChangeSummary(UpdateManifest manifest) {
        int added = 0, removed = 0;
        for (UpdateManifest.UpdateEntry entry : manifest.entries) {
            if ("add".equalsIgnoreCase(entry.op)) added++;
            else if ("del".equalsIgnoreCase(entry.op)) removed++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("A new Echo VR build is available:\n").append(manifest.baseApkName).append("\n\n");
        sb.append("What's new:\n");
        sb.append("• ").append(added).append(added == 1 ? " file to update\n" : " files to update\n");
        if (removed > 0) {
            sb.append("• ").append(removed).append(removed == 1 ? " file to remove\n" : " files to remove\n");
        }
        sb.append("\nYour installed game no longer matches this build, so mod updates can't be applied until it's updated.\n\nDownload and install now?");
        return sb.toString();
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
            TextView txt = view.findViewById(R.id.dialogProgressText);
            txt.setText(message);
            AlertDialog.Builder builder = new AlertDialog.Builder(this).setView(view).setCancelable(false);
            if (cancelable) {
                builder.setNegativeButton("Cancel", (d, w) -> {
                    manager.cancelCurrentTask();
                    updateManager.cancel();
                    discordAuth.cancel();
                    DiscordAuthKeepAliveService.stop(this);
                });
            }
            progressDialog = builder.create();
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
        builder.setTitle("New Player Installation");

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(50, 40, 50, 30);
        mainLayout.setBackgroundColor(Color.parseColor("#FF111928"));

        TextView instruction = new TextView(this);
        instruction.setText("Sign in with Discord to get your personal Echo VR APK. It downloads and installs automatically.\n\nYou need to be a member of the Echo VR Patcher Discord server.");
        instruction.setTextColor(Color.parseColor("#EEF3F8"));
        instruction.setTextSize(14);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 0, 0, 25);
        mainLayout.addView(instruction);

        Button signInBtn = dialogButton("SIGN IN WITH DISCORD", R.drawable.button_background);
        mainLayout.addView(signInBtn);

        Button joinBtn = dialogButton("JOIN PATCHER SERVER", R.drawable.button_background_secondary);
        mainLayout.addView(joinBtn);
        joinBtn.setOnClickListener(v -> openUrl(DiscordAuth.PATCHER_INVITE));

        // Fallback for someone who already has a patched APK link (e.g. from the bot directly).
        TextView haveLink = new TextView(this);
        haveLink.setText("I already have an APK link");
        haveLink.setTextColor(Color.parseColor("#3FD4FF"));
        haveLink.setTextSize(13);
        haveLink.setGravity(Gravity.CENTER);
        haveLink.setPadding(0, 5, 0, 15);
        mainLayout.addView(haveLink);

        LinearLayout linkLayout = new LinearLayout(this);
        linkLayout.setOrientation(LinearLayout.VERTICAL);
        linkLayout.setVisibility(View.GONE);
        mainLayout.addView(linkLayout);

        EditText apkUrlInput = new EditText(this);
        apkUrlInput.setHint("https://files.echovr.de/...apk");
        apkUrlInput.setTextColor(Color.WHITE);
        apkUrlInput.setHintTextColor(Color.parseColor("#888888"));
        apkUrlInput.setBackground(getResources().getDrawable(R.drawable.edit_text_background));
        apkUrlInput.setPadding(25, 20, 25, 20);
        apkUrlInput.setTextSize(14);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, 0, 0, 15);
        apkUrlInput.setLayoutParams(inputParams);
        linkLayout.addView(apkUrlInput);

        Button installLinkBtn = dialogButton("INSTALL FROM LINK", R.drawable.button_background);
        linkLayout.addView(installLinkBtn);

        haveLink.setOnClickListener(v -> {
            haveLink.setVisibility(View.GONE);
            linkLayout.setVisibility(View.VISIBLE);
        });

        Button cancelBtn = dialogButton("CANCEL", R.drawable.button_background_secondary);
        mainLayout.addView(cancelBtn);

        builder.setView(mainLayout);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        signInBtn.setOnClickListener(v -> {
            dialog.dismiss();
            onTaskStarted();
            DiscordAuthKeepAliveService.start(this);
            discordAuth.start();
        });
        installLinkBtn.setOnClickListener(v -> {
            String extractedUrl = extractUrl(apkUrlInput.getText().toString().trim());
            if (extractedUrl != null && extractedUrl.startsWith("http")) {
                dialog.dismiss();
                manager.installCustomApk(extractedUrl);
            } else {
                Toast.makeText(this, "No valid URL found", Toast.LENGTH_SHORT).show();
            }
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

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show();
        }
    }

    // --- DiscordAuth Callbacks ---

    @Override
    public void onStatus(String message) {
        onProgress(-1, message);
    }

    @Override
    public void onPatchUrl(String patchUrl) {
        DiscordAuthKeepAliveService.stop(this);
        dismissProgressDialog();
        manager.installCustomApk(patchUrl);
    }

    @Override
    public void onError(String title, String message, String joinUrl) {
        DiscordAuthKeepAliveService.stop(this);
        onTaskFinished();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null);
        if (joinUrl != null) builder.setNeutralButton("JOIN SERVER", (d, w) -> openUrl(joinUrl));
        builder.show();
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
                "   - Choose 'New player' option when reinstalling\n" +
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
        try {
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
            uninstallIntent.setData(Uri.parse("package:com.readyatdawn.r15"));
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(uninstallIntent);
        } catch (ActivityNotFoundException e) {
            Log.e("Uninstall", "Could not launch uninstall dialog.", e);
            Toast.makeText(this, "Could not open uninstaller.", Toast.LENGTH_SHORT).show();
        }
    }

    private String extractUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = Pattern.compile("(https?://[^\\s]+)").matcher(text);
        return m.find() ? m.group(1) : null;
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
        discordAuth.cancel();
        DiscordAuthKeepAliveService.stop(this);
        manager.shutdown();
        testerInstaller.shutdown();
        super.onDestroy();
    }
}