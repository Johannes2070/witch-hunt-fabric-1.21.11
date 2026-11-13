package de.johannes.witchhunt.search;

public record SearchResult(
        int centerX, int centerY, int centerZ,
        int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
        long distanceSquared
) {
}
