package com.echovr.installer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The NEVR runtime release manifest (evr.echo.taxi/nevr-runtime/manifest.json): one Echo VR
 * APK for everyone, always signed with the same key so each release installs over the last,
 * plus the game-data zip as a separate artifact. Follows the "stable" channel, or "beta"
 * while there is no stable release yet.
 */
public class NevrRuntime {

    public static final String BASE_URL = "https://evr.echo.taxi/nevr-runtime/";
    private static final String MANIFEST_URL = BASE_URL + "manifest.json";
    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";
    private static final String PREFS_NAME = "EchoVRInstallerPrefs";
    private static final String PREF_CHANNEL = "nevr_channel";
    private static final String ECHO_VR_PACKAGE = "com.readyatdawn.r15";

    /** The channel picked in Settings; stable unless the user switched to beta. */
    public static String getChannel(Context context) {
        String c = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_CHANNEL, CHANNEL_STABLE);
        return CHANNEL_BETA.equals(c) ? CHANNEL_BETA : CHANNEL_STABLE;
    }

    public static void setChannel(Context context, String channel) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_CHANNEL, CHANNEL_BETA.equals(channel) ? CHANNEL_BETA : CHANNEL_STABLE).apply();
    }

    // Artifact paths are joined onto BASE_URL, so only plain relative paths are accepted.
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._+-]+(/[A-Za-z0-9._+-]+)*");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._+-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public static class Artifact {
        public String name;
        public String url;
        public String sha256;
        public long size;
    }

    public static class Release {
        public String channel;
        public String version;
        public Artifact apk;
        public long apkVersionCode;
        public String apkSignerSha256;
        /** null if the release has no game-data artifact. */
        public Artifact gameData;
    }

    /** Blocking - call off the main thread. Uses the Settings channel, falling back to the other one if it has no release. */
    public static Release fetchLatest(Context context) throws Exception {
        String preferred = getChannel(context);
        String fallback = CHANNEL_BETA.equals(preferred) ? CHANNEL_STABLE : CHANNEL_BETA;
        return fetchLatest(new String[] { preferred, fallback });
    }

    private static Release fetchLatest(String[] channelOrder) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        conn.setRequestProperty("User-Agent", "EchoVR-Installer");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        StringBuilder body = new StringBuilder();
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new Exception("Release manifest returned HTTP " + conn.getResponseCode());
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
        } finally {
            conn.disconnect();
        }
        return parse(new JSONObject(body.toString()), channelOrder);
    }

    static Release parse(JSONObject manifest, String[] channelOrder) throws Exception {
        if (manifest.optInt("schema_version", 0) != 1) {
            throw new Exception("Unsupported release manifest version - update the installer.");
        }
        JSONObject channels = manifest.getJSONObject("channels");
        String channel = null;
        for (String c : channelOrder) {
            if (channels.optString(c, "").length() > 0) {
                channel = c;
                break;
            }
        }
        if (channel == null) throw new Exception("The release manifest lists no stable or beta release.");

        String version = channels.getString(channel);
        JSONObject release = manifest.getJSONObject("releases").getJSONObject(version);

        Release result = new Release();
        result.channel = channel;
        result.version = version;

        JSONArray artifacts = release.getJSONArray("artifacts");
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject a = artifacts.getJSONObject(i);
            String kind = a.optString("kind");
            if ("quest-apk".equals(kind) && result.apk == null) {
                JSONObject android = a.getJSONObject("android");
                if (!ECHO_VR_PACKAGE.equals(android.optString("package"))) {
                    throw new Exception("The release APK isn't Echo VR (" + android.optString("package") + ").");
                }
                result.apk = parseArtifact(a);
                result.apkVersionCode = android.getLong("version_code");
                result.apkSignerSha256 = android.getString("signer_cert_sha256").toLowerCase(Locale.ROOT);
                if (!SHA256.matcher(result.apkSignerSha256).matches()) throw new Exception("Bad signer in release manifest.");
            } else if ("game-data".equals(kind) && result.gameData == null) {
                result.gameData = parseArtifact(a);
            }
        }
        if (result.apk == null) throw new Exception("Release " + version + " has no Quest APK.");
        return result;
    }

    private static Artifact parseArtifact(JSONObject a) throws Exception {
        Artifact artifact = new Artifact();
        artifact.name = a.getString("name");
        String path = a.getString("path");
        artifact.sha256 = a.getString("sha256").toLowerCase(Locale.ROOT);
        artifact.size = a.getLong("size");
        if (!SAFE_NAME.matcher(artifact.name).matches() || artifact.name.startsWith(".")
                || !SAFE_PATH.matcher(path).matches() || path.contains("..")
                || !SHA256.matcher(artifact.sha256).matches() || artifact.size <= 0) {
            throw new Exception("Bad artifact entry in release manifest: " + artifact.name);
        }
        artifact.url = BASE_URL + path;
        return artifact;
    }

    // --- installed / downloaded APK identity ---

    /** Version code of the installed Echo VR, or -1 if it isn't installed. */
    public static long installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(ECHO_VR_PACKAGE, 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /**
     * SHA-256 of the Echo VR signing cert on this headset (lowercase hex), or null if it isn't on the
     * headset / unknown. Also finds a copy that's only installed for another account on the headset,
     * which blocks installing a differently signed build just the same.
     */
    public static String installedSignerSha256(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(ECHO_VR_PACKAGE,
                        PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                return info.signingInfo == null ? null : certSha256(info.signingInfo.getApkContentsSigners());
            }
            PackageInfo info = pm.getPackageInfo(ECHO_VR_PACKAGE,
                    PackageManager.GET_SIGNATURES | PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return certSha256(info.signatures);
        } catch (Exception e) {
            return null;
        }
    }

    /** Package name and signer of a downloaded APK, as {package, signerSha256}; either may be null if unreadable. */
    public static String[] archiveIdentity(Context context, String apkPath) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        String signer = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info != null && info.signingInfo != null) signer = certSha256(info.signingInfo.getApkContentsSigners());
        } else {
            info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES);
            if (info != null) signer = certSha256(info.signatures);
        }
        return new String[] { info != null ? info.packageName : null, signer };
    }

    private static String certSha256(Signature[] signatures) {
        if (signatures == null || signatures.length != 1) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
