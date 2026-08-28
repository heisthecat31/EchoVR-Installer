package com.echovr.installer;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class UpdateManager {

    private final Context context;
    private final InstallerManager.Listener listener;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private Future<?> currentTask;
    private volatile boolean isCancelled = false;

    public static final String DEFAULT_MANIFEST_URL = "https://files.echovr.de/updates/quest/update.manifest";

    public UpdateManager(Context context, InstallerManager.Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void cancel() {
        isCancelled = true;
        if (currentTask != null) {
            currentTask.cancel(true);
        }
    }

    public void checkAndApplyUpdates(String manifestUrl, boolean silentCheck) {
        if (manifestUrl == null || manifestUrl.isEmpty()) {
            manifestUrl = DEFAULT_MANIFEST_URL;
        }

        final String urlToFetch = manifestUrl;

        isCancelled = false;
        
        if (!silentCheck) {
            mainHandler.post(() -> {
                listener.onTaskStarted();
                listener.onProgress(-1, "Checking for mod updates...");
            });
        }

        currentTask = executorService.submit(() -> {
            boolean[] uiStarted = { !silentCheck };
            try {
                // 1. Fetch and parse manifest
                UpdateManifest manifest = ManifestParser.parseFromUrl(urlToFetch);

                // 2. Read local marker file
                File targetDir = resolveTargetDir(manifest.targetDir);

                File markerFile = new File(targetDir, ".echo_installer_version");
                if (!markerFile.exists()) {
                    if (silentCheck) return;
                    throw new Exception("Installation marker file not found. Please install the game via the app first.");
                }

                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(markerFile)) {
                    props.load(fis);
                }

                String localBaseSha256 = props.getProperty("base_sha256");
                String installSource = props.getProperty("install_source");
                boolean isManifestSourced = FileUtil.INSTALL_SOURCE_MANIFEST.equals(installSource);

                // Only compare against the manifest's BASE_APK hash when we know the install
                // actually came from the manifest (Legacy Echo VR / an offered base-APK
                // update). A "custom" install (New Player, Enhanced/Better Graphics) may
                // already be license-patched by whoever hosted it, so its hash will never
                // match the manifest's vanilla APK - that's not a real mismatch, and
                // offering to "fix" it would overwrite a perfectly working patched install.
                // Older markers with no install_source field are treated the same way
                // (unknown provenance -> don't second-guess it).
                if (isManifestSourced && (localBaseSha256 == null || !localBaseSha256.equalsIgnoreCase(manifest.baseApkHash))) {
                    // Don't apply patches on top of the wrong base APK - offer to fetch the
                    // correct one from the manifest's mirrors instead of dead-ending here.
                    // Surfaced even on a silent (background) check so it doesn't require the
                    // user to remember to tap "Check for Game Updates".
                    final boolean wasUiStarted = uiStarted[0];
                    mainHandler.post(() -> {
                        if (wasUiStarted) listener.onTaskFinished();
                        listener.onBaseApkUpdateAvailable(manifest);
                    });
                    return;
                }

                // 3. Process entries
                int totalEntries = manifest.entries.size();
                int processed = 0;
                boolean updatesApplied = false;

                for (UpdateManifest.UpdateEntry entry : manifest.entries) {
                    if (isCancelled) throw new Exception("Update cancelled");

                    File localFile = new File(targetDir, entry.path);
                    boolean needsAction = false;

                    if ("add".equalsIgnoreCase(entry.op)) {
                        if (!localFile.exists()) {
                            needsAction = true;
                        } else {
                            if (uiStarted[0]) {
                                mainHandler.post(() -> listener.onProgress(-1, "Verifying " + localFile.getName() + "..."));
                            }
                            String localHash = FileUtil.calculateSHA256(localFile);
                            if (!entry.sha256.equalsIgnoreCase(localHash)) {
                                needsAction = true;
                            }
                        }

                        if (needsAction) {
                            if (!uiStarted[0]) {
                                uiStarted[0] = true;
                                mainHandler.post(() -> {
                                    listener.onTaskStarted();
                                    listener.onProgress(-1, "Applying mod updates...");
                                });
                            }
                            updatesApplied = true;
                            int progress = (processed * 100) / totalEntries;
                            mainHandler.post(() -> listener.onProgress(progress, "Downloading " + localFile.getName() + "..."));
                            
                            String fileUrl = manifest.baseUrl;
                            if (!fileUrl.endsWith("/")) fileUrl += "/";
                            fileUrl += entry.path;

                            downloadFile(fileUrl, localFile);
                            
                            // Verify after download
                            String newHash = FileUtil.calculateSHA256(localFile);
                            if (!entry.sha256.equalsIgnoreCase(newHash)) {
                                throw new Exception("Hash mismatch after downloading " + entry.path);
                            }
                        }
                    } else if ("del".equalsIgnoreCase(entry.op)) {
                        if (localFile.exists()) {
                            needsAction = true;
                        }
                        if (needsAction) {
                            if (!uiStarted[0]) {
                                uiStarted[0] = true;
                                mainHandler.post(() -> {
                                    listener.onTaskStarted();
                                    listener.onProgress(-1, "Applying mod updates...");
                                });
                            }
                            updatesApplied = true;
                            localFile.delete();
                        }
                    }

                    processed++;
                }

                final boolean finalUpdatesApplied = updatesApplied;
                mainHandler.post(() -> {
                    if (uiStarted[0]) listener.onTaskFinished();
                    if (!isCancelled && finalUpdatesApplied) {
                        listener.onSuccess("Update applied successfully!");
                    } else if (!isCancelled && !silentCheck && !finalUpdatesApplied) {
                        listener.onSuccess("No updates found.");
                    }
                });

            } catch (Exception e) {
                Log.e("UpdateManager", "Update failed", e);
                mainHandler.post(() -> {
                    if (uiStarted[0]) {
                        listener.onTaskFinished();
                        if (!isCancelled) listener.onError("Update failed: " + e.getMessage());
                    } else if (!silentCheck) {
                        if (!isCancelled) listener.onError("Update failed: " + e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Resolves a manifest "Target:" path (e.g. "/sdcard/Android/media/com.readyatdawn.r15")
     * to a real File. Paths are resolved against Environment.getExternalStorageDirectory()
     * rather than trusting "/sdcard" to exist as a symlink.
     */
    private File resolveTargetDir(String targetDir) throws Exception {
        if (targetDir == null || targetDir.trim().isEmpty()) {
            throw new Exception("Manifest is missing the Target directory.");
        }

        String path = targetDir.trim();
        String sdcardRoot = "/sdcard";
        if (path.equals(sdcardRoot) || path.startsWith(sdcardRoot + "/")) {
            String relative = path.substring(sdcardRoot.length());
            while (relative.startsWith("/")) relative = relative.substring(1);
            return relative.isEmpty()
                    ? Environment.getExternalStorageDirectory()
                    : new File(Environment.getExternalStorageDirectory(), relative);
        }

        File file = new File(path);
        return file.isAbsolute() ? file : new File(Environment.getExternalStorageDirectory(), path);
    }

    /**
     * Downloads the manifest's BASE_APK and prompts to install it. Used when the installed
     * base APK's marker hash no longer matches the manifest, since patches only apply
     * cleanly on top of the base they were built against.
     */
    public void downloadAndInstallBaseApk(UpdateManifest manifest) {
        isCancelled = false;
        mainHandler.post(() -> {
            listener.onTaskStarted();
            listener.onProgress(0, "Downloading " + manifest.baseApkName + "...");
        });

        currentTask = executorService.submit(() -> {
            try {
                File destDir = context.getExternalFilesDir("updates");
                if (destDir == null) {
                    throw new Exception("External storage unavailable");
                }
                if (!destDir.exists()) destDir.mkdirs();
                File apkFile = new File(destDir, manifest.baseApkName);

                downloadBaseApkFromMirrors(manifest, apkFile);

                File targetDir = resolveTargetDir(manifest.targetDir);
                FileUtil.writeInstallerMarker(targetDir, apkFile, apkFile, InstallerManager.INSTALLER_VERSION, FileUtil.INSTALL_SOURCE_MANIFEST);

                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    if (isCancelled) return;
                    try {
                        FileUtil.launchApkInstaller(context, apkFile);
                        listener.onSuccess("Downloaded " + manifest.baseApkName + ". Confirm the install prompt to finish updating.");
                    } catch (Exception e) {
                        listener.onError("Could not launch installer.");
                    }
                });
            } catch (Exception e) {
                Log.e("UpdateManager", "Base APK update failed", e);
                mainHandler.post(() -> {
                    listener.onTaskFinished();
                    if (!isCancelled) listener.onError("Game update failed: " + e.getMessage());
                });
            }
        });
    }

    private void downloadBaseApkFromMirrors(UpdateManifest manifest, File dest) throws Exception {
        Exception lastError = null;
        for (String mirror : UpdateManifest.APK_MIRRORS) {
            if (isCancelled) throw new Exception("Update cancelled");

            String base = mirror.endsWith("/") ? mirror : mirror + "/";
            String url = base + manifest.baseApkName;
            try {
                mainHandler.post(() -> listener.onProgress(-1, "Downloading " + manifest.baseApkName + "..."));
                downloadFile(url, dest);

                String hash = FileUtil.calculateSHA256(dest);
                if (hash == null || !manifest.baseApkHash.equalsIgnoreCase(hash)) {
                    throw new Exception("Hash mismatch after downloading " + manifest.baseApkName);
                }
                return;
            } catch (Exception e) {
                lastError = e;
                if (dest.exists()) dest.delete();
            }
        }
        throw new Exception(lastError != null ? lastError.getMessage() : "All mirrors failed");
    }

    private void downloadFile(String urlStr, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.connect();

        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP " + conn.getResponseCode() + " when downloading " + urlStr);
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) > 0) {
                if (isCancelled) {
                    throw new Exception("Download cancelled");
                }
                out.write(buf, 0, len);
            }
        } finally {
            conn.disconnect();
        }
    }
}
