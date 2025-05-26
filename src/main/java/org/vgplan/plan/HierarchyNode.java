package org.vgplan.plan;

import org.vgplan.plan.KanbanProjectManager.HierarchyType;

/**
 * Node for the project hierarchy tree.
 */
public class HierarchyNode {
    public HierarchyType type;
    public Integer id; // DB id
    public String displayName;
    public String skillSets; // Only for phase

    public HierarchyNode(HierarchyType type, Integer id, String displayName) {
        this.type = type;
        this.id = id;
        this.displayName = displayName;
    }

    public HierarchyNode(HierarchyType type, Integer id, String displayName, String skillSets) {
        this(type, id, displayName);
        this.skillSets = skillSets;
    }

    @Override
    public String toString() {
        return displayName;
    }
}