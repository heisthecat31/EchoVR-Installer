package com.echovr.installer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.android.apksig.ApkSigner;
import com.iyxan23.zipalignjava.ZipAlign;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ApkPatcher {

    // --- CONFIGURE YOUR KEY HERE ---
    private static final String KEYSTORE_NAME = "key.p12"; // Must be in assets folder
    private static final String KEY_ALIAS = "myalias";
    private static final String KEY_PASS = "changeme";
    private static final String STORE_PASS = "changeme";
    // -------------------------------

    private static final String PATCH_LIB_URL = "https://github.com/heisthecat31/EchoVR-Installer/releases/download/Installer/libr15.so";
    private static final String PATCH_CONFIG_URL = "https://github.com/heisthecat31/EchoVR-Installer/releases/download/Installer/gamesettings_config.json";

    private static final String TARGET_LIB_PATH = "lib/arm64-v8a/libr15.so";
    private static final String TARGET_CONFIG_PATH = "assets/sourcedb/rad15/json/r14/config/gamesettings_config.json";

    public interface PatcherListener {
        void onProgress(String status);
    }

    public static File patchApk(Context context, File inputApk, PatcherListener listener) throws Exception {
        File cacheDir = context.getExternalCacheDir();
        File libFile = new File(cacheDir, "libr15.so");
        File configFile = new File(cacheDir, "gamesettings_config.json");
        File tempUnsigned = new File(cacheDir, "temp_modded.apk");
        File tempAligned = new File(cacheDir, "temp_aligned.apk");
        
        File finalApk = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "EchoVR_BetterGraphics.apk");

        try {
            // 1. Download Patches
            listener.onProgress("Downloading patch files...");
            downloadFile(PATCH_LIB_URL, libFile);
            downloadFile(PATCH_CONFIG_URL, configFile);

            // 2. Replace Files in APK
            listener.onProgress("Patching APK...");
            replaceFilesInZip(inputApk, tempUnsigned, libFile, configFile);

            // 3. Zipalign
            listener.onProgress("Aligning APK...");
            if (tempAligned.exists()) tempAligned.delete();
            try (RandomAccessFile raf = new RandomAccessFile(tempUnsigned, "r");
                 FileOutputStream fos = new FileOutputStream(tempAligned)) {
                ZipAlign.alignZip(raf, fos);
            }

            // 4. Sign
            listener.onProgress("Signing APK...");
            signApk(context, tempAligned, finalApk);

            listener.onProgress("Done! Saved to Downloads.");
            return finalApk;

        } finally {
            // Cleanup
            if (libFile.exists()) libFile.delete();
            if (configFile.exists()) configFile.delete();
            if (tempUnsigned.exists()) tempUnsigned.delete();
            if (tempAligned.exists()) tempAligned.delete();
        }
    }

    private static void replaceFilesInZip(File srcZip, File destZip, File newLib, File newConfig) throws IOException {
        try (ZipFile zipFile = new ZipFile(srcZip);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destZip)))) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals(TARGET_LIB_PATH)) {
                    writeEntry(zos, name, newLib);
                    continue;
                }
                if (name.equals(TARGET_CONFIG_PATH)) {
                    writeEntry(zos, name, newConfig);
                    continue;
                }

                zos.putNextEntry(new ZipEntry(name));
                try (InputStream is = zipFile.getInputStream(entry)) {
                    copyStream(is, zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, File file) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        try (FileInputStream fis = new FileInputStream(file)) {
            copyStream(fis, zos);
        }
        zos.closeEntry();
    }

    private static void signApk(Context context, File input, File output) throws Exception {
        // Change "JKS" to "PKCS12"
        KeyStore ks = KeyStore.getInstance("PKCS12"); 
        
        try (InputStream is = context.getAssets().open(KEYSTORE_NAME)) {
            ks.load(is, STORE_PASS.toCharArray());
        }

        PrivateKey key = (PrivateKey) ks.getKey(KEY_ALIAS, KEY_PASS.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(KEY_ALIAS);

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("EchoPatcher", key, Collections.singletonList(cert)).build();

        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign();
    }

    private static void downloadFile(String urlStr, File dest) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.connect();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[32768];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
    }
}
