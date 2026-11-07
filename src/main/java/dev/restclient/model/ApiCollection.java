package dev.restclient.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** A named collection of folders and requests with its own variables. */
public class ApiCollection {
    public String id = UUID.randomUUID().toString();
    public String name = "New Collection";
    public List<Variable> variables = new ArrayList<>();
    public List<CollectionNode> items = new ArrayList<>();

    /** Finds a node anywhere in the tree. */
    public CollectionNode find(String nodeId) {
        return find(items, nodeId);
    }

    private static CollectionNode find(List<CollectionNode> list, String nodeId) {
        for (CollectionNode n : list) {
            if (n.id.equals(nodeId)) return n;
            CollectionNode c = find(n.children, nodeId);
            if (c != null) return c;
        }
        return null;
    }

    /** The list that directly contains the node, or null. */
    public List<CollectionNode> parentList(String nodeId) {
        return parentList(items, nodeId);
    }

    private static List<CollectionNode> parentList(List<CollectionNode> list, String nodeId) {
        for (CollectionNode n : list) {
            if (n.id.equals(nodeId)) return list;
            List<CollectionNode> l = parentList(n.children, nodeId);
            if (l != null) return l;
        }
        return null;
    }

    public boolean remove(String nodeId) {
        List<CollectionNode> l = parentList(nodeId);
        return l != null && l.removeIf(n -> n.id.equals(nodeId));
    }

    public void forEachRequest(Consumer<CollectionNode> action) {
        walk(items, action);
    }

    private static void walk(List<CollectionNode> list, Consumer<CollectionNode> action) {
        for (CollectionNode n : list) {
            if (!n.folder && n.request != null) action.accept(n);
            walk(n.children, action);
        }
    }

    /** "Orders API › Orders" style path to a node (excluding the node itself). */
    public String pathTo(String nodeId) {
        StringBuilder sb = new StringBuilder(name);
        if (pathTo(items, nodeId, sb)) return sb.toString();
        return name;
    }

    private static boolean pathTo(List<CollectionNode> list, String nodeId, StringBuilder sb) {
        for (CollectionNode n : list) {
            if (n.id.equals(nodeId)) return true;
            if (n.folder) {
                int len = sb.length();
                sb.append(" › ").append(n.name);
                if (pathTo(n.children, nodeId, sb)) return true;
                sb.setLength(len);
            }
        }
        return false;
    }
}
