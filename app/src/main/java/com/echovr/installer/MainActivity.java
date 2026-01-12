package com.echovr.installer;

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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
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

public class MainActivity extends AppCompatActivity implements InstallerManager.Listener {

    private InstallerManager manager;
    private SharedPreferences prefs;

    // UI Elements
    private LinearLayout gameSelectionLayout, mainContentLayout;
    private TextView statusText;
    private Button downloadButton, reinstallButton, grantPermissionsButton, launchEchoVRButton, uninstallEchoVRButton;
    private Button clearCacheButtonGame, checkUpdatesButtonGame, clearCacheButtonMain, checkUpdatesButtonMain;
    private Button helpButtonGame, helpButtonMain;
    private AlertDialog progressDialog;

    private static final String PREFS_NAME = "EchoVRInstallerPrefs";
    private static final String PREF_PERMISSION_POPUP_SHOWN = "permission_popup_shown";
    private static final String ECHO_VR_PACKAGE = "com.readyatdawn.r15";
    private static final String DISCORD_INVITE_URL = "https://discord.gg/KQ8qGPKQeF";
    private boolean justInstalledEchoVr = false;

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
        manager.fetchRemoteConfig();

        initializeViews();
        checkInstallPermission(); 
    }

    private void setupFullscreenTransparent() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
    }

    private void initializeViews() {
        gameSelectionLayout = findViewById(R.id.gameSelectionLayout);
        mainContentLayout = findViewById(R.id.mainContentLayout);
        statusText = findViewById(R.id.statusText);

        downloadButton = findViewById(R.id.downloadButton);
        reinstallButton = findViewById(R.id.reinstallButton);
        grantPermissionsButton = findViewById(R.id.grantPermissionsButton);
        launchEchoVRButton = findViewById(R.id.launchEchoVRButton);
        uninstallEchoVRButton = findViewById(R.id.viewLobbyLinkButton); // Reused ID per original xml
        uninstallEchoVRButton.setText("UNINSTALL ECHO VR");

        // Options
        findViewById(R.id.legacyOption).setOnClickListener(v -> manager.installLegacyEchoVr());
        findViewById(R.id.enhancedGraphicsOption).setOnClickListener(v -> manager.installEnhancedGraphics());
        findViewById(R.id.newPlayerOption).setOnClickListener(v -> showNewPlayerDialog());

        // Actions
        downloadButton.setOnClickListener(v -> manager.installGameData());
        reinstallButton.setOnClickListener(v -> showConfirmDialog("Reinstall Game Data", "This will re-download all game data.", () -> manager.installGameData()));
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
        View.OnClickListener checkUpdates = v -> manager.checkForUpdates(getCurrentVersion());

        clearCacheButtonGame = findViewById(R.id.clearCacheButtonGame);
        clearCacheButtonMain = findViewById(R.id.clearCacheButtonMain);
        clearCacheButtonGame.setOnClickListener(clearCache);
        clearCacheButtonMain.setOnClickListener(clearCache);

        checkUpdatesButtonGame = findViewById(R.id.checkUpdatesButtonGame);
        checkUpdatesButtonMain = findViewById(R.id.checkUpdatesButtonMain);
        checkUpdatesButtonGame.setOnClickListener(checkUpdates);
        checkUpdatesButtonMain.setOnClickListener(checkUpdates);

        helpButtonGame = findViewById(R.id.helpButtonGame);
        helpButtonMain = findViewById(R.id.helpButtonMain);
        View.OnClickListener help = v -> showHelpDialog();
        helpButtonGame.setOnClickListener(help);
        helpButtonMain.setOnClickListener(help);
    }
    

    private void checkState() {
        boolean isInstalled = isPackageInstalled(ECHO_VR_PACKAGE);

        if (isInstalled) {
            gameSelectionLayout.setVisibility(View.GONE);
            mainContentLayout.setVisibility(View.VISIBLE);
            helpButtonGame.setVisibility(View.GONE);
            helpButtonMain.setVisibility(View.VISIBLE);

            if (manager.verifyDataInstallation()) {
                String date = prefs.getString("installation_date", "");
                String msg = "Game data installed";
                if (!date.isEmpty()) msg += "\nInstalled: " + date;

                statusText.setText(msg);
                statusText.setTextColor(Color.GREEN);
                downloadButton.setVisibility(View.GONE);
                reinstallButton.setVisibility(View.VISIBLE);
                grantPermissionsButton.setVisibility(View.VISIBLE);

                boolean hasPerms = hasFilePermissions();
                launchEchoVRButton.setVisibility(hasPerms ? View.VISIBLE : View.GONE);
                uninstallEchoVRButton.setVisibility(View.VISIBLE);
            } else {
                statusText.setText("Game data missing");
                statusText.setTextColor(Color.parseColor("#ff9800"));
                downloadButton.setVisibility(View.VISIBLE);
                reinstallButton.setVisibility(View.GONE);
                grantPermissionsButton.setVisibility(View.GONE);
                launchEchoVRButton.setVisibility(View.GONE);
                uninstallEchoVRButton.setVisibility(View.GONE);
            }

            if (justInstalledEchoVr && !prefs.getBoolean(PREF_PERMISSION_POPUP_SHOWN, false)) {
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
        dismissProgressDialog();
        checkState();
        new AlertDialog.Builder(this).setTitle("Success").setMessage(message).setPositiveButton("OK", null).show();
    }

    @Override
    public void onError(String message) {
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
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            } else {
                checkState();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkState();
        } else {
            Toast.makeText(this, "Permission denied. App cannot function.", Toast.LENGTH_SHORT).show();
        }
    }
    

    private void showProgressDialog(String message) {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) return;
            View view = getLayoutInflater().inflate(R.layout.dialog_progress, null);
            TextView txt = view.findViewById(R.id.dialogProgressText);
            txt.setText(message);
            progressDialog = new AlertDialog.Builder(this)
                    .setView(view)
                    .setCancelable(false)
                    .setNegativeButton("Cancel", (d, w) -> manager.cancelCurrentTask())
                    .create();
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
        builder.setTitle("Custom APK Installation");

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(50, 40, 50, 30);
        mainLayout.setBackgroundColor(Color.parseColor("#1a1a1a"));

        TextView instruction = new TextView(this);
        instruction.setText("Enter APK download URL:");
        instruction.setTextColor(Color.WHITE);
        instruction.setTextSize(16);
        instruction.setTypeface(null, Typeface.BOLD);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, 0, 0, 20);
        mainLayout.addView(instruction);

        EditText apkUrlInput = new EditText(this);
        apkUrlInput.setHint("https://example.com/echo_vr.apk");
        apkUrlInput.setTextColor(Color.WHITE);
        apkUrlInput.setHintTextColor(Color.parseColor("#888888"));
        apkUrlInput.setBackground(getResources().getDrawable(R.drawable.edit_text_background));
        apkUrlInput.setPadding(25, 20, 25, 20);
        apkUrlInput.setTextSize(14);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, 0, 0, 25);
        apkUrlInput.setLayoutParams(inputParams);
        mainLayout.addView(apkUrlInput);

        TextView noteText = new TextView(this);
        String discordText = "Get APK Link From The Discord";
        SpannableString spannableString = new SpannableString(discordText);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE_URL)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Cannot open link", Toast.LENGTH_SHORT).show();
                }
            }
        };
        spannableString.setSpan(clickableSpan, 21, 29, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        noteText.setText(spannableString);
        noteText.setMovementMethod(LinkMovementMethod.getInstance());
        noteText.setHighlightColor(Color.parseColor("#40ffffff"));
        noteText.setTextColor(Color.parseColor("#888888"));
        noteText.setTextSize(12);
        noteText.setGravity(Gravity.CENTER);
        noteText.setPadding(0, 0, 0, 20);
        mainLayout.addView(noteText);

        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(Gravity.CENTER);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("CANCEL");
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setBackground(getResources().getDrawable(R.drawable.button_background_secondary));
        cancelBtn.setPadding(40, 15, 40, 15);
        cancelBtn.setTextSize(14);
        cancelBtn.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(0, 0, 10, 0);
        cancelBtn.setLayoutParams(cancelParams);

        Button installBtn = new Button(this);
        installBtn.setText("INSTALL");
        installBtn.setTextColor(Color.WHITE);
        installBtn.setBackground(getResources().getDrawable(R.drawable.button_background));
        installBtn.setPadding(40, 15, 40, 15);
        installBtn.setTextSize(14);
        installBtn.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        installParams.setMargins(10, 0, 0, 0);
        installBtn.setLayoutParams(installParams);

        buttonLayout.addView(cancelBtn);
        buttonLayout.addView(installBtn);
        mainLayout.addView(buttonLayout);

        builder.setView(mainLayout);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        installBtn.setOnClickListener(v -> {
            String inputText = apkUrlInput.getText().toString().trim();
            String extractedUrl = extractUrl(inputText);
            if (extractedUrl != null && extractedUrl.startsWith("http")) {
                dialog.dismiss();
                manager.installCustomApk(extractedUrl);
            } else {
                Toast.makeText(this, "No valid URL found", Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
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
        new AlertDialog.Builder(this)
                .setTitle("Grant Echo VR Permissions")
                .setMessage("For Echo VR to work properly, please grant it file permissions:\n\n" +
                        "Go to: Settings → Privacy → Installed Apps → Echo VR → Permissions → Allow all permissions")
                .setPositiveButton("Open Settings", (d, w) -> openAppPermissions())
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
            // Create the intent with the ACTION_DELETE action
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
            // Format the URI correctly with the package name
            uninstallIntent.setData(Uri.parse("package:com.readyatdawn.r15"));
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Start the system uninstall activity
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
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { return "1.0"; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkState();
        
        new Handler(Looper.getMainLooper()).postDelayed(this::checkState, 2000);

        if (manager.verifyDataInstallation() && !isPackageInstalled(ECHO_VR_PACKAGE)) {
            checkState();
        }
    }

    @Override
    protected void onDestroy() {
        manager.shutdown();
        super.onDestroy();
    }
}
