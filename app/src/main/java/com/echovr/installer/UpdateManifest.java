package com.echovr.installer;

import java.util.ArrayList;
import java.util.List;

public class UpdateManifest {

    // BASE_APK is downloaded as {mirror}/{BASE_APK}, tried in order until one succeeds.
    public static final String[] APK_MIRRORS = {
            "https://files.echovr.de/",
            "https://evr.echo.taxi/"
    };

    public String baseUrl;
    public String baseApkName;
    public String baseApkHash;
    public String targetDir;
    public List<UpdateEntry> entries = new ArrayList<>();

    public static class UpdateEntry {
        public String op;
        public String path;
        public String sha256;

        public UpdateEntry(String op, String path, String sha256) {
            this.op = op;
            this.path = path;
            this.sha256 = sha256;
        }
    }
}
