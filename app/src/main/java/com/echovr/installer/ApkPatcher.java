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

    // --- HARDCODED KEY FOR RELIABLE SIGNING ---
    private static final String PRIVATE_KEY_BASE64 = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCYYWC2NgyWazIWzNsZ2AhC4vfhWpdZAWeaPWp+lxftXHEz6Y6dqOJi5my4COXTxzzPqZ1YlLszx1yd3e08OQ/pErKJOcwJR/jBGbNVl/1mHZt29qV7xQy+dzpRZPBXjIP7GYvPMUD3TsKa8QSn3qEr1WDXLl8ykkYEwL8d8/h8tYUqj/x7d1/ipCj9xWGlPm9GmWnqFmEbF+MWxwM2+fqIGftsh8qGlPaZbBjowKj4z1YgYosgfwB/S1MUGHym78L/OMcqKzodshwODfJtAcLfgU4UU5i2UlZumH67/DFA3ez1p2aHZw8+f83x5YK8viRCJF9kagkfmAvlJYznDdqbAgMBAAECggEAKmsFFIP0OhUqEt3A6i9QkWoELdfdhLnW4MFS+V1PHFSc8KIGAM5oArb5MbvMWok+XOJu+h8hA5duKUYDib2qt6tsRrXvne/Kh9qDKQMP15LLWbDsPQmL9CNVeR37p6tmfApO+ITR/GYQ1zfbn21ieUTDWfM/LeE5G46aRRjKpdAmBPXnGgeJefZ9KCOEG/AhWrdP7BUR8DAF9v6wTeW0hat7wm1b++eUvELZ6Bgqx12n2W8sAFfFRoD4awAqYLG0YI9BMmlgGIIGfHtN6JbTnxUVohJEtvL/QFyrQGZ0HHVWAddqk1sXztdVTERz9v05mf6cQ82XVLkN7skW3G7pfQKBgQDFyiU5fa20OtTcyihQCMF1Tv8G/pH/Z45eu/uMcIh4XDXrPGplTB5EQSe90KxoDBW1vBiu8uj7dVGddvFQpIxGwDv7iV0kA62iv8/5Lmuby6t5rMaZ3+bOuULlE0Accg1VbGxuQhCHzr81KRRhlJMlGoIJC0DIq7HLLMd50tjFRwKBgQDFOgLvZAwCM0c5TiHhxoagaiPhgfj5/cTizKiTQn1Yj2T+bmOQzy2V/psWdJKWy6UH7bNQfg0MPaHajCwyfLxkwlX7Uml7ISa4UWjJuasbaSue6StcbjvKAaEvI2OULJR8Enmq7zuc1HCW01Sm4u3wwvLfYgK1vPQkiAaLn7h6DQKBgBH7LKMrX81Qw+VGo5+TDDNj+R9jqVY0zeai5F2CJYX7rBM0rN+EqgO+gKRrAiF7Z8Xb0cql3rRtl/vewlV4gCA2fb2CYWtSwkhXc8rNg47oVzB6mpuGlW8ZvJEizONJIxkvADSN4P7Xtt5YW7f7T91Bqay0zzDvGvzDl2bl2jslAoGAVl9ie65n5+rHDVyfT/4eZVA2aIMAI5M0T1LrnJooxMj/pMF5Tyi8QQ0gpEPnEq0amA9MUTryweKX6Fss2+tuof3No+Pil+7bwyq75mQugDGdzdk1iSQpgP0XtsobyP+BA5kfuXFNvQ/4QsVINFH7fE4UCSomH6shjIIZw7nuE10CgYEAleid+LsRs4YhG9uBa5rVn6DJIqdyyFqx24OjlcNFGfvgakjUWEljlIxpKF4MdE6DdzHYTaBZ3W9nOmj/32h5NEMPgovX8GcLIpbdalMuSihFpVdlDfFL9WsMuIL/7UtB9OdIll/JEgbmZ+iOehIiustkTgLyJP9stRIstpbM6v8=";
    private static final String CERT_BASE64 = "MIIDeDCCAmCgAwIBAgIJAJA5+HY+OtK3MA0GCSqGSIb3DQEBCwUAMGkxEDAOBgNVBAYTB1Vua25vd24xEDAOBgNVBAgTB1Vua25vd24xEDAOBgNVBAcTB1Vua25vd24xEDAOBgNVBAoTB1Vua25vd24xEDAOBgNVBAsTB1Vua25vd24xDTALBgNVBAMTBGVjaG8wIBcNMjYwMjA3MDcwMzQwWhgPMjA1MzA2MjUwNzAzNDBaMGkxEDAOBgNVBAYTB1Vua25vd24xEDAOBgNVBAgTB1Vua25vd24xEDAOBgNVBAcTB1Vua25vd24xEDAOBgNVBAoTB1Vua25vd24xEDAOBgNVBAsTB1Vua25vd24xDTALBgNVBAMTBGVjaG8wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCYYWC2NgyWazIWzNsZ2AhC4vfhWpdZAWeaPWp+lxftXHEz6Y6dqOJi5my4COXTxzzPqZ1YlLszx1yd3e08OQ/pErKJOcwJR/jBGbNVl/1mHZt29qV7xQy+dzpRZPBXjIP7GYvPMUD3TsKa8QSn3qEr1WDXLl8ykkYEwL8d8/h8tYUqj/x7d1/ipCj9xWGlPm9GmWnqFmEbF+MWxwM2+fqIGftsh8qGlPaZbBjowKj4z1YgYosgfwB/S1MUGHym78L/OMcqKzodshwODfJtAcLfgU4UU5i2UlZumH67/DFA3ez1p2aHZw8+f83x5YK8viRCJF9kagkfmAvlJYznDdqbAgMBAAGjITAfMB0GA1UdDgQWBBR/2Gfa2sZO4vVBkWGIvUNjeZQe7jANBgkqhkiG9w0BAQsFAAOCAQEAb1/yW3REt4b1qt8JdgWa1QKcjE0eh9A4VgogJv3AbKv1m/ssh9WgWS8QHVTeEZV/O+zi+Wyh040MoyRgmIjQWMZMIaqPqztqYqbwS6YxHj8ccys+XbVmpFPpAuR+mrvXLWB3wMRV61yWJMlqiAjeT0n/oKtWila/LsvJr2oP3tToyleVNmARFNFsuId/qz8lC7IeC71p+DTYX+VNWvKDONCNEZ3eu1Ky0Sq3KjJIJwxN6j6sVmmGmbBwhJgs4sLyX2C1dnAT5fKwDKRl+/a+jzjV8ZyTAzyKXecyIq1ZUFfoECrlBnXYoQd2jR2kKiiSgCCraYwU3XnMXbO+XzPsug==";
    // ----------------------------------------------

    private static final String PATCH_LIB_URL = "https://github.com/heisthecat31/EchoVR-Installer/releases/download/Installer/libr15.so";
    private static final String PATCH_CONFIG_URL = "https://github.com/heisthecat31/EchoVR-Installer/releases/download/Installer/gamesettings_config.json";

    private static final String TARGET_LIB_PATH = "lib/arm64-v8a/libr15.so";
    private static final String TARGET_CONFIG_PATH = "assets/sourcedb/rad15/json/r14/config/gamesettings_config.json";

    public interface PatcherListener {
        void onProgress(String status);
    }

    public static File patchApk(Context context, File inputApk, boolean applyBetterGraphics, PatcherListener listener) throws Exception {
        File cacheDir = context.getExternalCacheDir();
        File libFile = new File(cacheDir, "libr15.so");
        File configFile = new File(cacheDir, "gamesettings_config.json");
        File tempUnsigned = new File(cacheDir, "temp_modded.apk");
        File tempAligned = new File(cacheDir, "temp_aligned.apk");
        
        String outName = applyBetterGraphics ? "EchoVR_BetterGraphics.apk" : "EchoVR_Patched.apk";
        File finalApk = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), outName);

        try {
            if (applyBetterGraphics) {
                // 1. Download Patches
                listener.onProgress("Downloading Better Graphics files...");
                downloadFile(PATCH_LIB_URL, libFile);
                downloadFile(PATCH_CONFIG_URL, configFile);
            } else {
                // 1. Extract existing libr15.so from the APK
                listener.onProgress("Extracting engine library...");
                extractFileFromZip(inputApk, TARGET_LIB_PATH, libFile);
            }

            // 2. Binary Patch libr15.so to fix the Android/data path
            listener.onProgress("Patching data paths...");
            patchLibr15Paths(libFile);

            // 3. Replace Files in APK
            listener.onProgress("Repacking APK...");
            replaceFilesInZip(inputApk, tempUnsigned, libFile, applyBetterGraphics ? configFile : null);

            // 4. Zipalign
            listener.onProgress("Aligning APK...");
            if (tempAligned.exists()) tempAligned.delete();
            try (RandomAccessFile raf = new RandomAccessFile(tempUnsigned, "r");
                 FileOutputStream fos = new FileOutputStream(tempAligned)) {
                ZipAlign.alignZip(raf, fos);
            }

            // 5. Sign
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

    private static void patchLibr15Paths(File libFile) throws IOException {
        byte[] data = new byte[(int) libFile.length()];
        try (FileInputStream fis = new FileInputStream(libFile)) {
            fis.read(data);
        }

        byte[][] oldStrings = {
                "/sdcard/readyatdawn/files/_data/5932408047/rad15/android/manifests".getBytes(),
                "/sdcard/readyatdawn/files/_data/5932408047/rad15/android/packages".getBytes(),
                "/sdcard/readyatdawn/files".getBytes(),
                "android.permission.RECORD_AUDIO".getBytes(),
                "android.permission.READ_EXTERNAL_STORAGE".getBytes(),
                "android.permission.WRITE_EXTERNAL_STORAGE".getBytes()
        };
        byte[][] newStrings = {
                "/sdcard/Android/media/com.readyatdawn.r15/files/_data/5932408047/rad15/android/manifests".getBytes(),
                "/sdcard/Android/media/com.readyatdawn.r15/files/_data/5932408047/rad15/android/packages".getBytes(),
                "/sdcard/Android/media/com.readyatdawn.r15/files".getBytes(),
                "android.permission.INTERNET".getBytes(),
                "android.permission.INTERNET".getBytes(),
                "android.permission.INTERNET".getBytes()
        };

        for (int s = 0; s < oldStrings.length; s++) {
            byte[] oldStr = oldStrings[s];
            byte[] newStr = newStrings[s];

            int idx = indexOf(data, oldStr, 0);
            while (idx != -1) {
                int nullsFound = 0;
                for (int i = idx + oldStr.length; i < data.length; i++) {
                    if (data[i] == 0) nullsFound++;
                    else break;
                }
                
                int paddingNeeded = newStr.length - oldStr.length;
                if (nullsFound >= paddingNeeded) {
                    int totalSpace = oldStr.length + nullsFound;
                    for (int i = 0; i < totalSpace; i++) {
                        if (i < newStr.length) {
                            data[idx + i] = newStr[i];
                        } else {
                            data[idx + i] = 0;
                        }
                    }
                }
                idx = indexOf(data, oldStr, idx + newStr.length);
            }
        }

        try (FileOutputStream fos = new FileOutputStream(libFile)) {
            fos.write(data);
        }
    }

    private static int indexOf(byte[] data, byte[] pattern, int start) {
        for (int i = start; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private static void extractFileFromZip(File zipFile, String targetPath, File destFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry(targetPath);
            if (entry != null) {
                try (InputStream is = zip.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(destFile)) {
                    copyStream(is, fos);
                }
            } else {
                throw new IOException("File not found in APK: " + targetPath);
            }
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
                if (newConfig != null && name.equals(TARGET_CONFIG_PATH)) {
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
        byte[] keyBytes = android.util.Base64.decode(PRIVATE_KEY_BASE64, android.util.Base64.DEFAULT);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        PrivateKey key = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));

        byte[] certBytes = android.util.Base64.decode(CERT_BASE64, android.util.Base64.DEFAULT);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));

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