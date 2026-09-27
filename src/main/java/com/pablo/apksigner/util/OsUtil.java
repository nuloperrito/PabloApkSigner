package com.pablo.apksigner.util;

public final class OsUtil {

    public static OS getOsType() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
            return OS.WIN;
        }
        if (osName.contains("mac")) {
            return OS.MAC;
        }

        return OS._NIX;
    }

    public enum OS {
        WIN, MAC, _NIX
    }
}
