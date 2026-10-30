package com.echovr.installer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

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

    private static final String GITHUB_RELEASES_URL = "https://api.github.com/repos/heisthecat31/EchoVR-Installer/releases/latest";
    private static final String TARGET_DIR = "Android/media/com.readyatdawn.r15/files";
    private static final String DATA_FOLDER = "_data";
    private static final String PREFS_NAME = "EchoVRInstallerPrefs";
    private static final String PREF_INSTALLATION_DATE = "installation_date";
    private static final String PREF_INSTALLED_GAME_DATA_SHA256 = "installed_game_data_sha256";
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

    public interface NevrUpdateCallback {
        void onNevrUpdateAvailable(NevrRuntime.Release release);
    }

    /**
     * Installs (or updates to) the latest NEVR runtime Echo VR APK from the release manifest.
     * Every release is signed with the same key, so it installs over an earlier one in place.
     */
    public void installNevrRuntime() {
        if (!hasEnoughSpace()) return;
        isTaskCancelled = false;
        mainHandler.post(() -> {
            listener.onTaskStarted();
            listener.onProgress(-1, "Checking for the latest Echo VR...");
        });

        currentTask = executorService.submit(() -> {
            try {
                NevrRuntime.Release release = NevrRuntime.fetchLatest(context);

                // A different Echo VR (e.g. a Discord-patched one) is signed with another key, and
                // Android refuses to install over it - say so instead of a vague "App not installed".
                String installedSigner = NevrRuntime.installedSignerSha256(context);
                if (installedSigner != null && !installedSigner.equals(release.apkSignerSha256)) {
                    if (NevrRuntime.installedVersionCode(context) < 0) {
                        // Removed for this account, but still installed for another account on the headset.
                        throw new Exception("A different Echo VR is still installed for another account on this headset, "
                                + "so this one can't be installed. Switch to the headset's main account and use "
                                + "Uninstall Echo VR there (it removes it for every account), then install again.");
                    }
                    throw new Exception("The Echo VR already on this headset is a different build and can't be updated in place. "
                            + "Uninstall it first, then install again.");
                }

                deleteOtherDownloads("apk", "nevr-runtime-", release.apk.name);
                File apk = downloadArtifact(release.apk, "apk", "Echo VR " + release.version);

                String[] identity = NevrRuntime.archiveIdentity(context, apk.getPath());
                if (!"com.readyatdawn.r15".equals(identity[0])
                        || (identity[1] != null && !identity[1].equals(release.apkSignerSha256))) {
                    apk.delete();
                    throw new Exception("The downloaded APK isn't the expected Echo VR build.");
                }

                boolean dataStale = gameDataStale(release.gameData);
                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    if (isTaskCancelled) return;
                    launchApkInstaller(apk);
                    if (dataStale) installGameData();
                });
            } catch (Exception e) {
                Log.e("InstallerManager", "NEVR runtime install failed", e);
                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    if (!isTaskCancelled) listener.onError(e.getMessage());
                });
            }
        });
    }

    /**
     * Calls back (on the main thread) only when the installed Echo VR is a NEVR runtime build
     * older than the release channel's. Stays quiet on any failure.
     */
    public void checkNevrRuntimeUpdate(NevrUpdateCallback callback) {
        checkNevrRuntimeUpdate(callback, false);
    }

    /** @param manual when true (the "Check Echo Updates" button), shows progress and reports "no update" and errors too. */
    public void checkNevrRuntimeUpdate(NevrUpdateCallback callback, boolean manual) {
        if (manual) mainHandler.post(() -> {
            listener.onTaskStarted();
            listener.onProgress(-1, "Checking for Echo VR updates...");
        });
        executorService.execute(() -> {
            try {
                NevrRuntime.Release release = NevrRuntime.fetchLatest(context);
                long installed = NevrRuntime.installedVersionCode(context);
                boolean available = installed >= 0 && installed < release.apkVersionCode
                        && release.apkSignerSha256.equals(NevrRuntime.installedSignerSha256(context));
                mainHandler.post(() -> {
                    if (manual) listener.onTaskFinished();
                    if (available) callback.onNevrUpdateAvailable(release);
                    else if (manual) listener.onUpdateNotAvailable();
                });
            } catch (Exception e) {
                Log.w("InstallerManager", "NEVR runtime update check failed: " + e.getMessage());
                if (manual) mainHandler.post(() -> {
                    listener.onTaskFinished();
                    listener.onError("Update check failed: " + e.getMessage());
                });
            }
        });
    }

    /** Downloads a manifest artifact (resuming if possible) and checks its size and sha256. */
    private File downloadArtifact(NevrRuntime.Artifact artifact, String dirType, String label) throws Exception {
        File file = downloadFile(artifact.url, dirType, artifact.name, label, false);
        if (isTaskCancelled) throw new Exception("Cancelled.");
        if (file == null) throw new Exception(label + " download failed. Check your connection and try again.");

        mainHandler.post(() -> listener.onProgress(-1, "Verifying " + label + "..."));
        String hash = file.length() == artifact.size ? FileUtil.calculateSHA256(file) : null;
        if (!artifact.sha256.equalsIgnoreCase(hash)) {
            file.delete();
            throw new Exception(label + " download was corrupted. Try again.");
        }
        return file;
    }

    /** Removes older downloads of the same artifact family so each release doesn't leave ~175 MB behind. */
    private void deleteOtherDownloads(String dirType, String prefix, String keepName) {
        File dir = context.getExternalFilesDir(dirType);
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(prefix) && !f.getName().equals(keepName)) f.delete();
        }
    }

    /**
     * True when the manifest's game data differs from what was last extracted. With nothing recorded
     * (installed before this was tracked) it only counts as stale if the data is missing, so an
     * existing install isn't forced into a re-download.
     */
    private boolean gameDataStale(NevrRuntime.Artifact data) {
        if (data == null) return false;
        String installed = prefs.getString(PREF_INSTALLED_GAME_DATA_SHA256, null);
        if (installed == null) return !verifyDataInstallation();
        return !installed.equalsIgnoreCase(data.sha256);
    }

    public void installGameData() {
        if (!hasEnoughSpace()) return;
        isTaskCancelled = false;
        mainHandler.post(listener::onTaskStarted);

        currentTask = executorService.submit(() -> {
            // The NEVR release's game-data zip, checked against the manifest's sha256. No other source.
            File zipFile;
            String dataSha256;
            try {
                NevrRuntime.Artifact data = NevrRuntime.fetchLatest(context).gameData;
                if (data == null) throw new Exception("The current Echo VR release has no game data.");
                deleteOtherDownloads("downloads", "echovr-data-", data.name);
                deleteStaleDownload("downloads", "game_data.zip");
                zipFile = downloadArtifact(data, "downloads", "Game Data");
                dataSha256 = data.sha256;
            } catch (Exception e) {
                Log.e("InstallerManager", "Game data download failed", e);
                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    if (!isTaskCancelled) listener.onError(e.getMessage());
                });
                return;
            }

            mainHandler.post(() -> listener.onProgress(-1, "Extracting data..."));
            boolean success = extractZipFile(zipFile);
            final String extractedSha256 = dataSha256;

            mainHandler.post(() -> {
                listener.onTaskFinished();
                if (isTaskCancelled) return;
                if (success) {
                    if (verifyDataInstallation()) {
                        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
                        prefs.edit()
                                .putString(PREF_INSTALLATION_DATE, date)
                                .putString(PREF_INSTALLED_GAME_DATA_SHA256, extractedSha256)
                                .apply();
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

    /** Clears a partial download before switching to a different mirror/host for it. */
    private void deleteStaleDownload(String dirType, String fileName) {
        File dir = dirType.equals("cache") ? context.getExternalCacheDir() : context.getExternalFilesDir(dirType);
        if (dir == null) return;
        File file = new File(dir, fileName);
        if (file.exists()) file.delete();
    }

    private File downloadFile(String urlString, String dirType, String fileName, String logName, boolean isBackup) {
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;

        try {
            File dir = dirType.equals("cache") ? context.getExternalCacheDir() : context.getExternalFilesDir(dirType);
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

            if (!isResuming && downloaded > 0) {
                file.delete();
                downloaded = 0;
            }

            int contentLength = connection.getContentLength();
            long totalFileSize = (contentLength > 0) ? (contentLength + downloaded) : 0;

            input = connection.getInputStream();
            output = new FileOutputStream(file, isResuming);

            byte[] buffer = new byte[32768];
            long totalDownloaded = downloaded;
            int bytesRead;
            int lastReportedProgress = -1;

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
                    // Posting to the main thread on every 32KB chunk (thousands of times for
                    // a large APK/zip) floods the UI thread with Runnable/Message allocation
                    // and string concatenation, which competes for CPU with the download
                    // thread itself - only post when the displayed percentage actually moves.
                    if (progress != lastReportedProgress) {
                        lastReportedProgress = progress;
                        long mb = totalDownloaded / (1024 * 1024);
                        long totalMb = totalFileSize / (1024 * 1024);
                        mainHandler.post(() -> listener.onProgress(progress, "Downloading " + prefix + "\n" + mb + " MB of " + totalMb + " MB"));
                    }
                }
            }

            output.flush();
            return (file.exists() && file.length() > 0) ? file : null;

        } catch (Exception e) {
            Log.e("InstallerManager", "Download error: " + e.getMessage());
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
            String targetRoot = targetDir.getCanonicalPath() + File.separator;

            while ((entry = zis.getNextEntry()) != null) {
                if (isTaskCancelled) return false;

                File outputFile = new File(targetDir, entry.getName());
                if (!outputFile.getCanonicalPath().startsWith(targetRoot)) {
                    Log.e("InstallerManager", "Zip entry outside target: " + entry.getName());
                    return false;
                }
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
            FileUtil.launchApkInstaller(context, apkFile);
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
        checkForUpdates(currentVersion, false);
    }

    /**
     * @param silent when true (e.g. an automatic startup check), stays quiet unless an
     *               update is actually found - no progress UI, no "no updates" toast/error.
     */
    public void checkForUpdates(String currentVersion, boolean silent) {
        if (!silent) mainHandler.post(listener::onTaskStarted);
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
                String remoteVerName = json.getString("name");
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
                    if (!silent) listener.onTaskFinished();
                    if (isNewVersion(currentVersion, remoteVerName)) {
                        listener.onUpdateAvailable(remoteVerName, notes, finalUrl);
                    } else if (!silent) {
                        listener.onUpdateNotAvailable();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!silent) {
                        listener.onTaskFinished();
                        listener.onError("Update check failed");
                    }
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
        String currentVersion = current.replaceAll("[^0-9.]", "");
        String remoteVersion = remote.replaceAll("[^0-9.]", "");

        Log.d("InstallerManager", "Current Version: " + currentVersion);
        Log.d("InstallerManager", "Remote Version: " + remoteVersion);

        if (currentVersion.isEmpty() || remoteVersion.isEmpty()) {
            return false;
        }

        String[] currentParts = currentVersion.split("\\.");
        String[] remoteParts = remoteVersion.split("\\.");

        int length = Math.max(currentParts.length, remoteParts.length);
        for (int i = 0; i < length; i++) {
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            int remotePart = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;

            if (remotePart > currentPart) {
                return true;
            }
            if (remotePart < currentPart) {
                return false;
            }
        }
        return false;
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