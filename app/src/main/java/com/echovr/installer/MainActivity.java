package com.echovr.installer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
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
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private LinearLayout gameSelectionLayout;
    private LinearLayout mainContentLayout;
    private TextView statusText;
    private Button downloadButton;
    private Button reinstallButton;
    private Button grantPermissionsButton;
    private Button launchEchoVRButton;
    private Button clearCacheButton;
    private Button checkUpdatesButton;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // URLs
    private static final String ECHO_VR_LEGACY_URL = "https://evr.echo.taxi/r15_26-06-25.apk";
    private static final String DATA_URL = "https://mia.cdn.echo.taxi/_data.zip";
    private static final String ENHANCED_GRAPHICS_URL = "https://mia.cdn.echo.taxi/questEchoTextureMod_Alpha_v0.1_06-10-25.apk";
    private static final String GITHUB_RELEASES_URL = "https://api.github.com/repos/heisthecat31/EchoVR-Installer/releases/latest";
    private static final String DISCORD_INVITE_URL = "https://discord.gg/KQ8qGPKQeF";

    // File paths and constants
    private static final String TARGET_DIR = "readyatdawn/files";
    private static final String DATA_FOLDER = "_data";
    private static final String ECHO_VR_PACKAGE = "com.readyatdawn.r15";
    private static final long TOTAL_FILE_SIZE = 894L * 1024 * 1024;

    private static final String[] REQUIRED_DATA_PATHS = {
            "5932408047/rad15/android/manifests",
            "5932408047/rad15/android/packages"
    };
    private static final int MIN_REQUIRED_FILES_PER_FOLDER = 2;

    private static final String PREFS_NAME = "EchoVRInstallerPrefs";
    private static final String PREF_PERMISSION_POPUP_SHOWN = "permission_popup_shown";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";
    private static final String PREF_INSTALLATION_DATE = "installation_date";

    private String currentApkUrl = "";
    private boolean echoVrInstalled = false;
    private SharedPreferences prefs;
    private boolean justInstalledEchoVr = false;
    private AlertDialog progressDialog;

    private final ActivityResultLauncher<Intent> manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        checkInitialEchoVrInstallation();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
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
        builder.setTitle("Install Permission Required");
        builder.setMessage("This app needs permission to install APK files. Please grant the 'Install unknown apps' permission to continue.");
        builder.setPositiveButton("Grant Permission", (dialog, which) -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + getPackageName()));
            installPermissionLauncher.launch(intent);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> finish());
        builder.setCancelable(false);
        builder.show();
    }

    private void initializeViews() {
        gameSelectionLayout = findViewById(R.id.gameSelectionLayout);
        mainContentLayout = findViewById(R.id.mainContentLayout);
        statusText = findViewById(R.id.statusText);
        downloadButton = findViewById(R.id.downloadButton);
        reinstallButton = findViewById(R.id.reinstallButton);
        grantPermissionsButton = findViewById(R.id.grantPermissionsButton);
        launchEchoVRButton = findViewById(R.id.launchEchoVRButton);
        clearCacheButton = findViewById(R.id.clearCacheButton);
        checkUpdatesButton = findViewById(R.id.checkUpdatesButton);

        findViewById(R.id.legacyOption).setOnClickListener(v -> downloadLegacyEchoVr());
        findViewById(R.id.newPlayerOption).setOnClickListener(v -> showNewPlayerDialog());
        findViewById(R.id.enhancedGraphicsOption).setOnClickListener(v -> downloadEnhancedGraphicsPackage());

        downloadButton.setOnClickListener(v -> startDataDownload());
        reinstallButton.setOnClickListener(v -> showReinstallConfirmation());
        grantPermissionsButton.setOnClickListener(v -> grantEchoVRPermissions());
        launchEchoVRButton.setOnClickListener(v -> launchEchoVR());
        clearCacheButton.setOnClickListener(v -> showClearCacheConfirmation());
        checkUpdatesButton.setOnClickListener(v -> checkForUpdates());

        grantPermissionsButton.setVisibility(View.GONE);
        launchEchoVRButton.setVisibility(View.GONE);
        clearCacheButton.setVisibility(View.GONE);
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
        builder.setTitle("Storage Permission Required");
        builder.setMessage("This app needs storage permission to download and install game files.");
        builder.setPositiveButton("Grant Permission", (dialog, which) -> {
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
        builder.setNegativeButton("Cancel", (dialog, which) -> finish());
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
        });
    }

    private void showMainContentScreen() {
        mainHandler.post(() -> {
            gameSelectionLayout.setVisibility(View.GONE);
            mainContentLayout.setVisibility(View.VISIBLE);
            checkDataStatus();
            checkAndShowPermissionPopupAfterInstall();
        });
    }

    // ===== ECHO VR INSTALLATION METHODS =====

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
                }
            }
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
                openDiscordInvite();
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
            String extractedUrl = extractUrlFromText(inputText);
            if (extractedUrl != null && extractedUrl.startsWith("http")) {
                currentApkUrl = extractedUrl;
                dialog.dismiss();
                downloadAndInstallApk();
            } else {
                Toast.makeText(this, "No valid URL found in input", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
    }

    private void openDiscordInvite() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE_URL));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open Discord link", Toast.LENGTH_SHORT).show();
        }
    }

    private String extractUrlFromText(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }

        Pattern urlPattern = Pattern.compile("(https?://[^\\s]+)");
        Matcher matcher = urlPattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private void downloadAndInstallApk() {
        if (!hasStoragePermissions()) {
            checkAndRequestStoragePermissions();
            return;
        }

        showProgressDialog("Downloading APK...");
        executorService.execute(() -> {
            File apkFile = downloadApkFile(currentApkUrl);
            mainHandler.post(() -> {
                if (apkFile != null) {
                    installApkFile(apkFile);
                } else {
                    showResult(false, "APK download failed");
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
                return null;
            }

            File apkDir = new File(getExternalFilesDir(null), "apk");
            if (!apkDir.exists() && !apkDir.mkdirs()) {
                return null;
            }

            String fileName = apkUrl.equals(ENHANCED_GRAPHICS_URL) ? "echo_vr_enhanced.apk" : "echo_vr.apk";
            File apkFile = new File(apkDir, fileName);

            if (apkFile.exists()) apkFile.delete();

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
                    String apkType = apkUrl.equals(ENHANCED_GRAPHICS_URL) ? "Enhanced APK" : "APK";
                    updateProgress(progress, "Downloading " + apkType + ": " + progress + "%");
                }
            }

            output.flush();
            return apkFile.exists() && apkFile.length() > 0 ? apkFile : null;

        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (output != null) output.close();
                if (input != null) input.close();
            } catch (IOException e) {
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
                showResult(false, "No installer app found");
            }

        } catch (Exception e) {
            showResult(false, "Installation failed");
        }
    }

    // ===== GAME DATA DOWNLOAD AND INSTALLATION METHODS =====

    private void startDataDownload() {
        if (hasStoragePermissions() && isExternalStorageWritable()) {
            showProgressDialog("Downloading game data...");
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
                showResult(false, "Download failed");
                return;
            }

            updateProgress(-1, "Extracting data...");
            boolean extractionSuccess = extractZipFile(zipFile);

            if (extractionSuccess) {
                boolean dataValid = verifyDataInstallation();

                if (dataValid) {
                    String currentDate = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
                    prefs.edit().putString(PREF_INSTALLATION_DATE, currentDate).apply();
                    showResult(true, "Installation complete");
                } else {
                    showResult(false, "Data extraction incomplete - required files missing");
                }
            } else {
                showResult(false, "Extraction failed");
            }

        } catch (Exception e) {
            showResult(false, "Installation error");
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
                return null;
            }

            int contentLength = connection.getContentLength();
            long fileSize = contentLength > 0 ? contentLength : TOTAL_FILE_SIZE;

            File dataDir = getExternalFilesDir("downloads");
            if (dataDir == null || !dataDir.canWrite()) {
                return null;
            }

            File zipFile = new File(dataDir, "game_data.zip");
            if (zipFile.exists()) zipFile.delete();

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
                updateProgress(progress, "Downloading: " + progress + "% (" + mbDownloaded + "MB)");
            }

            output.flush();
            return zipFile.exists() && zipFile.length() > 0 ? zipFile : null;

        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (output != null) output.close();
                if (input != null) input.close();
            } catch (IOException e) {
            }
            if (connection != null) connection.disconnect();
        }
    }

    private boolean extractZipFile(File zipFile) {
        if (!zipFile.exists()) {
            return false;
        }

        File targetDir = new File(Environment.getExternalStorageDirectory(), TARGET_DIR);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return false;
        }

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                File outputFile = new File(targetDir, entry.getName());

                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                    }
                } else {
                    File parentDir = outputFile.getParentFile();
                    if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
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

            zipFile.delete();
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // ===== IMPROVED DATA INSTALLATION DETECTION =====

    private boolean verifyDataInstallation() {
        File baseDataDir = new File(Environment.getExternalStorageDirectory(), TARGET_DIR + "/" + DATA_FOLDER);

        if (!baseDataDir.exists() || !baseDataDir.isDirectory()) {
            return false;
        }

        for (String requiredPath : REQUIRED_DATA_PATHS) {
            File requiredDir = new File(baseDataDir, requiredPath);

            if (!requiredDir.exists() || !requiredDir.isDirectory()) {
                return false;
            }

            File[] files = requiredDir.listFiles();
            if (files == null || files.length < MIN_REQUIRED_FILES_PER_FOLDER) {
                return false;
            }
        }

        return true;
    }

    private void checkDataStatus() {
        boolean dataValid = verifyDataInstallation();

        if (dataValid) {
            showDataInstalled();
        } else {
            showDataMissing();
        }
    }

    private void showDataInstalled() {
        mainHandler.post(() -> {
            String installationDate = prefs.getString(PREF_INSTALLATION_DATE, "");
            String statusMessage = "Game data installed";
            if (!installationDate.isEmpty()) {
                statusMessage += "\nInstalled: " + installationDate;
            }

            statusText.setText(statusMessage);
            statusText.setTextColor(Color.parseColor("#4caf50"));
            downloadButton.setVisibility(View.GONE);
            reinstallButton.setVisibility(View.VISIBLE);
            grantPermissionsButton.setVisibility(View.VISIBLE);
            launchEchoVRButton.setVisibility(View.VISIBLE);
            clearCacheButton.setVisibility(View.VISIBLE);
            checkUpdatesButton.setVisibility(View.VISIBLE);
        });
    }

    private void showDataMissing() {
        mainHandler.post(() -> {
            statusText.setText("Game data not installed");
            statusText.setTextColor(Color.parseColor("#ff9800"));
            downloadButton.setVisibility(View.VISIBLE);
            reinstallButton.setVisibility(View.GONE);
            grantPermissionsButton.setVisibility(View.GONE);
            launchEchoVRButton.setVisibility(View.GONE);
            clearCacheButton.setVisibility(View.VISIBLE);
            checkUpdatesButton.setVisibility(View.VISIBLE);
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
                .setTitle("Grant Echo VR Permissions")
                .setMessage("For Echo VR to work properly, please grant it file permissions:\n\n" +
                        "Go to: Settings → Privacy → Installed Apps → Echo VR → Permissions → Allow all permissions")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + ECHO_VR_PACKAGE));
                    startActivity(intent);
                })
                .setNegativeButton("Later", null)
                .setCancelable(false)
                .show();

        prefs.edit().putBoolean(PREF_PERMISSION_POPUP_SHOWN, true).apply();
    }

    private void grantEchoVRPermissions() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + ECHO_VR_PACKAGE));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Error opening permissions: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void launchEchoVR() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(ECHO_VR_PACKAGE);
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                Toast.makeText(this, "Echo VR is not installed", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error launching Echo VR: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }


    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showReinstallConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Reinstall Game Data")
                .setMessage("This will download and reinstall all game data files. This may take several minutes. Continue?")
                .setPositiveButton("Reinstall", (dialog, which) -> startDataDownload())
                .setNegativeButton("Cancel", null)
                .setCancelable(true)
                .show();
    }

    private void showClearCacheConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Clear Cache")
                .setMessage("This will clear all downloaded temporary files and cache. This does not affect installed game data. Continue?")
                .setPositiveButton("Clear", (dialog, which) -> clearCache())
                .setNegativeButton("Cancel", null)
                .setCancelable(true)
                .show();
    }

    private void clearCache() {
        showProgressDialog("Clearing cache...");
        executorService.execute(() -> {
            try {
                File apkDir = new File(getExternalFilesDir(null), "apk");
                if (apkDir.exists()) {
                    deleteDirectory(apkDir);
                }

                File dataDir = getExternalFilesDir("downloads");
                if (dataDir != null && dataDir.exists()) {
                    deleteDirectory(dataDir);
                }

                File updateDir = new File(getExternalFilesDir(null), "updates");
                if (updateDir.exists()) {
                    deleteDirectory(updateDir);
                }

                mainHandler.post(() -> {
                    dismissProgressDialog();
                    Toast.makeText(MainActivity.this, "Cache cleared successfully", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    dismissProgressDialog();
                    Toast.makeText(MainActivity.this, "Error clearing cache: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }


    private void checkForUpdates() {
        showProgressDialog("Checking for updates...");
        executorService.execute(() -> {
            try {
                URL url = new URL(GITHUB_RELEASES_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "EchoVR-Installer");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject releaseInfo = new JSONObject(response.toString());
                String latestVersion = releaseInfo.getString("tag_name");
                String releaseNotes = releaseInfo.optString("body", "No release notes available.");

                JSONArray assets = releaseInfo.getJSONArray("assets");
                String downloadUrl = "";
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String assetName = asset.getString("name");
                    if (assetName.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                String currentVersion = getCurrentVersion();

                String finalDownloadUrl = downloadUrl;
                mainHandler.post(() -> {
                    dismissProgressDialog();
                    if (isNewVersionAvailable(currentVersion, latestVersion)) {
                        showUpdateAvailableDialog(latestVersion, releaseNotes, finalDownloadUrl);
                    } else {
                        Toast.makeText(MainActivity.this, "You have the latest version: " + currentVersion, Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    dismissProgressDialog();
                    Toast.makeText(MainActivity.this, "Update check failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String getCurrentVersion() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    private boolean isNewVersionAvailable(String currentVersion, String latestVersion) {
        currentVersion = currentVersion.replace("v", "").replace("V", "").trim();
        latestVersion = latestVersion.replace("v", "").replace("V", "").trim();

        String[] currentParts = currentVersion.split("\\.");
        String[] latestParts = latestVersion.split("\\.");

        for (int i = 0; i < Math.min(currentParts.length, latestParts.length); i++) {
            try {
                int currentPart = Integer.parseInt(currentParts[i]);
                int latestPart = Integer.parseInt(latestParts[i]);

                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            } catch (NumberFormatException e) {
                if (!latestParts[i].equals(currentParts[i])) {
                    return true;
                }
            }
        }

        return latestParts.length > currentParts.length;
    }

    private void showUpdateAvailableDialog(String latestVersion, String releaseNotes, String downloadUrl) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Update Available");

        String displayNotes = releaseNotes;
        if (releaseNotes.length() > 500) {
            displayNotes = releaseNotes.substring(0, 500) + "...\n\n[Release notes truncated]";
        }

        String message = "New version " + latestVersion + " is available!\n\n" +
                "Release Notes:\n" + displayNotes + "\n\n" +
                "Would you like to download and install the update now?";

        builder.setMessage(message);
        builder.setPositiveButton("DOWNLOAD UPDATE", (dialog, which) -> {
            if (!downloadUrl.isEmpty()) {
                downloadAndInstallUpdate(downloadUrl, latestVersion);
            } else {
                Toast.makeText(MainActivity.this, "Download URL not found", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("LATER", null);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void downloadAndInstallUpdate(String downloadUrl, String versionName) {
        showProgressDialog("Downloading update " + versionName + "...");
        executorService.execute(() -> {
            File apkFile = downloadUpdateApk(downloadUrl, versionName);
            mainHandler.post(() -> {
                if (apkFile != null && apkFile.exists()) {
                    installUpdateApk(apkFile);
                } else {
                    dismissProgressDialog();
                    Toast.makeText(MainActivity.this, "Update download failed", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private File downloadUpdateApk(String downloadUrl, String versionName) {
        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;

        try {
            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "EchoVR-Installer-Update");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            File apkDir = new File(getExternalFilesDir(null), "updates");
            if (!apkDir.exists() && !apkDir.mkdirs()) {
                return null;
            }

            String fileName = "EchoVR-Installer-" + versionName + ".apk";
            File apkFile = new File(apkDir, fileName);

            if (apkFile.exists()) apkFile.delete();

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
                    long mbDownloaded = totalDownloaded / (1024 * 1024);
                    updateProgress(progress, "Downloading update: " + progress + "% (" + mbDownloaded + "MB)");
                }
            }

            output.flush();
            return apkFile.exists() && apkFile.length() > 0 ? apkFile : null;

        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (output != null) output.close();
                if (input != null) input.close();
            } catch (IOException e) {
            }
            if (connection != null) connection.disconnect();
        }
    }

    private void installUpdateApk(File apkFile) {
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
                dismissProgressDialog();
                startActivity(installIntent);
            } else {
                showResult(false, "No installer app found");
            }

        } catch (Exception e) {
            showResult(false, "Update installation failed");
        }
    }


    private void showProgressDialog(String message) {
        mainHandler.post(() -> {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
            ProgressBar progressBar = dialogView.findViewById(R.id.dialogProgressBar);
            TextView progressText = dialogView.findViewById(R.id.dialogProgressText);

            if (progressBar != null) progressBar.setMax(100);
            if (progressText != null) progressText.setText(message);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Echo VR Installer");
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
                        .setTitle("Error")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
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
