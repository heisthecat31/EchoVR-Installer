package com.echovr.installer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class ManifestParser {

    public static UpdateManifest parseFromUrl(String manifestUrl) throws Exception {
        URL url = new URL(manifestUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("Failed to fetch manifest: HTTP " + responseCode);
        }

        try (InputStream is = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return parse(reader);
        }
    }

    public static UpdateManifest parse(BufferedReader reader) throws Exception {
        UpdateManifest manifest = new UpdateManifest();
        String line;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("#")) {
                parseHeader(line, manifest);
            } else {
                parseEntry(line, manifest);
            }
        }

        if (manifest.baseUrl == null || manifest.baseApkName == null || manifest.baseApkHash == null || manifest.targetDir == null) {
            throw new Exception("Manifest is missing required header fields (Base URL, BASE_APK, Target).");
        }

        return manifest;
    }

    private static void parseHeader(String line, UpdateManifest manifest) {
        String content = line.substring(1).trim();
        if (content.toLowerCase().startsWith("base url:")) {
            manifest.baseUrl = content.substring("base url:".length()).trim();
        } else if (content.toLowerCase().startsWith("base_apk:")) {
            String[] parts = content.substring("base_apk:".length()).trim().split("\\s+");
            if (parts.length >= 2) {
                manifest.baseApkName = parts[0];
                manifest.baseApkHash = parts[1];
            }
        } else if (content.toLowerCase().startsWith("target:")) {
            manifest.targetDir = content.substring("target:".length()).trim();
        }
    }

    private static void parseEntry(String line, UpdateManifest manifest) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 3) {
            String op = parts[0];
            String path = parts[1];
            String sha256 = parts[2];
            if ("add".equalsIgnoreCase(op) || "del".equalsIgnoreCase(op)) {
                manifest.entries.add(new UpdateManifest.UpdateEntry(op, path, sha256));
            }
        }
    }
}
