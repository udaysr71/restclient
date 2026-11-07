package dev.restclient.model;

import java.util.ArrayList;
import java.util.List;

/** Window state restored on relaunch: open tabs (including unsaved drafts) and layout. */
public class Session {
    public static class Tab {
        public RequestDef draft;
        public String collectionId;
        public String nodeId;
    }

    public List<Tab> tabs = new ArrayList<>();
    public int selectedTab = -1;
    public int x = -1;
    public int y = -1;
    public int width = 1280;
    public int height = 820;
    public boolean maximized;
    public boolean sidebarVisible = true;
    public int sidebarWidth = 280;
    public double responseDivider = 0.45;
    public String sidebarTab = "collections";
    public List<String> expandedNodes = new ArrayList<>();
}
