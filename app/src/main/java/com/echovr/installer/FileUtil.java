package com.echovr.installer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileUtil {

    private static final String MARKER_FILE_NAME = ".echo_installer_version";

    public static String calculateSHA256(File file) {
        if (file == null || !file.exists()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] byteArray = new byte[8192];
            int bytesCount;

            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }

            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static final String INSTALL_SOURCE_MANIFEST = "manifest";
    public static final String INSTALL_SOURCE_CUSTOM = "custom";

    /**
     * Writes the ".echo_installer_version" marker recording which base APK an install came
     * from, so it can be compared against a manifest's BASE_APK hash without hashing the
     * (possibly license-patched) installed APK directly.
     *
     * @param installSource INSTALL_SOURCE_MANIFEST when baseApkFile was downloaded from the
     *                       update manifest's BASE_APK/mirrors (so base_sha256 is directly
     *                       comparable to the manifest), or INSTALL_SOURCE_CUSTOM for any
     *                       install from a user-supplied URL (New Player, Enhanced/Better
     *                       Graphics) - those may already be license-patched by whoever
     *                       hosted them, so their hash will never match the manifest's
     *                       vanilla BASE_APK and must not be compared against it.
     */
    public static void writeInstallerMarker(File targetDir, File baseApkFile, File installedApkFile, String installerVersion, String installSource) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Could not create target directory: " + targetDir);
        }

        String baseSha256 = calculateSHA256(baseApkFile);
        String installedSha256 = calculateSHA256(installedApkFile);
        if (baseSha256 == null || installedSha256 == null) {
            throw new IOException("Could not hash APK for marker file");
        }
        boolean isPatched = !baseSha256.equalsIgnoreCase(installedSha256);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("version=1\n");
        sb.append("base_apk=").append(baseApkFile.getName()).append("\n");
        sb.append("base_sha256=").append(baseSha256).append("\n");
        sb.append("installed_sha256=").append(installedSha256).append("\n");
        sb.append("patched=").append(isPatched).append("\n");
        sb.append("installed_at=").append(timestamp).append("\n");
        sb.append("installer_version=").append(installerVersion).append("\n");
        sb.append("install_source=").append(installSource).append("\n");

        File markerFile = new File(targetDir, MARKER_FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(markerFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[65536];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        }
    }

    public static void launchApkInstaller(Context context, File apkFile) throws Exception {
        Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apkFile);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        context.startActivity(intent);
    }
}
