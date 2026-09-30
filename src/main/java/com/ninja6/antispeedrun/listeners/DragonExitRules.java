package com.ninja6.antispeedrun.listeners;

/** Small, server-independent decisions for the resummoned dragon's trophies. */
public final class DragonExitRules {

    private DragonExitRules() {
    }

    /** One independent roll per dragon death. Zero never drops; one always drops. */
    public static boolean dropsHead(double chance, double roll) {
        return chance > 0.0D && roll < chance;
    }

    /** The two-block-radius opening inside vanilla's bedrock exit-portal rim. */
    public static boolean inBasin(int dx, int dz) {
        return dx * dx + dz * dz <= 6;
    }
}
