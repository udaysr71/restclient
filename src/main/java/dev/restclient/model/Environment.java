package dev.restclient.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A named set of variables, e.g. Local, Staging, Production. Globals use the same shape. */
public class Environment {
    public static final String GLOBALS_ID = "globals";
    public static final String[] COLORS = {"#1A7F37", "#0062CC", "#C0271D", "#A35200", "#7A3FC4", "#636366"};

    public String id = UUID.randomUUID().toString();
    public String name = "New Environment";
    /** Dot color shown next to the name. */
    public String color = COLORS[1];
    public List<Variable> variables = new ArrayList<>();

    public static Environment globals() {
        Environment e = new Environment();
        e.id = GLOBALS_ID;
        e.name = "Globals";
        e.color = COLORS[5];
        return e;
    }

    public boolean isGlobals() {
        return GLOBALS_ID.equals(id);
    }

    public Environment duplicate(String newName) {
        Environment e = new Environment();
        e.name = newName;
        e.color = color;
        for (Variable v : variables) e.variables.add(v.copy());
        return e;
    }
}
