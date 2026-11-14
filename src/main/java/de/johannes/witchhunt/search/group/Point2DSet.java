package de.johannes.witchhunt.search.group;

import org.jspecify.annotations.NonNull;

import java.util.*;

public final class Point2DSet {

    public Point2DSet(Point2D pos){
        Point2DSetInternal.add(pos);
    }

    public Point2DSet(@NonNull Point2DSet group, Point2D pos){
        this(pos);
        Point2DSetInternal.addAll(group.Point2DSetInternal);
    }

    private final Set<Point2D> Point2DSetInternal = new HashSet<>();

    public Point2D @NonNull [] GetPoint2Ds(){
        return Point2DSetInternal.toArray(new Point2D[0]);
    }

    public int Size(){
        return Point2DSetInternal.size();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Point2DSet other)) return false;

        return Point2DSetInternal.equals(other.Point2DSetInternal);
    }

    @Override
    public int hashCode() {
        return Point2DSetInternal.hashCode();
    }

    public double GetWelzlRadius(){
        List<Point2D> shuffled = new ArrayList<>(Point2DSetInternal);
        Collections.shuffle(shuffled);

        Circle result = welzl(shuffled, new ArrayList<>());
        return result.radius();
    }

    private Circle welzl(List<Point2D> points, List<Point2D> boundary) {

        if (points.isEmpty() || boundary.size() == 3) {
            return trivial(boundary);
        }

        Point2D p = points.removeLast();

        Circle circle = welzl(points, boundary);

        if (circle.contains(p)) {
            points.add(p);
            return circle;
        }

        boundary.add(p);

        Circle result = welzl(points, boundary);

        boundary.removeLast();
        points.add(p);

        return result;
    }

    private Circle trivial(List<Point2D> boundary) {
        switch (boundary.size()) {
            case 0 -> {
                return new Circle(new Point2D(0, 0), 0);
            }

            case 1 -> {
                return new Circle(boundary.getFirst(), 0);
            }

            case 2 -> {
                return circleFromTwoPoints(
                        boundary.get(0),
                        boundary.get(1)
                );
            }

            case 3 -> {
                Point2D a = boundary.get(0);
                Point2D b = boundary.get(1);
                Point2D c = boundary.get(2);

                Circle ab = circleFromTwoPoints(a, b);
                Circle ac = circleFromTwoPoints(a, c);
                Circle bc = circleFromTwoPoints(b, c);

                if (ab.contains(c)) return ab;
                if (ac.contains(b)) return ac;
                if (bc.contains(a)) return bc;

                return circleFromThreePoints(a, b, c);
            }

            default -> throw new IllegalArgumentException(
                    "Boundary must contain at most 3 points"
            );
        }
    }

    private Circle circleFromThreePoints(
            Point2D a,
            Point2D b,
            Point2D c
    ) {
        double d = 2.0 * (
                a.x() * (b.z() - c.z()) +
                        b.x() * (c.z() - a.z()) +
                        c.x() * (a.z() - b.z())
        );

        if (Math.abs(d) < 1e-10) {
            throw new IllegalArgumentException(
                    "Points are collinear"
            );
        }

        double aSq = a.x() * a.x() + a.z() * a.z();
        double bSq = b.x() * b.x() + b.z() * b.z();
        double cSq = c.x() * c.x() + c.z() * c.z();

        double ux = (
                aSq * (b.z() - c.z()) +
                        bSq * (c.z() - a.z()) +
                        cSq * (a.z() - b.z())
        ) / d;

        double uz = (
                aSq * (c.x() - b.x()) +
                        bSq * (a.x() - c.x()) +
                        cSq * (b.x() - a.x())
        ) / d;

        Point2D center = new Point2D(ux, uz);

        double dx = ux - a.x();
        double dz = uz - a.z();

        double radius = Math.sqrt(dx * dx + dz * dz);

        return new Circle(center, radius);
    }

    private Circle circleFromTwoPoints(Point2D a, Point2D b) {
        double x = (a.x() + b.x()) / 2.0;
        double z = (a.z() + b.z()) / 2.0;

        Point2D center = new Point2D(x, z);

        double dx = a.x() - center.x();
        double dz = a.z() - center.z();

        double radius = Math.sqrt(dx * dx + dz * dz);

        return new Circle(center, radius);
    }
}
