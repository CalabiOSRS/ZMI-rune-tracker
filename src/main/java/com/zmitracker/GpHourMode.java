package com.zmitracker;

public enum GpHourMode
{
    LAST_10_LAPS("Last 10 laps"),
    FULL_SESSION("Full session"),
    HIDDEN("Hidden");

    private final String name;
    GpHourMode(String name) { this.name = name; }

    @Override
    public String toString() { return name; }
}
