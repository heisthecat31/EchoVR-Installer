package com.echovr.installer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.android.apksig.ApkSigner;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.security.auth.x500.X500Principal;

/**
 * On-device port of the bundle's own tools/net/tester_install.py "install" step: unzip +
 * verify a tester bundle (an "online test build" apk + data pack for the side-by-side
 * com.readyatdawn.afk app), sign the apk with a key only this device holds (the bundle's
 * apk isn't signed with anything a normal install will accept), install it, copy stock
 * Echo VR's base data into afk's folder, push the bundle's pack on top, reconcile the
 * mod's on/off switch files, and write the tester's display name + server login config.
 * Driven entirely by BUNDLE.json's contents (sha256 per file, the "on" list, base_files) -
 * nothing here is specific to one build.
 */
public class TesterBundleInstaller {

    public interface Listener {
        void onProgress(String message);
        /** Ask the user before uninstalling an existing afk install signed with a different key (its files will be restored). */
        void onNeedUninstallConfirm(Runnable proceed, Runnable cancel);
        void onSuccess(String message);
        void onError(String message);
    }

    /** Result of {@link #verifyDevCode}: ok, or not ok with error == null for a code that opens nothing. */
    public interface DevCodeCallback {
        void onResult(boolean ok, String error);
    }

    /**
     * The developer's Quest dev folder on release.echovr.de. It can't be listed and only answers exact
     * file URLs, so the 10-character dev code in the path is what gates access - it is typed in by the
     * tester and kept in prefs, never written into this (public) source.
     */
    private static final String DEV_FOLDER_BASE = "https://release.echovr.de/quest/launcher/dev/";
    /** Pointer file in the dev folder naming the current bundle zip, so swapping builds needs no app release. */
    private static final String POINTER_PATH = "files/tester.json";
    private static final Pattern DEV_CODE = Pattern.compile("[a-z0-9]{10}");
    private static final Pattern PLAIN_FILE_NAME = Pattern.compile("[A-Za-z0-9._+-]+");

    private static final String TAG = "TesterBundleInstaller";

    private static final String PACKAGE = "com.readyatdawn.afk";
    private static final String R15_PACKAGE = "com.readyatdawn.r15";
    private static final String DATA_SUBPATH = "files/_data/5932408047/rad15/android";
    private static final String SWITCHES_SUBPATH = "files/afk";
    private static final String USER_TXT_NAME = "user.txt";
    private static final String CONFIG_JSON_NAME = "config.json";
    private static final String BASE_FIX = "Install stock Echo VR from the store, launch it once to its main menu, quit it, "
            + "then run this again (it copies the stock base data into the test app's folder).";

    private static final Pattern SWITCH = Pattern.compile("[A-Za-z0-9_.-]+\\.(on|off|req|poses|done)");
    private static final Pattern ON_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.(on|off)");

    private static final String SIGNING_KEY_ALIAS = "echo_tester_signing_key";
    private static final long REQUIRED_SPACE_BYTES = 1536L * 1024 * 1024;
    private static final int UNINSTALL_GRACE_ATTEMPTS = 15;   // 15 * 2s = 30s - just long enough to tap through the system prompt
    private static final long INSTALL_POLL_INTERVAL_MS = 2000;

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public TesterBundleInstaller(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Reads the display name currently on this device for the test app, if any (for pre-filling the name prompt). */
    public String peekExistingDisplayName() {
        File userTxt = new File(mediaDir(), SWITCHES_SUBPATH + "/" + USER_TXT_NAME);
        return readUser(userTxt).name;
    }

    public void downloadAndInstall(String devCode, String displayName) {
        executor.execute(() -> runInstall(devCode, displayName));
    }

    /** "abcde-fghij" -> "abcdefghij"; null when it can't be a dev code at all. */
    public static String normalizeDevCode(String input) {
        if (input == null) return null;
        String code = input.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "");
        return DEV_CODE.matcher(code).matches() ? code : null;
    }

    /** Checks the code opens a dev folder that has a test bundle in it (fetches its pointer file). */
    public void verifyDevCode(String devCode, DevCodeCallback callback) {
        executor.execute(() -> {
            try {
                fetchPointer(devCode);
                mainHandler.post(() -> callback.onResult(true, null));
            } catch (InvalidCodeException e) {
                mainHandler.post(() -> callback.onResult(false, null));
            } catch (Exception e) {
                Log.e(TAG, "Dev code check failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                mainHandler.post(() -> callback.onResult(false, msg));
            }
        });
    }

    // --- orchestration ---------------------------------------------------------------------------------------

    private void runInstall(String devCode, String displayName) {
        File workDir = new File(context.getExternalFilesDir("testbundle"), "extract");
        try {
            if (!hasEnoughSpace()) {
                fail("Not enough free space. Need about 1.5GB free for the test build and its data pack.");
                return;
            }

            progress("Checking for the latest test build...");
            Pointer pointer = fetchPointer(devCode);
            File zipFile = downloadBundle(devCode, pointer);

            progress("Unzipping bundle...");
            deleteDirectory(workDir);
            unzip(zipFile, workDir);
            zipFile.delete();
            File bundleDir = findBundleRoot(workDir);
            if (bundleDir == null) {
                fail("Could not find BUNDLE.json inside that zip.");
                return;
            }

            progress("Verifying bundle...");
            TesterBundle bundle = checkBundle(bundleDir);

            progress("Checking stock Echo VR data...");
            java.util.Map<String, Long> stockSizes = checkStockBaseFiles(bundle);

            // Read before anything is installed: an uninstall (signature conflict) wipes the test app's media
            // folder, and a same-name reinstall must keep its player id + password (tester_install.py AUD-9 TI-5).
            UserIdentity oldIdentity = readUser(new File(mediaDir(), SWITCHES_SUBPATH + "/" + USER_TXT_NAME));
            String oldPassword = readPassword(new File(mediaDir(), CONFIG_JSON_NAME));

            progress("Preparing signing key...");
            File signedApk = new File(workDir, "signed.apk");
            signApk(new File(bundleDir, bundle.apkRelPath), signedApk);

            installOrUpdateApk(signedApk, workDir, () -> finishAfterInstall(bundleDir, bundle, stockSizes, displayName,
                    oldIdentity, oldPassword, workDir));
        } catch (Exception e) {
            Log.e(TAG, "Tester bundle install failed", e);
            fail(e.getMessage() != null ? e.getMessage() : e.toString());
            deleteDirectory(workDir);
        }
    }

    private void finishAfterInstall(File bundleDir, TesterBundle bundle, java.util.Map<String, Long> stockSizes,
                                     String displayName, UserIdentity oldIdentity, String oldPassword, File workDir) {
        try {
            progress("Copying stock base data...");
            baseCopy(bundle, stockSizes);

            progress("Installing the data pack...");
            pushPackFiles(bundleDir, bundle);

            progress("Setting up mod switches...");
            reconcileSwitches(bundle);

            progress("Saving your identity...");
            String name = writeIdentity(bundle, displayName, oldIdentity, oldPassword);

            succeed("Done. Launch \"Echo VR (afk)\" from Library > Unknown Sources.\nServer " + bundle.server
                    + ", you are " + name + ".");
        } catch (Exception e) {
            Log.e(TAG, "Tester bundle install failed after apk install", e);
            fail(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            deleteDirectory(workDir);
        }
    }

    // --- step 0: download the bundle zip ------------------------------------------------------------------------

    private static class InvalidCodeException extends Exception {
        InvalidCodeException() {
            super("That developer code doesn't open a test build folder.");
        }
    }

    private static class Pointer {
        String bundle;
        String sha256;
        long size;
    }

    private static String devFileUrl(String devCode, String path) {
        return DEV_FOLDER_BASE + devCode + "/" + path;
    }

    private Pointer fetchPointer(String devCode) throws Exception {
        if (devCode == null || !DEV_CODE.matcher(devCode).matches()) throw new InvalidCodeException();
        HttpURLConnection conn = (HttpURLConnection) new URL(devFileUrl(devCode, POINTER_PATH)).openConnection();
        String body;
        try {
            conn.setRequestProperty("User-Agent", "EchoVR-Installer");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 404 || code == 403) throw new InvalidCodeException();
            if (code == 429) throw new Exception("The test build server is busy. Wait a few seconds and try again.");
            if (code != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + code + " checking for the test build");
            body = readLimited(conn.getInputStream(), 65536);
        } finally {
            conn.disconnect();
        }

        JSONObject json = new JSONObject(body);
        Pointer p = new Pointer();
        p.bundle = json.optString("bundle", "");
        p.sha256 = json.optString("sha256", "").toLowerCase(java.util.Locale.ROOT);
        p.size = json.optLong("size", 0);
        if (!PLAIN_FILE_NAME.matcher(p.bundle).matches() || p.bundle.startsWith(".") || !p.sha256.matches("[0-9a-f]{64}")) {
            throw new Exception("The test build folder's tester.json is malformed.");
        }
        return p;
    }

    private File downloadBundle(String devCode, Pointer pointer) throws Exception {
        File dir = context.getExternalFilesDir("testbundle");
        if (dir == null) throw new IOException("External storage not available");
        // Named after the bundle, so a partial download is only ever resumed against the same build -
        // anything left over from an older build is dropped first.
        File dest = new File(dir, pointer.bundle);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) if (f.isFile() && !f.getName().equals(dest.getName())) f.delete();
        }

        downloadWithProgress(devFileUrl(devCode, "files/" + pointer.bundle), dest, "test bundle");

        if (pointer.size > 0 && dest.length() != pointer.size) {
            dest.delete();
            throw new Exception("The test bundle download is incomplete. Try again.");
        }
        progress("Verifying download...");
        String actual = FileUtil.calculateSHA256(dest);
        if (actual == null || !actual.equalsIgnoreCase(pointer.sha256)) {
            dest.delete();
            throw new Exception("The test bundle download is corrupt (checksum mismatch). Try again.");
        }
        return dest;
    }

    private static String readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0, len;
        while (total < maxBytes && (len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            total += len;
        }
        return out.toString("UTF-8");
    }

    private void downloadWithProgress(String urlString, File dest, String label) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        long downloaded = dest.isFile() ? dest.length() : 0;
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (downloaded > 0) connection.setRequestProperty("Range", "bytes=" + downloaded + "-");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode == 416 && downloaded > 0) return;   // already fully downloaded

            boolean resuming = responseCode == HttpURLConnection.HTTP_PARTIAL;
            if (responseCode == 429) {
                throw new Exception("The test build server is busy. Wait a few seconds and try again.");
            }
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw new Exception("HTTP " + responseCode + " downloading the " + label);
            }
            if (!resuming && downloaded > 0) {
                dest.delete();
                downloaded = 0;
            }

            int contentLength = connection.getContentLength();
            long totalSize = contentLength > 0 ? contentLength + downloaded : 0;

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(dest, resuming)) {
                byte[] buffer = new byte[65536];
                long total = downloaded;
                int lastPercent = -1;
                int len;
                while ((len = in.read(buffer)) != -1) {
                    total += len;
                    out.write(buffer, 0, len);
                    if (totalSize > 0) {
                        int percent = (int) ((total * 100) / totalSize);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            long mb = total / (1024 * 1024);
                            progress("Downloading " + label + ": " + percent + "% (" + mb + "MB)");
                        }
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!dest.isFile() || dest.length() == 0) throw new Exception("Downloaded " + label + " is empty");
    }

    // --- step 1: unzip -----------------------------------------------------------------------------------------

    private void unzip(File zipFile, File targetDir) throws Exception {
        if (!targetDir.exists() && !targetDir.mkdirs()) throw new IOException("Could not create " + targetDir);
        try (InputStream rawIn = new BufferedInputStream(new FileInputStream(zipFile))) {
            try (ZipInputStream zis = new ZipInputStream(rawIn)) {
                ZipEntry entry;
                byte[] buffer = new byte[65536];
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(targetDir, entry.getName());
                    if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)) {
                        throw new IOException("Zip entry escapes the target directory: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                        }
                    }
                    zis.closeEntry();
                }
            }
        }
    }

    private File findBundleRoot(File dir) {
        if (new File(dir, "BUNDLE.json").isFile()) return dir;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File found = findBundleRoot(child);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    // --- step 2: verify every bundle file's sha256 against BUNDLE.json -----------------------------------------

    private TesterBundle checkBundle(File bundleDir) throws Exception {
        File manifestFile = new File(bundleDir, "BUNDLE.json");
        JSONObject json = new JSONObject(readFile(manifestFile));
        TesterBundle bundle = TesterBundle.parse(json);

        List<String> bad = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : bundle.files.entrySet()) {
            File f = new File(bundleDir, e.getKey());
            String actual = f.isFile() ? FileUtil.calculateSHA256(f) : null;
            if (actual == null || !actual.equalsIgnoreCase(e.getValue())) bad.add(e.getKey());
        }
        if (!bad.isEmpty()) throw new Exception("Bundle files missing or changed (sha256): " + bad);

        for (String name : bundle.on) {
            if (!ON_NAME.matcher(name).matches()) throw new Exception("Bundle's \"on\" list is not plain .on/.off names: " + name);
        }
        return bundle;
    }

    // --- step 3 prerequisite: stock Echo VR's base data must already be on this device -------------------------

    private java.util.Map<String, Long> checkStockBaseFiles(TesterBundle bundle) throws Exception {
        File r15Data = new File(r15MediaDir(), DATA_SUBPATH);
        java.util.Map<String, Long> sizes = new java.util.LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String rel : bundle.baseFiles) {
            File f = new File(r15Data, rel);
            if (f.isFile()) sizes.put(rel, f.length());
            else missing.add(rel);
        }
        if (!missing.isEmpty()) {
            throw new Exception("PREREQUISITE MISSING: stock Echo VR's data is not on this device (" + missing + " absent). " + BASE_FIX);
        }
        return sizes;
    }

    // --- step 4: sign the bundle's apk with a key this device generated and keeps ------------------------------

    private void signApk(File inputApk, File outputApk) throws Exception {
        KeyStore.PrivateKeyEntry entry = ensureSigningKey();
        PrivateKey privateKey = entry.getPrivateKey();
        List<X509Certificate> certs = Collections.singletonList((X509Certificate) entry.getCertificate());

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder("ECHOVRTESTER", privateKey, certs).build();
        new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(Build.VERSION_CODES.O)
                .build()
                .sign();
    }

    /**
     * A random RSA key + self-signed cert generated inside AndroidKeyStore the first time this runs on a
     * device, then reused for every later bundle - so repeat installs upgrade in place instead of hitting
     * a signature conflict every time. The private key never leaves the keystore and isn't shared with
     * anyone; "random, different for everyone" falls out of that for free, one real key per device.
     */
    private KeyStore.PrivateKeyEntry ensureSigningKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(SIGNING_KEY_ALIAS)) {
            Calendar notBefore = Calendar.getInstance();
            Calendar notAfter = Calendar.getInstance();
            notAfter.add(Calendar.YEAR, 30);

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(SIGNING_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=EchoVRTester"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(notBefore.getTime())
                    .setCertificateNotAfter(notAfter.getTime())
                    .build();
            kpg.initialize(spec);
            kpg.generateKeyPair();
        }
        return (KeyStore.PrivateKeyEntry) ks.getEntry(SIGNING_KEY_ALIAS, null);
    }

    // --- step 2b: install (or in-place update) the signed apk --------------------------------------------------

    /**
     * Installs via PackageInstaller's session API rather than a plain ACTION_INSTALL_PACKAGE intent, because
     * that API reports back a real terminal status (success / signature conflict / other failure) instead of
     * leaving the caller to guess from polling whether the package ever changed. A signature conflict (an old
     * install of this test app signed with a different key - any earlier bundle, including ones installed the
     * PC+adb way) is expected and asked about, same as the bundle's own tester_install.py does; anything else
     * is a real failure.
     */
    private void installOrUpdateApk(File signedApk, File workDir, Runnable onInstalled) throws Exception {
        InstallResult result = installViaSessionAndWait(signedApk);
        if (result.success) {
            onInstalled.run();
            return;
        }
        if (!result.conflictingSignature) {
            throw new Exception(result.message != null ? result.message : "Install failed.");
        }
        mainHandler.post(() -> listener.onNeedUninstallConfirm(
                () -> executor.execute(() -> {
                    try {
                        uninstallAndWait();
                        InstallResult retry = installViaSessionAndWait(signedApk);
                        if (!retry.success) {
                            if (retry.conflictingSignature) {
                                throw new Exception("Still blocked by the old test build's signature after uninstalling. Horizon OS "
                                        + "sometimes doesn't fully remove an app on uninstall (this happens with stock Echo VR too) - "
                                        + "try rebooting the headset, then run this again.");
                            }
                            throw new Exception(retry.message != null ? retry.message : "Install failed after uninstalling the old test build.");
                        }
                        onInstalled.run();
                    } catch (Exception e) {
                        Log.e(TAG, "Uninstall/reinstall failed", e);
                        fail(e.getMessage() != null ? e.getMessage() : e.toString());
                        deleteDirectory(workDir);
                    }
                }),
                () -> {
                    fail("Stopped: nothing changed on this device.");
                    deleteDirectory(workDir);
                }));
    }

    private static class InstallResult {
        boolean success;
        boolean conflictingSignature;
        String message;
    }

    private InstallResult installViaSessionAndWait(File signedApk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(PACKAGE);
        int sessionId = installer.createSession(params);

        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (OutputStream out = session.openWrite("base.apk", 0, signedApk.length());
                 FileInputStream in = new FileInputStream(signedApk)) {
                byte[] buffer = new byte[65536];
                int len;
                while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
                session.fsync(out);
            }

            CountDownLatch latch = new CountDownLatch(1);
            InstallResult result = new InstallResult();
            String action = "com.echovr.installer.TESTER_INSTALL_RESULT." + sessionId;

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                context.startActivity(confirm);
                            } catch (Exception e) {
                                Log.e(TAG, "Could not launch install confirmation", e);
                            }
                        }
                        return;   // not a terminal status - wait for the real one after the user responds
                    }
                    result.success = status == PackageInstaller.STATUS_SUCCESS;
                    result.conflictingSignature = status == PackageInstaller.STATUS_FAILURE_CONFLICT;
                    result.message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    latch.countDown();
                }
            };

            ContextCompat.registerReceiver(context, receiver, new IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED);
            try {
                Intent target = new Intent(action).setPackage(context.getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, target,
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pending.getIntentSender());

                progress("Confirm the install prompt on your headset...");
                if (!latch.await(5, TimeUnit.MINUTES)) {
                    throw new Exception("Timed out waiting for the install prompt to be confirmed. Run this again.");
                }
            } finally {
                context.unregisterReceiver(receiver);
            }
            return result;
        }
    }

    private PackageInfo getInstalledPackageInfo(String pkg) {
        try {
            return context.getPackageManager().getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Launches the uninstall prompt and gives the user a short window to confirm it. Doesn't wait for (or
     * fail on) getPackageInfo() actually reporting the package gone: Horizon OS doesn't reliably clear that
     * even after a real uninstall completes (seen on stock Echo VR too, not just this test app), so that
     * signal can't be trusted here. Whether the uninstall actually took effect is judged by whether the
     * reinstall right after this succeeds, not by anything checked in this method.
     */
    private void uninstallAndWait() throws Exception {
        mainHandler.post(() -> {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + PACKAGE));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        });
        progress("Confirm the uninstall prompt on your headset...");
        for (int i = 0; i < UNINSTALL_GRACE_ATTEMPTS; i++) {
            Thread.sleep(INSTALL_POLL_INTERVAL_MS);
            if (getInstalledPackageInfo(PACKAGE) == null) return;   // confirmed gone - no need to wait out the rest of the grace period
        }
    }

    // --- step 3: copy stock Echo VR's base files into afk's folder (only if missing/wrong size) -----------------

    private void baseCopy(TesterBundle bundle, java.util.Map<String, Long> stockSizes) throws Exception {
        File r15Data = new File(r15MediaDir(), DATA_SUBPATH);
        File data = new File(mediaDir(), DATA_SUBPATH);
        String packManifestRel = "manifests/" + bundle.packManifestName();

        for (String rel : bundle.baseFiles) {
            File dst = new File(data, rel);
            if (rel.equals(packManifestRel) && dst.isFile()) continue;   // the pack push below replaces + sha-checks it anyway
            Long stockSize = stockSizes.get(rel);
            if (dst.isFile() && stockSize != null && dst.length() == stockSize) continue;   // already a correct stock copy

            File src = new File(r15Data, rel);
            FileUtil.copyFile(src, dst);
            if (stockSize != null && dst.length() != stockSize) {
                throw new Exception("Base copy " + rel + ": size differs from stock Echo VR's. Check free space and try again.");
            }
        }
    }

    // --- step 5: push this bundle's pack on top, sha256-checked -------------------------------------------------

    private void pushPackFiles(File bundleDir, TesterBundle bundle) throws Exception {
        File data = new File(mediaDir(), DATA_SUBPATH);

        File manifestSrc = new File(bundleDir, bundle.packManifestRelPath);
        File manifestDst = new File(data, "manifests/" + bundle.packManifestName());
        FileUtil.copyFile(manifestSrc, manifestDst);
        checkCopiedSha256(manifestDst, bundle.files.get(bundle.packManifestRelPath), bundle.packManifestRelPath);

        File packageSrc = new File(bundleDir, bundle.packPackageRelPath);
        File packageDst = new File(data, "packages/" + bundle.packPackageName());
        FileUtil.copyFile(packageSrc, packageDst);
        checkCopiedSha256(packageDst, bundle.files.get(bundle.packPackageRelPath), bundle.packPackageRelPath);
    }

    private void checkCopiedSha256(File dst, String expected, String label) throws Exception {
        String actual = FileUtil.calculateSHA256(dst);
        if (actual == null || !actual.equalsIgnoreCase(expected)) {
            throw new Exception("Pack file " + label + ": sha256 differs after copying. Check free space and try again.");
        }
    }

    // --- step 6: reconcile on/off/req/poses/done switch files against the bundle's "on" list --------------------

    private void reconcileSwitches(TesterBundle bundle) throws Exception {
        File afkDir = new File(mediaDir(), SWITCHES_SUBPATH);
        if (!afkDir.exists() && !afkDir.mkdirs()) throw new IOException("Could not create " + afkDir);

        Set<String> on = new HashSet<>(bundle.on);
        File[] existing = afkDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (SWITCH.matcher(f.getName()).matches() && !on.contains(f.getName())) f.delete();
            }
        }
        for (String name : bundle.on) {
            File f = new File(afkDir, name);
            if (!f.exists()) {
                try (FileOutputStream fos = new FileOutputStream(f)) { /* touch */ }
            }
        }

        Set<String> finalSwitches = new HashSet<>();
        File[] after = afkDir.listFiles();
        if (after != null) {
            for (File f : after) if (SWITCH.matcher(f.getName()).matches()) finalSwitches.add(f.getName());
        }
        if (!finalSwitches.equals(on)) {
            throw new Exception("files/afk switches " + finalSwitches + " != the bundle's " + on);
        }
    }

    // --- step 7: display name / player id / password + server login config ---------------------------------------

    private static final int NAME_MAX = 20;

    private String writeIdentity(TesterBundle bundle, String requestedName, UserIdentity old, String oldPassword) throws Exception {
        File afkDir = new File(mediaDir(), SWITCHES_SUBPATH);
        File userTxt = new File(afkDir, USER_TXT_NAME);
        File configJson = new File(mediaDir(), CONFIG_JSON_NAME);

        String name = cleanName(requestedName);
        if (name.isEmpty()) throw new Exception("The display name has no printable characters");

        boolean same = old.id != null && name.equals(old.name);
        long id = same ? old.id : newId();
        String password = same ? oldPassword : null;
        if (password == null) password = newPassword();

        writeFile(userTxt, "id=" + id + "\nname=" + name + "\n");
        writeFile(configJson, buildConfigJson(bundle.server, password, name));

        UserIdentity verify = readUser(userTxt);
        if (verify.id == null || verify.id != id || !name.equals(verify.name) || !password.equals(readPassword(configJson))) {
            throw new Exception("user.txt or config.json doesn't hold what was just written - try again");
        }
        return name + " (OVR-ORG-" + id + ")";
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= ' ' && c < 0x7f) sb.append(c);
        }
        String trimmed = sb.toString().trim();
        return trimmed.length() > NAME_MAX ? trimmed.substring(0, NAME_MAX) : trimmed;
    }

    private static class UserIdentity {
        Long id;
        String name;
    }

    private UserIdentity readUser(File userTxt) {
        UserIdentity u = new UserIdentity();
        if (!userTxt.isFile()) return u;
        try {
            for (String line : readFile(userTxt).split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ("id".equals(key) && value.matches("\\d+")) {
                    try {
                        u.id = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("name".equals(key)) {
                    u.name = value;
                }
            }
        } catch (IOException ignored) {
        }
        return u;
    }

    private String readPassword(File configJson) {
        if (!configJson.isFile()) return null;
        try {
            JSONObject obj = new JSONObject(readFile(configJson));
            String url = obj.optString("loginservice_host", "");
            int q = url.indexOf('?');
            if (q < 0) return null;
            for (String pair : url.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                if ("auth".equals(pair.substring(0, eq))) {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String buildConfigJson(String server, String password, String name) throws Exception {
        String q = "auth=" + URLEncoder.encode(password, "UTF-8")
                + "&displayname=" + URLEncoder.encode(name, "UTF-8")
                + "&ign=" + URLEncoder.encode(name, "UTF-8");
        JSONObject cfg = new JSONObject();
        cfg.put("apiservice_host", "http://" + server + "/api");
        cfg.put("configservice_host", "ws://" + server + "/config");
        cfg.put("loginservice_host", "ws://" + server + "/login?" + q);
        cfg.put("matchingservice_host", "ws://" + server + "/matching");
        cfg.put("transactionservice_host", "ws://" + server + "/transaction");
        cfg.put("publisher_lock", "echovrce");
        return cfg.toString(4) + "\n";
    }

    private long newId() {
        long id = new SecureRandom().nextLong() & Long.MAX_VALUE;
        return id == 0 ? 1 : id;
    }

    private String newPassword() {
        byte[] buf = new byte[12];
        new SecureRandom().nextBytes(buf);
        return Base64.encodeToString(buf, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // --- small helpers -------------------------------------------------------------------------------------------

    private File mediaDir() {
        return new File(Environment.getExternalStorageDirectory(), "Android/media/" + PACKAGE);
    }

    private File r15MediaDir() {
        return new File(Environment.getExternalStorageDirectory(), "Android/media/" + R15_PACKAGE);
    }

    private static String readFile(File f) throws IOException {
        byte[] bytes = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0, n;
            while (off < bytes.length && (n = fis.read(bytes, off, bytes.length - off)) >= 0) off += n;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeFile(File f, String content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean hasEnoughSpace() {
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long available = (long) stat.getAvailableBlocks() * stat.getBlockSize();
        return available >= REQUIRED_SPACE_BYTES;
    }

    private void deleteDirectory(File dir) {
        if (dir == null) return;
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) deleteDirectory(child);
        dir.delete();
    }

    private void progress(String message) {
        mainHandler.post(() -> listener.onProgress(message));
    }

    private void fail(String message) {
        mainHandler.post(() -> listener.onError(message));
    }

    private void succeed(String message) {
        mainHandler.post(() -> listener.onSuccess(message));
    }

    /**
     * Graceful, not shutdownNow(): stops accepting new work, but lets an in-flight install finish
     * running rather than interrupting it. MainActivity calls this from onDestroy(), and on Quest a
     * panel app's Activity can be destroyed just from the system install/uninstall confirmation
     * taking focus - forcibly killing the flow at exactly that moment would mean it could never
     * complete. Everything it touches (context, the install/uninstall broadcast receiver) is tied to
     * the application context already, not this Activity, so it keeps working fine without one; the
     * Listener callbacks are guarded against a destroyed Activity on the other end.
     */
    public void shutdown() {
        executor.shutdown();
    }
}
