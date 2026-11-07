package dev.restclient.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A folder or a saved request inside a collection. */
public class CollectionNode {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public boolean folder;
    public List<CollectionNode> children = new ArrayList<>();
    public RequestDef request;

    public static CollectionNode folder(String name) {
        CollectionNode n = new CollectionNode();
        n.name = name;
        n.folder = true;
        return n;
    }

    public static CollectionNode request(RequestDef r) {
        CollectionNode n = new CollectionNode();
        n.name = r.name;
        n.request = r;
        return n;
    }

    /** Deep copy with fresh ids. */
    public CollectionNode duplicate() {
        CollectionNode n = new CollectionNode();
        n.name = name;
        n.folder = folder;
        if (request != null) n.request = request.duplicate();
        for (CollectionNode c : children) n.children.add(c.duplicate());
        return n;
    }
}
