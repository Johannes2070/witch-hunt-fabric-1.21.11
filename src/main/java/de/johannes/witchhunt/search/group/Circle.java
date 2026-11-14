package de.johannes.witchhunt.search.group;

public record Circle(Point2D center, double radius) {

    public boolean contains(Point2D p) {
        double dx = center.x() - p.x();
        double dz = center.z() - p.z();

        return dx * dx + dz * dz <= radius * radius;
    }
}
