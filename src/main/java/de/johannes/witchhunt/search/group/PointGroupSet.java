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
            if (tempSet.GetWelzlRadius() < 128){
                if (Groups.add(tempSet)) {
                    addedGroups.add(tempSet);
                }
            }
        }

        if (Groups.add(newSet)) {
            addedGroups.add(newSet);
        }

        addedGroups.sort(
                Comparator.comparingInt(Point2DSet::Size).reversed()
        );

        return addedGroups;
    }

    public List<Point2DSet> GetGroups() {
        List<Point2DSet> Groups = new ArrayList<>(this.Groups);
        Groups.sort(
                Comparator.comparingInt(Point2DSet::Size).reversed()
        );
        return Groups;
    }
}
