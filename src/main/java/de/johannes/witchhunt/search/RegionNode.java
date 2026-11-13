package de.johannes.witchhunt.search;

public record RegionNode(int x, int z, long lowerBoundDistanceSquared)
        implements Comparable<RegionNode> {
    @Override
    public int compareTo(RegionNode other) {
        int byDistance = Long.compare(lowerBoundDistanceSquared, other.lowerBoundDistanceSquared);
        if (byDistance != 0) return byDistance;
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(z, other.z);
    }
}
