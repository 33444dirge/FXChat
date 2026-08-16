package com.dirges.fxchat.bukkit.scheduler;

public final class FoliaSupport {
    private static volatile Boolean folia;

    private FoliaSupport() {
    }

    public static void detect() {
        if (folia != null) {
            return;
        }
        synchronized (FoliaSupport.class) {
            if (folia != null) {
                return;
            }
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                folia = true;
            } catch (ClassNotFoundException ignored) {
                folia = false;
            }
        }
    }

    public static boolean isFolia() {
        if (folia == null) {
            throw new IllegalStateException("Folia detection has not run");
        }
        return folia;
    }
}
