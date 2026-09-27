package com.pablo.apksigner.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A parameter model to store configurations collected from the GUI.
 */
public class Arg {
    public String[] apkFile;
    public String out;

    public List<SignArgs> signArgsList = new ArrayList<>();
    public String lineageFilePath;

    public boolean overwrite = false;
    public boolean dryRun = false;
    public boolean verbose = false;
    public boolean skipZipAlign = false;
    public boolean debug = false;
    public boolean onlyVerify = false;
    public boolean ksIsDebug = false;
    public boolean allowResign = true;
    public String[] checkCertSha256;

    public boolean v1SchemeEnabled = true;
    public boolean v2SchemeEnabled = true;
    public boolean v3SchemeEnabled = true;
    public boolean v4SchemeEnabled = false;

    public Arg() {
    }

    public static class SignArgs {
        public int index;
        public String ksFile;
        public String alias;
        public String pass;
        public String keyPass;

        public SignArgs(int index, String ksFile, String alias, String pass, String keyPass) {
            this.index = index;
            this.ksFile = ksFile;
            this.alias = alias;
            this.pass = pass;
            this.keyPass = keyPass;
        }

        @Override
        public String toString() {
            return "SignArgs{" + "index=" + index + ", ksFile='" + ksFile + '\'' + '}';
        }
    }
}
