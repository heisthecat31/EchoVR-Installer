package com.echovr.installer;

import android.app.AlertDialog;
import android.content.Intent;
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
    private TextView statusText;
    private Button downloadButton;
    private Button reinstallButton;
    private AlertDialog progressDialog;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String ECHO_VR_LEGACY_URL = "https://files.echovr.de/r15_26-06-25.apk";
    private static final String DATA_URL = "https://files.echovr.de/_data.zip";
    private static final String ENHANCED_GRAPHICS_URL = "https://files.echovr.de/cat/echo_graphics_boost.apk";

    private static final String TARGET_DIR = "readyatdawn/files";
    private static final String DATA_FOLDER = "_data";
    private static final String ECHO_VR_PACKAGE = "com.readyatdawn.r15";
    private static final long TOTAL_FILE_SIZE = 937L * 1024 * 1024;

    private String currentApkUrl = "";
    private boolean echoVrInstalled = false;

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
            result -> {
                checkAndRequestStoragePermissions();
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupFullscreenTransparent();
        setContentView(R.layout.activity_main);

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

        View legacyOption = findViewById(R.id.legacyOption);
        View newPlayerOption = findViewById(R.id.newPlayerOption);
        View enhancedGraphicsOption = findViewById(R.id.enhancedGraphicsOption);

        if (legacyOption != null) {
            legacyOption.setOnClickListener(v -> downloadLegacyEchoVr());
        }

        if (newPlayerOption != null) {
            newPlayerOption.setOnClickListener(v -> showNewPlayerDialog());
        }

        if (enhancedGraphicsOption != null) {
            enhancedGraphicsOption.setOnClickListener(v -> downloadEnhancedGraphicsPackage());
        }

        if (downloadButton != null) {
            downloadButton.setOnClickListener(v -> startDataDownload());
        }

        if (reinstallButton != null) {
            reinstallButton.setOnClickListener(v -> showReinstallConfirmation());
        }
    }

    private void showReinstallConfirmation() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Reinstall Game Data");
        builder.setMessage("This will download and reinstall all game data files. This may take several minutes. Continue?");
        builder.setPositiveButton("Reinstall", (dialog, which) -> {
            startDataDownload();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            // Simply dismiss
        });
        builder.setCancelable(true);
        builder.show();
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
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        1001);
                return;
            }
        }
        showGameSelectionScreen();
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
        if (requestCode == 1001 && grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showGameSelectionScreen();
            } else {
                finish();
            }
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
            if (gameSelectionLayout != null) gameSelectionLayout.setVisibility(View.VISIBLE);
            if (mainContentLayout != null) mainContentLayout.setVisibility(View.GONE);
        });
    }

    private void showMainContentScreen() {
        mainHandler.post(() -> {
            if (gameSelectionLayout != null) gameSelectionLayout.setVisibility(View.GONE);
            if (mainContentLayout != null) mainContentLayout.setVisibility(View.VISIBLE);
            checkDataStatus();
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
        builder.setTitle("New Player Installation");

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 30, 40, 20);
        mainLayout.setBackgroundColor(Color.parseColor("#1a237e"));

        TextView title = new TextView(this);
        title.setText("Get Echo VR APK");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);
        mainLayout.addView(title);

        TextView instruction = new TextView(this);
        instruction.setText("Join the Echo VR community Discord to get the latest APK file:");
        instruction.setTextColor(Color.LTGRAY);
        instruction.setTextSize(14);
        instruction.setPadding(0, 0, 0, 10);
        instruction.setGravity(Gravity.CENTER);
        mainLayout.addView(instruction);

        TextView discordLink = new TextView(this);
        discordLink.setText("https://discord.gg/NusGw8bjsC");
        discordLink.setTextColor(Color.parseColor("#4fc3f7"));
        discordLink.setTextSize(14);
        discordLink.setTypeface(null, Typeface.BOLD);
        discordLink.setGravity(Gravity.CENTER);
        discordLink.setPadding(0, 0, 0, 20);
        discordLink.setOnClickListener(v -> openDiscordLink());
        mainLayout.addView(discordLink);

        TextView inputLabel = new TextView(this);
        inputLabel.setText("Paste APK download URL:");
        inputLabel.setTextColor(Color.LTGRAY);
        inputLabel.setTextSize(12);
        inputLabel.setPadding(0, 0, 0, 10);
        mainLayout.addView(inputLabel);

        EditText apkUrlInput = new EditText(this);
        apkUrlInput.setHint("https://example.com/echo_vr.apk");
        apkUrlInput.setTextColor(Color.WHITE);
        apkUrlInput.setHintTextColor(Color.GRAY);
        apkUrlInput.setBackgroundColor(Color.parseColor("#37474f"));
        apkUrlInput.setPadding(20, 15, 20, 15);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(0, 0, 0, 20);
        apkUrlInput.setLayoutParams(inputParams);
        mainLayout.addView(apkUrlInput);

        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(Gravity.CENTER);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setBackgroundColor(Color.parseColor("#f44336"));
        cancelBtn.setPadding(30, 12, 30, 12);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(0, 0, 5, 0);
        cancelBtn.setLayoutParams(cancelParams);

        Button installBtn = new Button(this);
        installBtn.setText("Install");
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

        cancelBtn.setOnClickListener(v -> {
            dialog.dismiss();
        });

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
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/NusGw8bjsC"));
            startActivity(intent);
        } catch (Exception e) {
            // No toast on error
        }
    }

    private void downloadAndInstallApk() {
        if (!hasStoragePermissions()) {
            checkAndRequestStoragePermissions();
            return;
        }

        showProgressDialog("Downloading Echo VR APK...");
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

            if (apkFile.exists()) {
                apkFile.delete();
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
                    String apkType = apkUrl.equals(ENHANCED_GRAPHICS_URL) ? "Enhanced APK" : "APK";
                    updateProgress(progress, "Downloading " + apkType + ": " + progress + "%");
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !getPackageManager().canRequestPackageInstalls()) {
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
                mainHandler.postDelayed(this::checkEchoVrInstallation, 3000);
                dismissProgressDialog();
            } else {
                showResult(false, "No installer app found");
            }

        } catch (Exception e) {
            showResult(false, "Installation failed");
            Log.e(TAG, "Installation error: " + e.getMessage());
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
            }
        });
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
        if (hasStoragePermissions()) {
            if (isExternalStorageWritable()) {
                showProgressDialog("Downloading game data...");
                executorService.execute(this::downloadAndExtractGameData);
            }
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
                showResult(true, "Installation complete");
            } else {
                showResult(false, "Extraction failed");
            }

        } catch (Exception e) {
            showResult(false, "Installation error");
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
            if (zipFile.exists()) {
                zipFile.delete();
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
                String message = "Downloading: " + progress + "% (" + mbDownloaded + "MB)";
                updateProgress(progress, message);
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
                        continue;
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

            boolean zipDeleted = zipFile.delete();
            if (!zipDeleted) {
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
        int fileCount = files != null ? files.length : 0;

        if (fileCount > 0) {
            showDataInstalled();
        } else {
            showDataMissing();
        }
    }

    private void showDataInstalled() {
        mainHandler.post(() -> {
            statusText.setText("Game data installed");
            statusText.setTextColor(Color.parseColor("#4caf50"));
            downloadButton.setVisibility(View.GONE);
            reinstallButton.setVisibility(View.VISIBLE);
        });
    }

    private void showDataMissing() {
        mainHandler.post(() -> {
            statusText.setText("Game data not installed");
            statusText.setTextColor(Color.parseColor("#ff9800"));
            downloadButton.setVisibility(View.VISIBLE);
            reinstallButton.setVisibility(View.GONE);
            downloadButton.setText("DOWNLOAD GAME DATA (937MB)");
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
