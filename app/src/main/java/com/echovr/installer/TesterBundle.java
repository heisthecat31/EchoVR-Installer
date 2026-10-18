package com.echovr.installer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BUNDLE.json from a tester bundle (see the bundle's own tools/net/tester_install.py:
 * make_bundle() writes this, install() reads it back). Schema 1: build_id, apk_md5, a
 * files map of relative-path -> sha256 covering every file the installer must verify
 * before touching the device, the "on" switch list, the server, and base_files (the
 * stock-Echo VR files the install must copy in before it can push this build's pack).
 */
public class TesterBundle {

    private static final Pattern PACK_MANIFEST = Pattern.compile("pack/manifests/.+");
    private static final Pattern PACK_PACKAGE = Pattern.compile("pack/packages/.+");

    public String buildId;
    public String server;
    public final Map<String, String> files = new LinkedHashMap<>();
    public final List<String> on = new ArrayList<>();
    public final List<String> baseFiles = new ArrayList<>();
    public String apkRelPath;
    public String packManifestRelPath;
    public String packPackageRelPath;

    public static TesterBundle parse(JSONObject json) throws Exception {
        TesterBundle b = new TesterBundle();
        b.buildId = json.optString("build_id", null);
        b.server = json.optString("server", null);
        if (b.server == null || b.server.trim().isEmpty()) {
            throw new Exception("BUNDLE.json is missing \"server\"");
        }

        JSONObject filesObj = json.optJSONObject("files");
        if (filesObj == null) throw new Exception("BUNDLE.json has no \"files\" map");
        Iterator<String> keys = filesObj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            b.files.put(key, filesObj.getString(key));
            if (key.endsWith(".apk") && !key.contains("/")) {
                b.apkRelPath = key;
            } else if (PACK_MANIFEST.matcher(key).matches()) {
                b.packManifestRelPath = key;
            } else if (PACK_PACKAGE.matcher(key).matches()) {
                b.packPackageRelPath = key;
            }
        }
        if (b.apkRelPath == null || b.packManifestRelPath == null || b.packPackageRelPath == null) {
            throw new Exception("BUNDLE.json's files map is missing the APK, pack manifest, or pack package entry");
        }

        JSONArray onArr = json.optJSONArray("on");
        if (onArr != null) {
            for (int i = 0; i < onArr.length(); i++) b.on.add(onArr.getString(i));
        }

        JSONArray baseArr = json.optJSONArray("base_files");
        if (baseArr == null || baseArr.length() == 0) throw new Exception("BUNDLE.json has no \"base_files\"");
        for (int i = 0; i < baseArr.length(); i++) b.baseFiles.add(baseArr.getString(i));

        return b;
    }

    /** The pack manifest/package file names, with the "pack/" prefix stripped (their name under DATA on-device). */
    public String packManifestName() {
        return packManifestRelPath.substring("pack/manifests/".length());
    }

    public String packPackageName() {
        return packPackageRelPath.substring("pack/packages/".length());
    }
}
