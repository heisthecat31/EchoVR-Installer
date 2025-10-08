package com.echovr.installer;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "EchoVRInstaller";

    private LinearLayout gameSelectionLayout;
    private LinearLayout mainContentLayout;
    private LinearLayout logViewerLayout;
    private TextView statusText;
    private TextView logContentText;
    private Button downloadButton;
    private Button reinstallButton;
    private Button openLogButton;
    private Button logBackButton;
    private Button copyLogButton;
    private ScrollView logScrollView;
    private AlertDialog progressDialog;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String ECHO_VR_LEGACY_URL = "https://evr.echo.taxi/r15_26-06-25.apk";
    private static final String DATA_URL = "https://mia.cdn.echo.taxi/_data.zip";
    private static final String ENHANCED_GRAPHICS_URL = "https://mia.cdn.echo.taxi/questEchoTextureMod_Alpha_v0.1_06-10-25.apk";

    private static final String TARGET_DIR = "readyatdawn/files";
    private static final String DATA_FOLDER = "_data";
    private static final String ECHO_VR_PACKAGE = "com.readyatdawn.r15";
    private static final String LOG_DIRECTORY = "r14logs";
    private static final long TOTAL_FILE_SIZE = 894L * 1024 * 1024;

    private static final String PREFS_NAME = "EchoVRInstallerPrefs";
    private static final String PREF_PERMISSION_POPUP_SHOWN = "permission_popup_shown";

    // Clipboard limits to prevent crashes
    private static final int MAX_CLIPBOARD_SIZE = 100000; // ~100KB limit
    private static final int MAX_LINES_TO_COPY = 1000; // Max lines to copy

    private String currentApkUrl = "";
    private boolean echoVrInstalled = false;
    private SharedPreferences prefs;
    private boolean justInstalledEchoVr = false;
    private String currentLogContent = "";

    private final ActivityResultLauncher<Intent> manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        showGameSelectionScreen();
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> installPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> checkAndRequestStoragePermissions()
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreenTransparent();
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        initializeViews();
        checkInstallUnknownAppsPermission();
    }

    private void setupFullscreenTransparent() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
    }

    private void checkInstallUnknownAppsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                showInstallPermissionDialog();
                return;
            }
        }
        checkAndRequestStoragePermissions();
    }

    private void showInstallPermissionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.install_permission_title));
        builder.setMessage(getString(R.string.install_permission_message));
        builder.setPositiveButton(getString(R.string.grant_permission), (dialog, which) -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + getPackageName()));
            installPermissionLauncher.launch(intent);
        });
        builder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> finish());
        builder.setCancelable(false);
        builder.show();
    }

    private void initializeViews() {
        gameSelectionLayout = findViewById(R.id.gameSelectionLayout);
        mainContentLayout = findViewById(R.id.mainContentLayout);
        logViewerLayout = findViewById(R.id.logViewerLayout);
        statusText = findViewById(R.id.statusText);
        logContentText = findViewById(R.id.logContentText);
        downloadButton = findViewById(R.id.downloadButton);
        reinstallButton = findViewById(R.id.reinstallButton);
        openLogButton = findViewById(R.id.openLogButton);
        logBackButton = findViewById(R.id.logBackButton);
        copyLogButton = findViewById(R.id.copyLogButton);
        logScrollView = findViewById(R.id.logScrollView);

        findViewById(R.id.legacyOption).setOnClickListener(v -> downloadLegacyEchoVr());
        findViewById(R.id.newPlayerOption).setOnClickListener(v -> showNewPlayerDialog());
        findViewById(R.id.enhancedGraphicsOption).setOnClickListener(v -> downloadEnhancedGraphicsPackage());

        downloadButton.setOnClickListener(v -> startDataDownload());
        reinstallButton.setOnClickListener(v -> showReinstallConfirmation());
        openLogButton.setOnClickListener(v -> openRecentLog());
        logBackButton.setOnClickListener(v -> showMainContentScreen());
        copyLogButton.setOnClickListener(v -> copyLogToClipboard());
    }

    private void copyLogToClipboard() {
        if (TextUtils.isEmpty(currentLogContent)) {
            Toast.makeText(this, R.string.no_log_content, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String textToCopy = getSafeCopyText(currentLogContent);

            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Echo VR Log", textToCopy);
                clipboard.setPrimaryClip(clip);

                // Show appropriate message based on copy size
                if (textToCopy.length() < currentLogContent.length()) {
                    Toast.makeText(this, getString(R.string.truncated_copy_message, MAX_LINES_TO_COPY), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, R.string.clipboard_unavailable, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Clipboard copy error: " + e.getMessage());
            Toast.makeText(this, getString(R.string.copy_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private String getSafeCopyText(String fullText) {
        if (TextUtils.isEmpty(fullText)) {
            return "";
        }

        // Check size first
        if (fullText.length() <= MAX_CLIPBOARD_SIZE) {
            return fullText;
        }

        // If too large, limit by lines
        String[] lines = fullText.split("\n");
        if (lines.length <= MAX_LINES_TO_COPY) {
            // Still too large even with line limit, truncate by size
            return fullText.substring(0, MAX_CLIPBOARD_SIZE) + "\n\n" + getString(R.string.truncated_size_message);
        }

        return buildLimitedText(lines);
    }

    private String buildLimitedText(String[] lines) {
        StringBuilder limitedText = new StringBuilder();
        int linesAdded = 0;
        for (String line : lines) {
            if (linesAdded >= MAX_LINES_TO_COPY) {
                break;
            }
            limitedText.append(line).append("\n");
            linesAdded++;

            // Also check total size during building
            if (limitedText.length() >= MAX_CLIPBOARD_SIZE) {
                break;
            }
        }

        limitedText.append("\n\n").append(getString(R.string.truncated_lines_message, linesAdded, lines.length));
        return limitedText.toString();
    }

    private void showReinstallConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reinstall_title)
                .setMessage(R.string.reinstall_message)
                .setPositiveButton(R.string.reinstall, (dialog, which) -> startDataDownload())
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(true)
                .show();
    }

    private void checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showStoragePermissionDialog();
                return;
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
                return;
            }
        }
        checkInitialEchoVrInstallation();
    }

    private void showStoragePermissionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.storage_permission_title);
        builder.setMessage(R.string.storage_permission_message);
        builder.setPositiveButton(R.string.grant_permission, (dialog, which) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    manageStorageLauncher.launch(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    manageStorageLauncher.launch(intent);
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> finish());
        builder.setCancelable(false);
        builder.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkInitialEchoVrInstallation();
        } else {
            finish();
        }
    }

    private boolean hasStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void showGameSelectionScreen() {
        mainHandler.post(() -> {
            gameSelectionLayout.setVisibility(View.VISIBLE);
            mainContentLayout.setVisibility(View.GONE);
            logViewerLayout.setVisibility(View.GONE);
        });
    }

    private void showMainContentScreen() {
        mainHandler.post(() -> {
            gameSelectionLayout.setVisibility(View.GONE);
            mainContentLayout.setVisibility(View.VISIBLE);
            logViewerLayout.setVisibility(View.GONE);
            checkDataStatus();
            checkAndShowPermissionPopupAfterInstall();
        });
    }

    private void showLogViewerScreen() {
        mainHandler.post(() -> {
            gameSelectionLayout.setVisibility(View.GONE);
            mainContentLayout.setVisibility(View.GONE);
            logViewerLayout.setVisibility(View.VISIBLE);
        });
    }

    private void downloadLegacyEchoVr() {
        currentApkUrl = ECHO_VR_LEGACY_URL;
        downloadAndInstallApk();
    }

    private void downloadEnhancedGraphicsPackage() {
        currentApkUrl = ENHANCED_GRAPHICS_URL;
        downloadAndInstallApk();
    }

    private void showNewPlayerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.new_player_title);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 30, 40, 20);
        mainLayout.setBackgroundColor(Color.parseColor("#1a237e"));

        TextView title = new TextView(this);
        title.setText(R.string.get_apk_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);
        mainLayout.addView(title);

        TextView instruction = new TextView(this);
        instruction.setText(R.string.join_discord_message);
        instruction.setTextColor(Color.LTGRAY);
        instruction.setTextSize(14);
        instruction.setPadding(0, 0, 0, 10);
        instruction.setGravity(Gravity.CENTER);
        mainLayout.addView(instruction);

        TextView discordLink = new TextView(this);
        discordLink.setText(R.string.discord_url);
        discordLink.setTextColor(Color.parseColor("#4fc3f7"));
        discordLink.setTextSize(14);
        discordLink.setTypeface(null, Typeface.BOLD);
        discordLink.setGravity(Gravity.CENTER);
        discordLink.setPadding(0, 0, 0, 20);
        discordLink.setOnClickListener(v -> openDiscordLink());
        mainLayout.addView(discordLink);

        TextView inputLabel = new TextView(this);
        inputLabel.setText(R.string.paste_url_label);
        inputLabel.setTextColor(Color.LTGRAY);
        inputLabel.setTextSize(12);
        inputLabel.setPadding(0, 0, 0, 10);
        mainLayout.addView(inputLabel);

        EditText apkUrlInput = new EditText(this);
        apkUrlInput.setHint(R.string.url_hint);
        apkUrlInput.setTextColor(Color.WHITE);
        apkUrlInput.setHintTextColor(Color.GRAY);
        apkUrlInput.setBackgroundColor(Color.parseColor("#37474f"));
        apkUrlInput.setPadding(20, 15, 20, 15);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, 0, 0, 20);
        apkUrlInput.setLayoutParams(inputParams);
        mainLayout.addView(apkUrlInput);

        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(Gravity.CENTER);

        Button cancelBtn = new Button(this);
        cancelBtn.setText(R.string.cancel);
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setBackgroundColor(Color.parseColor("#f44336"));
        cancelBtn.setPadding(30, 12, 30, 12);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(0, 0, 5, 0);
        cancelBtn.setLayoutParams(cancelParams);

        Button installBtn = new Button(this);
        installBtn.setText(R.string.install);
        installBtn.setTextColor(Color.WHITE);
        installBtn.setBackgroundColor(Color.parseColor("#4caf50"));
        installBtn.setPadding(30, 12, 30, 12);
        LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        installParams.setMargins(5, 0, 0, 0);
        installBtn.setLayoutParams(installParams);

        buttonLayout.addView(cancelBtn);
        buttonLayout.addView(installBtn);
        mainLayout.addView(buttonLayout);

        builder.setView(mainLayout);
        AlertDialog dialog = builder.create();

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        installBtn.setOnClickListener(v -> {
            String url = apkUrlInput.getText().toString().trim();
            if (url.startsWith("http")) {
                currentApkUrl = url;
                dialog.dismiss();
                downloadAndInstallApk();
            }
        });

        dialog.show();
    }

    private void openDiscordLink() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.discord_url))));
        } catch (Exception e) {
            // Silent fail
        }
    }

    private void downloadAndInstallApk() {
        if (!hasStoragePermissions()) {
            checkAndRequestStoragePermissions();
            return;
        }

        showProgressDialog(getString(R.string.downloading_apk));
        executorService.execute(() -> {
            File apkFile = downloadApkFile(currentApkUrl);
            mainHandler.post(() -> {
                if (apkFile != null) {
                    installApkFile(apkFile);
                } else {
                    showResult(false, getString(R.string.apk_download_failed));
                }
            });
        });
    }

    private File downloadApkFile(String apkUrl) {
        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;

        try {
            URL url = new URL(apkUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error: " + connection.getResponseCode());
                return null;
            }

            File apkDir = new File(getExternalFilesDir(null), "apk");
            if (!apkDir.exists() && !apkDir.mkdirs()) {
                Log.e(TAG, "Failed to create APK directory");
                return null;
            }

            String fileName = apkUrl.equals(ENHANCED_GRAPHICS_URL) ? "echo_vr_enhanced.apk" : "echo_vr.apk";
            File apkFile = new File(apkDir, fileName);

            if (apkFile.exists() && !apkFile.delete()) {
                Log.w(TAG, "Failed to delete existing APK file");
            }

            input = connection.getInputStream();
            output = new FileOutputStream(apkFile);

            byte[] buffer = new byte[8192];
            long totalDownloaded = 0;
            int bytesRead;
            int contentLength = connection.getContentLength();

            while ((bytesRead = input.read(buffer)) != -1) {
                totalDownloaded += bytesRead;
                output.write(buffer, 0, bytesRead);

                if (contentLength > 0) {
                    int progress = (int) ((totalDownloaded * 100) / contentLength);
                    String apkType = apkUrl.equals(ENHANCED_GRAPHICS_URL) ? getString(R.string.enhanced_apk) : getString(R.string.apk);
                    updateProgress(progress, getString(R.string.downloading_progress, apkType, progress));
                }
            }

            output.flush();
            return apkFile.exists() && apkFile.length() > 0 ? apkFile : null;

        } catch (Exception e) {
            Log.e(TAG, "APK download error: " + e.getMessage());
            return null;
        } finally {
            try {
                if (output != null) output.close();
                if (input != null) input.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams: " + e.getMessage());
            }
            if (connection != null) connection.disconnect();
        }
    }

    private void installApkFile(File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                showInstallPermissionDialog();
                return;
            }

            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apkFile);
            Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            installIntent.setData(apkUri);
            installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);

            if (installIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(installIntent);
                justInstalledEchoVr = true;
                mainHandler.postDelayed(this::checkEchoVrInstallation, 3000);
                dismissProgressDialog();
            } else {
                showResult(false, getString(R.string.no_installer_app));
            }

        } catch (Exception e) {
            showResult(false, getString(R.string.installation_failed));
            Log.e(TAG, "Installation error: " + e.getMessage());
        }
    }

    private void checkInitialEchoVrInstallation() {
        boolean isInstalled = isPackageInstalled(ECHO_VR_PACKAGE);
        if (isInstalled) {
            echoVrInstalled = true;
            showMainContentScreen();
        } else {
            echoVrInstalled = false;
            boolean popupShown = prefs.getBoolean(PREF_PERMISSION_POPUP_SHOWN, false);
            if (popupShown) {
                prefs.edit().remove(PREF_PERMISSION_POPUP_SHOWN).apply();
                Log.d(TAG, "Cleared permission popup cache - Echo VR not installed");
            }
            showGameSelectionScreen();
        }
    }

    private void checkEchoVrInstallation() {
        boolean isInstalled = isPackageInstalled(ECHO_VR_PACKAGE);
        mainHandler.post(() -> {
            if (isInstalled) {
                echoVrInstalled = true;
                showMainContentScreen();
            } else {
                echoVrInstalled = false;
                boolean popupShown = prefs.getBoolean(PREF_PERMISSION_POPUP_SHOWN, false);
                if (popupShown) {
                    prefs.edit().remove(PREF_PERMISSION_POPUP_SHOWN).apply();
                    Log.d(TAG, "Cleared permission popup cache - Echo VR uninstalled");
                }
            }
        });
    }

    private void checkAndShowPermissionPopupAfterInstall() {
        boolean popupShown = prefs.getBoolean(PREF_PERMISSION_POPUP_SHOWN, false);
        if (justInstalledEchoVr && !popupShown) {
            showEchoVrPermissionPopup();
        }
        justInstalledEchoVr = false;
    }

    private void showEchoVrPermissionPopup() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.grant_permissions_title)
                .setMessage(R.string.grant_permissions_message)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + ECHO_VR_PACKAGE));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.later, null)
                .setCancelable(false)
                .show();

        prefs.edit().putBoolean(PREF_PERMISSION_POPUP_SHOWN, true).apply();
        Log.d(TAG, "Permission popup shown and cached");
    }

    private void openRecentLog() {
        showProgressDialog(getString(R.string.loading_log));
        executorService.execute(() -> {
            try {
                File logFile = findMostRecentLogFile();
                if (logFile != null && logFile.exists()) {
                    String logContent = readLogFile(logFile);
                    currentLogContent = logContent;
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        logContentText.setText(logContent);
                        showLogViewerScreen();
                        autoFitLogText();
                    });
                } else {
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        showResult(false, getString(R.string.no_log_files));
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    dismissProgressDialog();
                    showResult(false, getString(R.string.error_reading_log, e.getMessage()));
                });
            }
        });
    }

    private void autoFitLogText() {
        logContentText.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                logContentText.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                int availableWidth = logScrollView.getWidth() - logScrollView.getPaddingLeft() - logScrollView.getPaddingRight() - 10;
                float optimalSize = calculateOptimalTextSize(logContentText.getText().toString(), availableWidth);
                logContentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, optimalSize);
            }
        });
    }

    private float calculateOptimalTextSize(String text, int availableWidth) {
        if (TextUtils.isEmpty(text)) return 11f;

        String[] lines = text.split("\n");
        float maxLineWidth = 0;
        float testSize = 11f;

        android.text.TextPaint testPaint = new android.text.TextPaint();
        testPaint.setTypeface(Typeface.MONOSPACE);

        for (String line : lines) {
            testPaint.setTextSize(testSize);
            float lineWidth = testPaint.measureText(line);
            if (lineWidth > maxLineWidth) {
                maxLineWidth = lineWidth;
            }
        }

        if (maxLineWidth <= availableWidth) {
            return testSize;
        }

        float scaleFactor = availableWidth / maxLineWidth;
        float optimalSize = testSize * scaleFactor;

        return Math.max(8f, Math.min(13f, optimalSize));
    }

    private File findMostRecentLogFile() {
        File logDir = new File(Environment.getExternalStorageDirectory(), LOG_DIRECTORY);
        if (!logDir.exists() || !logDir.isDirectory()) {
            Log.e(TAG, "Log directory not found: " + logDir.getPath());
            return null;
        }

        File[] logFiles = logDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".log") || name.toLowerCase().endsWith(".txt")
        );

        if (logFiles == null || logFiles.length == 0) {
            Log.e(TAG, "No log files found in directory");
            return null;
        }

        File mostRecent = null;
        for (File file : logFiles) {
            if (mostRecent == null || file.lastModified() > mostRecent.lastModified()) {
                mostRecent = file;
            }
        }

        Log.d(TAG, "Found most recent log file: " + (mostRecent != null ? mostRecent.getName() : "null"));
        return mostRecent;
    }

    private String readLogFile(File logFile) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading log file: " + e.getMessage());
            return getString(R.string.error_reading_log, e.getMessage());
        }
        return content.toString();
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void startDataDownload() {
        if (hasStoragePermissions() && isExternalStorageWritable()) {
            showProgressDialog(getString(R.string.downloading_data));
            executorService.execute(this::downloadAndExtractGameData);
        } else {
            checkAndRequestStoragePermissions();
        }
    }

    private boolean isExternalStorageWritable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    private void downloadAndExtractGameData() {
        try {
            File zipFile = downloadGameDataFile();
            if (zipFile == null) {
                showResult(false, getString(R.string.download_failed));
                return;
            }

            updateProgress(-1, getString(R.string.extracting_data));
            boolean extractionSuccess = extractZipFile(zipFile);

            if (extractionSuccess) {
                showResult(true, getString(R.string.installation_complete));
            } else {
                showResult(false, getString(R.string.extraction_failed));
            }

        } catch (Exception e) {
            showResult(false, getString(R.string.installation_error));
            Log.e(TAG, "Data download error: " + e.getMessage());
        }
    }

    private File downloadGameDataFile() {
        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;

        try {
            URL url = new URL(DATA_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Data download HTTP error: " + connection.getResponseCode());
                return null;
            }

            int contentLength = connection.getContentLength();
            long fileSize = contentLength > 0 ? contentLength : TOTAL_FILE_SIZE;

            File dataDir = getExternalFilesDir("downloads");
            if (dataDir == null || !dataDir.canWrite()) {
                Log.e(TAG, "Cannot write to data directory");
                return null;
            }

            File zipFile = new File(dataDir, "game_data.zip");
            if (zipFile.exists() && !zipFile.delete()) {
                Log.w(TAG, "Failed to delete existing zip file");
            }

            input = connection.getInputStream();
            output = new FileOutputStream(zipFile);

            byte[] buffer = new byte[8192];
            long totalDownloaded = 0;
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                totalDownloaded += bytesRead;
                output.write(buffer, 0, bytesRead);

                int progress = (int) ((totalDownloaded * 100) / fileSize);
                long mbDownloaded = totalDownloaded / (1024 * 1024);
                updateProgress(progress, getString(R.string.downloading_data_progress, progress, mbDownloaded));
            }

            output.flush();
            return zipFile.exists() && zipFile.length() > 0 ? zipFile : null;

        } catch (Exception e) {
            Log.e(TAG, "Game data download error: " + e.getMessage());
            return null;
        } finally {
            try {
                if (output != null) output.close();
                if (input != null) input.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams: " + e.getMessage());
            }
            if (connection != null) connection.disconnect();
        }
    }

    private boolean extractZipFile(File zipFile) {
        if (!zipFile.exists()) {
            Log.e(TAG, "ZIP file does not exist: " + zipFile.getPath());
            return false;
        }

        File targetDir = new File(Environment.getExternalStorageDirectory(), TARGET_DIR);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Log.e(TAG, "Failed to create target directory: " + targetDir.getPath());
            return false;
        }

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                File outputFile = new File(targetDir, entry.getName());

                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        Log.e(TAG, "Failed to create directory: " + outputFile.getPath());
                    }
                } else {
                    File parentDir = outputFile.getParentFile();
                    if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                        Log.e(TAG, "Failed to create parent directory: " + parentDir.getPath());
                        continue;
                    }

                    try (FileOutputStream fos = new FileOutputStream(outputFile);
                         BufferedOutputStream bos = new BufferedOutputStream(fos, buffer.length)) {
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, length);
                        }
                        bos.flush();
                    }
                }
                zis.closeEntry();
            }

            if (!zipFile.delete()) {
                Log.w(TAG, "Failed to delete ZIP file after extraction");
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Extraction error: " + e.getMessage());
            return false;
        }
    }

    private void checkDataStatus() {
        File dataDir = new File(Environment.getExternalStorageDirectory(), TARGET_DIR + "/" + DATA_FOLDER);
        boolean dataExists = dataDir.exists() && dataDir.isDirectory();
        File[] files = dataExists ? dataDir.listFiles() : null;

        if (files != null && files.length > 0) {
            showDataInstalled();
        } else {
            showDataMissing();
        }
    }

    private void showDataInstalled() {
        mainHandler.post(() -> {
            statusText.setText(R.string.data_installed);
            statusText.setTextColor(Color.parseColor("#4caf50"));
            downloadButton.setVisibility(View.GONE);
            reinstallButton.setVisibility(View.VISIBLE);
        });
    }

    private void showDataMissing() {
        mainHandler.post(() -> {
            statusText.setText(R.string.data_missing);
            statusText.setTextColor(Color.parseColor("#ff9800"));
            downloadButton.setVisibility(View.VISIBLE);
            reinstallButton.setVisibility(View.GONE);
        });
    }

    private void showProgressDialog(String message) {
        mainHandler.post(() -> {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
            ProgressBar progressBar = dialogView.findViewById(R.id.dialogProgressBar);
            TextView progressText = dialogView.findViewById(R.id.dialogProgressText);

            if (progressBar != null) progressBar.setMax(100);
            if (progressText != null) progressText.setText(message);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.installer_title);
            builder.setView(dialogView);
            builder.setCancelable(false);

            progressDialog = builder.create();
            progressDialog.show();
            updateProgress(0, message);
        });
    }

    private void updateProgress(int progress, String message) {
        mainHandler.post(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                View dialogView = progressDialog.findViewById(android.R.id.content);
                if (dialogView != null) {
                    TextView progressText = dialogView.findViewById(R.id.dialogProgressText);
                    ProgressBar progressBar = dialogView.findViewById(R.id.dialogProgressBar);

                    if (progressText != null) progressText.setText(message);
                    if (progressBar != null) {
                        if (progress >= 0) {
                            progressBar.setProgress(progress);
                            progressBar.setIndeterminate(false);
                        } else {
                            progressBar.setIndeterminate(true);
                        }
                    }
                }
            }
        });
    }

    private void showResult(boolean success, String message) {
        mainHandler.post(() -> {
            dismissProgressDialog();
            if (success) {
                checkDataStatus();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.error_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            }
        });
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!echoVrInstalled) {
            checkEchoVrInstallation();
        }
    }

    @Override
    protected void onDestroy() {
        executorService.shutdownNow();
        dismissProgressDialog();
        super.onDestroy();
    }
}
