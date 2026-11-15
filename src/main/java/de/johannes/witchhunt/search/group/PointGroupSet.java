package de.johannes.witchhunt.search.group;

import java.util.*;

public class PointGroupSet {
    private final Set<Point2DSet> Groups = new HashSet<>();
    public List<Point2DSet> Add(Point2D p) {
        Point2DSet newSet = new Point2DSet(p);
        if (Groups.contains(newSet)) {
            return List.of();
        }

        List<Point2DSet> addedGroups = new ArrayList<>();
        Point2DSet[] existingSets = Groups.toArray(new Point2DSet[0]);

        for (Point2DSet existingSet : existingSets) {
            Point2DSet tempSet = new Point2DSet(existingSet,p);
            if (tempSet.GetWelzl().radius() < 128){
                if (Groups.add(tempSet)) {
                    addedGroups.add(tempSet);
                }
            }
        }

        if (Groups.add(newSet)) {
            addedGroups.add(newSet);
        }

        return addedGroups;
    }

    public List<Point2DSet> GetGroups() {
        return new ArrayList<>(this.Groups);
    }
}
