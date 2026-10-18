package com.echovr.installer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Discord OAuth2 in the headset browser, exchanging the code for a personalized patched-APK
 * URL. Same flow as the EchoVR_Launcher desktop app (marshmallow-mia/EchoVR_Launcher,
 * src/core/oauth.rs): the browser is sent to Discord's authorize page, Discord redirects to
 * a one-shot HTTP server we run on 127.0.0.1:53124, and the code is posted to the patch
 * server, which answers with the download link for this user's APK.
 */
public class DiscordAuth {

    public interface Callback {
        void onStatus(String message);
        void onPatchUrl(String patchUrl);
        /** {@code joinUrl} is non-null when the fix is joining the Patcher server. */
        void onError(String title, String message, String joinUrl);
    }

    private static final String TAG = "DiscordAuth";
    private static final String CLIENT_ID = "1326594571584409650";
    private static final String EXCHANGE_URL = "https://files.echovr.de/api/exchange";
    /** Must match the redirect registered in the Discord developer portal. */
    private static final int CALLBACK_PORT = 53124;
    private static final String REDIRECT_URI = "http://127.0.0.1:" + CALLBACK_PORT + "/callback";
    /** Longer than the launcher's 60s - signing in to Discord in the headset browser is slow. */
    private static final long CALLBACK_TIMEOUT_MS = 180_000;
    /** Opens the installer again from the "Authorization complete" page. */
    private static final String RETURN_LINK = "intent://auth#Intent;scheme=echovrinstaller;package=com.echovr.installer;end";
    /** The Echo VR Patcher server: only its members get a patch. */
    public static final String PATCHER_INVITE = "https://discord.gg/bMpsva6fmA";

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Bumped on every start/cancel; a worker whose run is no longer current stops quietly. */
    private volatile int generation;
    private Thread worker;
    /** Set per worker thread, so a superseded run can't see a newer run's state. */
    private final ThreadLocal<Integer> runId = new ThreadLocal<>();

    public DiscordAuth(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void start() {
        cancel();
        final int id = generation;
        worker = new Thread(() -> {
            runId.set(id);
            run();
        }, "DiscordAuth");
        worker.start();
    }

    /** A cancel shows nothing - the caller already knows. */
    public void cancel() {
        generation++;
        if (worker != null) worker.interrupt();
    }

    private boolean isCancelled() {
        Integer id = runId.get();
        return id == null || id != generation;
    }

    private void run() {
        String code;
        try (ServerSocket server = bind()) {
            if (server == null) {
                error("Authorization busy", "Couldn't open the Discord callback port (" + CALLBACK_PORT
                        + ").\nAnother authorization may still be finishing - wait a moment and try again.", null);
                return;
            }
            String state = randomState();
            String authUrl = authorizeUrl(state);
            status("Opening Discord in your browser...\n\nSign in, tap Authorize, then come back here.");
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                error("Authorization Failed", "Could not open your browser automatically.\nPlease open this URL manually:\n" + authUrl, null);
                return;
            }

            code = awaitCode(server, state);
        } catch (Exception e) {
            Log.e(TAG, "Callback server failed", e);
            if (!isCancelled()) error("Authorization Failed", "Callback server failed: " + e.getMessage(), null);
            return;
        }
        if (code == null || isCancelled()) return;

        status("Generating your patch file...");
        try {
            exchange(code);
        } catch (Exception e) {
            Log.e(TAG, "Exchange failed", e);
            if (!isCancelled()) error("Authorization Failed", "Couldn't reach the patch server:\n" + e.getMessage(), null);
        }
    }

    private ServerSocket bind() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            try {
                ServerSocket s = new ServerSocket(CALLBACK_PORT, 4, InetAddress.getByName("127.0.0.1"));
                s.setSoTimeout(250);
                return s;
            } catch (Exception e) {
                Log.w(TAG, "bind " + CALLBACK_PORT + " failed: " + e.getMessage());
                Thread.sleep(250);
            }
        }
        return null;
    }

    /** Keeps listening until the real /callback arrives - favicon/preconnect requests are ignored. */
    private String awaitCode(ServerSocket server, String state) throws Exception {
        long deadline = System.currentTimeMillis() + CALLBACK_TIMEOUT_MS;
        while (true) {
            if (isCancelled()) return null;
            if (System.currentTimeMillis() >= deadline) {
                error("Try again in a minute", "Discord didn't complete the authorization in time.\n\n"
                        + "If you saw a \"Service got rate limited\" message, that's Discord throttling - wait about a minute, then try again.", null);
                return null;
            }
            Socket socket;
            try {
                socket = server.accept();
            } catch (SocketTimeoutException e) {
                continue;
            }
            try (Socket s = socket) {
                s.setSoTimeout(5000);
                String target = readRequestTarget(s.getInputStream());
                Uri uri = target == null ? null : Uri.parse("http://127.0.0.1" + target);
                if (uri == null || !"/callback".equals(uri.getPath()) || !state.equals(uri.getQueryParameter("state"))) {
                    respond(s.getOutputStream(), 404, "Not found", false);
                    continue;
                }
                if (uri.getQueryParameter("error") != null) {
                    respond(s.getOutputStream(), 200, "Authorization cancelled. You can return to the installer.", true);
                    error("Authorization Failed", "The Discord authorization was cancelled in the browser.\nTry again and tap \"Authorize\" to continue.", null);
                    return null;
                }
                String code = uri.getQueryParameter("code");
                if (code == null || code.isEmpty()) {
                    respond(s.getOutputStream(), 404, "Not found", false);
                    continue;
                }
                respond(s.getOutputStream(), 200, "Authorization complete! Return to the installer to continue.", true);
                return code;
            } catch (Exception e) {
                Log.w(TAG, "Bad callback request: " + e.getMessage());
            }
        }
    }

    private static String readRequestTarget(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
        String requestLine = reader.readLine();
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        return parts.length >= 2 && parts[1].startsWith("/") ? parts[1] : null;
    }

    private static void respond(OutputStream out, int status, String message, boolean returnLink) throws Exception {
        String body = "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head>"
                + "<body style=\"font-family:sans-serif;background:#1a1a1a;color:#fff;text-align:center;padding:40px\">"
                + "<h1>" + message + "</h1>"
                + (returnLink ? "<p><a style=\"color:#4fc3f7;font-size:24px\" href=\"" + RETURN_LINK + "\">Open Echo VR Installer</a></p>" : "")
                + "</body></html>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private void exchange(String code) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(EXCHANGE_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        // The bot builds the APK before answering, so give it time.
        conn.setReadTimeout(180000);
        conn.setRequestProperty("Content-Type", "application/json");
        JSONObject req = new JSONObject();
        req.put("code", code);
        req.put("type", "apk");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
        }
        conn.disconnect();
        Log.i(TAG, "Exchange returned status " + status + ", body: " + body);
        interpretExchange(status, body.toString());
    }

    private void interpretExchange(int status, String body) {
        if (status == 403 && body.contains("not_in_guild")) {
            error("Join Server First", "You must join the Echo VR Patcher server first, then try again.\n" + PATCHER_INVITE, PATCHER_INVITE);
            return;
        }
        if (status == 403 && body.contains("phone_verification")) {
            error("Phone Verification Required", "Discord requires a verified phone number to interact in this server.\n\n"
                    + "Please verify your phone number in Discord Settings → Account → Phone Number, then try again.", null);
            return;
        }
        if (status == 409 && body.contains("busy")) {
            error("Bot Busy", "Bot is busy generating another file. Please try again in 30 seconds.", null);
            return;
        }
        if (status != 200) {
            String snippet = body.length() > 300 ? body.substring(0, 300) : body;
            error("Authorization Failed", "Server returned error (" + status + "): " + snippet, null);
            return;
        }
        String url;
        try {
            url = new JSONObject(body).optString("patchUrl", "");
        } catch (Exception e) {
            url = "";
        }
        if (url.isEmpty()) {
            error("Authorization Failed", "Failed to get patch URL from server", null);
            return;
        }
        if (!isTrustedPatchHost(url)) {
            Log.w(TAG, "Patch server sent an unexpected download location");
            error("Authorization Failed", "The server returned an unexpected download location.", null);
            return;
        }
        String finalUrl = url;
        int id = runId.get();
        mainHandler.post(() -> {
            if (id == generation) callback.onPatchUrl(finalUrl);
        });
    }

    /** Parsed, so files.echovr.de.evil.com doesn't pass. */
    static boolean isTrustedPatchHost(String url) {
        try {
            URL u = new URL(url.trim());
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return host.equals("files.echovr.de") || host.equals("evr.echo.taxi");
        } catch (Exception e) {
            return false;
        }
    }

    private static String authorizeUrl(String state) {
        return Uri.parse("https://discord.com/api/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", "identify guilds")
                .appendQueryParameter("state", state)
                .build().toString();
    }

    private static String randomState() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void status(String message) {
        int id = runId.get();
        mainHandler.post(() -> {
            if (id == generation) callback.onStatus(message);
        });
    }

    private void error(String title, String message, String joinUrl) {
        int id = runId.get();
        mainHandler.post(() -> {
            if (id == generation) callback.onError(title, message, joinUrl);
        });
    }
}
