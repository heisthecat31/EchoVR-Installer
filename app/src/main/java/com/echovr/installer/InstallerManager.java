package com.echovr.installer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class InstallerManager {

    public interface Listener {
        void onProgress(int progress, String message);
        void onSuccess(String message);
        void onError(String message);
        void onTaskStarted();
        void onTaskFinished();
        void onUpdateAvailable(String version, String notes, String downloadUrl);
        void onUpdateNotAvailable();
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private final SharedPreferences prefs;
    private Future<?> currentTask;
    private boolean isTaskCancelled = false;

    private static final String REMOTE_CONFIG_URL = "https://github.com/heisthecat31/EchoVR-Installer/releases/download/Installer/config.json";

    public String echoVrLegacyUrl = "https://files.echovr.de/r15_26-06-25.apk";
    public String dataUrl = "https://mia.cdn.echo.taxi/_data.zip";
    public String enhancedGraphicsUrl = "https://mia.cdn.echo.taxi/questEchoTextureMod_Alpha_v0.1_06-10-25.apk";
    public String backupDataUrl = "https://files.echovr.de/_data.zip";
    public String backupLegacyUrl = "https://evr.echo.taxi/r15_26-06-25.apk";
    public String backupEnhancedGraphicsUrl = "https://files.echovr.de/cat/questEchoTextureMod_Alpha_v0.1_06-10-25.apk";

    private static final String GITHUB_RELEASES_URL = "https://api.github.com/repos/heisthecat31/EchoVR-Installer/releases/latest";
    private static final String TARGET_DIR = "readyatdawn/files";
    private static final String DATA_FOLDER = "_data";
    private static final String PREFS_NAME = "EchoVRInstallerPrefs";
    private static final String PREF_INSTALLATION_DATE = "installation_date";
    private static final long TOTAL_FILE_SIZE = 894L * 1024 * 1024;
    private static final long REQUIRED_SPACE_BYTES = 2500L * 1024 * 1024;

    public InstallerManager(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void cancelCurrentTask() {
        isTaskCancelled = true;
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        mainHandler.post(listener::onTaskFinished);
    }

    public void fetchRemoteConfig() {
        executorService.execute(() -> {
            try {
                URL url = new URL(REMOTE_CONFIG_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK ||
                        connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();
                    JSONObject config = new JSONObject(response.toString());
                    if (config.has("legacyUrl")) echoVrLegacyUrl = config.getString("legacyUrl");
                    if (config.has("dataUrl")) dataUrl = config.getString("dataUrl");
                    if (config.has("enhancedUrl")) enhancedGraphicsUrl = config.getString("enhancedUrl");
                    if (config.has("backupLegacyUrl")) backupLegacyUrl = config.getString("backupLegacyUrl");
                    if (config.has("backupDataUrl")) backupDataUrl = config.getString("backupDataUrl");
                    if (config.has("backupEnhancedUrl")) backupEnhancedGraphicsUrl = config.getString("backupEnhancedUrl");
                }
            } catch (Exception ignored) { }
        });
    }

    public void installLegacyEchoVr() {
        downloadAndInstallApkWithBackup(echoVrLegacyUrl, backupLegacyUrl, "Legacy Echo VR", "echo_vr_legacy.apk");
    }

    public void installEnhancedGraphics() {
        downloadAndInstallApkWithBackup(enhancedGraphicsUrl, backupEnhancedGraphicsUrl, "Enhanced Graphics", "echo_vr_enhanced.apk");
    }

    public void installCustomApk(String url) {
        startApkDownload(url, "Custom APK", false, null, "echo_vr_custom.apk");
    }

    private void downloadAndInstallApkWithBackup(String primaryUrl, String backupUrl, String name, String saveFileName) {
        startApkDownload(primaryUrl, name, true, backupUrl, saveFileName);
    }

    private void startApkDownload(String url, String name, boolean useBackup, String backupUrl, String saveFileName) {
        if (!hasEnoughSpace()) return;
        isTaskCancelled = false;
        mainHandler.post(listener::onTaskStarted);

        currentTask = executorService.submit(() -> {
            File apkFile = downloadFile(url, "apk", saveFileName, name, false);

            if (apkFile == null && useBackup && !isTaskCancelled && backupUrl != null) {
                mainHandler.post(() -> listener.onProgress(0, "First link failed, trying backup..."));
                // Resume download using backup URL
                apkFile = downloadFile(backupUrl, "apk", saveFileName, name, true);
            }

            File finalApk = apkFile;
            mainHandler.post(() -> {
                listener.onTaskFinished();
                if (isTaskCancelled) return;
                if (finalApk != null) {
                    launchApkInstaller(finalApk);
                } else {
                    listener.onError(name + " download failed from all sources.");
                }
            });
        });
    }

    public void installGameData() {
        if (!hasEnoughSpace()) return;
        isTaskCancelled = false;
        mainHandler.post(listener::onTaskStarted);

        currentTask = executorService.submit(() -> {
            File zipFile = downloadFile(dataUrl, "downloads", "game_data.zip", "Game Data", false);

            // If primary failed (returned null), try backup. Backup will see existing partial file and resume.
            if (zipFile == null && !isTaskCancelled) {
                mainHandler.post(() -> listener.onProgress(0, "Primary failed, trying backup..."));
                zipFile = downloadFile(backupDataUrl, "downloads", "game_data.zip", "Game Data", true);
            }

            if (zipFile == null || isTaskCancelled) {
                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    if (!isTaskCancelled) listener.onError("Data download failed.");
                });
                return;
            }

            // 2. Extract
            mainHandler.post(() -> listener.onProgress(-1, "Extracting data..."));
            boolean success = extractZipFile(zipFile);

            mainHandler.post(() -> {
                listener.onTaskFinished();
                if (isTaskCancelled) return;
                if (success) {
                    if (verifyDataInstallation()) {
                        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
                        prefs.edit().putString(PREF_INSTALLATION_DATE, date).apply();
                        listener.onSuccess("Installation complete");
                    } else {
                        listener.onError("Extraction incomplete - files missing.");
                    }
                } else {
                    listener.onError("Extraction failed.");
                }
            });
        });
    }

    private File downloadFile(String urlString, String dirType, String fileName, String logName, boolean isBackup) {
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;

        try {
            File dir = context.getExternalFilesDir(dirType);
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, fileName);
            long downloaded = 0;
            if (file.exists()) downloaded = file.length();

            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (downloaded > 0) {
                connection.setRequestProperty("Range", "bytes=" + downloaded + "-");
            }

            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.connect();

            int responseCode = connection.getResponseCode();

            if (responseCode == 416 && downloaded > 0) {
                connection.disconnect();
                return file;
            }

            boolean isResuming = (responseCode == HttpURLConnection.HTTP_PARTIAL);

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                if (downloaded > 0) {
                    file.delete();
                    downloaded = 0;
                    connection.disconnect();
                    connection = (HttpURLConnection) url.openConnection();
                    connection.connect();
                    responseCode = connection.getResponseCode();
                }
                if (responseCode != HttpURLConnection.HTTP_OK) return null;
            }

            // If we asked to resume but server sent 200 OK (fresh file), restart counter
            if (!isResuming && downloaded > 0) {
                file.delete();
                downloaded = 0;
            }

            int contentLength = connection.getContentLength();
            long totalFileSize = (contentLength > 0) ? (contentLength + downloaded) : (fileName.contains("zip") ? TOTAL_FILE_SIZE : 0);

            input = connection.getInputStream();
            output = new FileOutputStream(file, isResuming);

            byte[] buffer = new byte[32768];
            long totalDownloaded = downloaded;
            int bytesRead;

            String prefix = isBackup ? "Backup " + logName : logName;

            while ((bytesRead = input.read(buffer)) != -1) {
                if (isTaskCancelled) {
                    output.close();
                    input.close();
                    return null;
                }
                totalDownloaded += bytesRead;
                output.write(buffer, 0, bytesRead);

                if (totalFileSize > 0) {
                    int progress = (int) ((totalDownloaded * 100) / totalFileSize);
                    long mb = totalDownloaded / (1024 * 1024);
                    mainHandler.post(() -> listener.onProgress(progress, "Downloading " + prefix + ": " + progress + "% (" + mb + "MB)"));
                }
            }

            output.flush();
            return (file.exists() && file.length() > 0) ? file : null;

        } catch (Exception e) {
            Log.e("InstallerManager", "Download error: " + e.getMessage());
            // Return null on exception (e.g. wifi cut), which allows the backup logic to pick up
            return null;
        } finally {
            try {
                if (output != null) output.close();
                if (input != null) input.close();
            } catch (IOException ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    private boolean extractZipFile(File zipFile) {
        File targetDir = new File(Environment.getExternalStorageDirectory(), TARGET_DIR);
        if (!targetDir.exists() && !targetDir.mkdirs()) return false;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = zis.getNextEntry()) != null) {
                if (isTaskCancelled) return false;

                File outputFile = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    if (!outputFile.exists()) outputFile.mkdirs();
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    try (FileOutputStream fos = new FileOutputStream(outputFile);
                         BufferedOutputStream bos = new BufferedOutputStream(fos, buffer.length)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            if (isTaskCancelled) return false;
                            bos.write(buffer, 0, len);
                        }
                        bos.flush();
                    }
                }
                zis.closeEntry();
            }
            zipFile.delete();
            return true;
        } catch (Exception e) {
            Log.e("InstallerManager", "Extraction error", e);
            return false;
        }
    }

    public boolean verifyDataInstallation() {
        File baseDir = new File(Environment.getExternalStorageDirectory(), TARGET_DIR + "/" + DATA_FOLDER);
        if (!baseDir.exists()) return false;
        String[] requiredPaths = {
                "5932408047/rad15/android/manifests",
                "5932408047/rad15/android/packages"
        };
        for (String path : requiredPaths) {
            File dir = new File(baseDir, path);
            if (!dir.exists() || !dir.isDirectory()) return false;
            File[] files = dir.listFiles();
            if (files == null || files.length < 2) return false;
        }
        return true;
    }

    private void launchApkInstaller(File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apkFile);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            context.startActivity(intent);
        } catch (Exception e) {
            listener.onError("Could not launch installer.");
        }
    }

    public void clearCache() {
        mainHandler.post(listener::onTaskStarted);
        executorService.execute(() -> {
            deleteDirectory(context.getExternalFilesDir(null));
            mainHandler.post(() -> {
                listener.onTaskFinished();
                listener.onSuccess("Cache cleared.");
            });
        });
    }

    public void deleteGameDataFiles() {
        executorService.execute(() -> {
            File radDir = new File(Environment.getExternalStorageDirectory(), "readyatdawn");
            if (radDir.exists()) deleteDirectory(radDir);
            prefs.edit().remove(PREF_INSTALLATION_DATE).apply();
        });
    }

    private void deleteDirectory(File dir) {
        if (dir != null && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) deleteDirectory(child);
            }
        }
        if (dir != null) dir.delete();
    }

    public void checkForUpdates(String currentVersion) {
        mainHandler.post(listener::onTaskStarted);
        executorService.execute(() -> {
            try {
                URL url = new URL(GITHUB_RELEASES_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "EchoVR-Installer");
                conn.setConnectTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                String remoteVer = json.getString("tag_name");
                String notes = json.optString("body", "");

                String downloadUrl = "";
                JSONArray assets = json.getJSONArray("assets");
                for (int i=0; i<assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                String finalUrl = downloadUrl;
                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    if (isNewVersion(currentVersion, remoteVer)) {
                        listener.onUpdateAvailable(remoteVer, notes, finalUrl);
                    } else {
                        listener.onUpdateNotAvailable();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    listener.onError("Update check failed");
                });
            }
        });
    }

    public void downloadAndInstallUpdate(String url, String version) {
        if (!hasEnoughSpace()) return;
        isTaskCancelled = false;
        mainHandler.post(listener::onTaskStarted);

        currentTask = executorService.submit(() -> {
            File apk = downloadFile(url, "updates", "update.apk", "Update " + version, false);
            mainHandler.post(() -> {
                listener.onTaskFinished();
                if (apk != null && !isTaskCancelled) launchApkInstaller(apk);
                else if (!isTaskCancelled) listener.onError("Update download failed");
            });
        });
    }

    private boolean isNewVersion(String current, String remote) {
        current = current.replaceAll("[^0-9.]", "");
        remote = remote.replaceAll("[^0-9.]", "");
        return !current.equals(remote);
    }

    private boolean hasEnoughSpace() {
        File path = Environment.getExternalStorageDirectory();
        StatFs stat = new StatFs(path.getPath());
        long available = (long) stat.getAvailableBlocks() * stat.getBlockSize();
        if (available < REQUIRED_SPACE_BYTES) {
            mainHandler.post(() -> listener.onError("Not enough space. Need ~2.5GB."));
            return false;
        }
        return true;
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}