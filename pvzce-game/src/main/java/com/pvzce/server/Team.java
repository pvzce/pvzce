package com.pvzce.server;

import com.pvzce.api.util.Identifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A team inside a level; resources are shared per team and displayed per player. */
public final class Team {
    public enum Status {
        WAITING, ACTIVE, WON, LOST
    }

    private final Identifier id;
    private final String name;
    private final Map<Identifier, Integer> resources = new HashMap<>();
    private final Set<Identifier> unlockedResources = new HashSet<>();
    private Status status = Status.ACTIVE;

    public Team(Identifier id, String name) {
        this.id = id;
        this.name = name;
    }

    public Identifier id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int resourcesOf(Identifier resource) {
        return resources.getOrDefault(resource, 0);
    }

    public void addResource(Identifier resource, int amount) {
        resources.merge(resource, amount, Integer::sum);
    }

    public boolean consume(Identifier resource, int amount) {
        int current = resources.getOrDefault(resource, 0);
        if (current < amount) {
            return false;
        }
        resources.put(resource, current - amount);
        return true;
    }

    public void putResource(Identifier resource, int amount) {
        resources.put(resource, amount);
    }

    public void unlockResource(Identifier resource) {
        unlockedResources.add(resource);
    }

    public boolean canCollect(Identifier resource) {
        return unlockedResources.contains(resource);
    }

    /** Live view of the resource balances; the save writer iterates this. */
    public Map<Identifier, Integer> resources() {
        return Map.copyOf(resources);
    }

    public Set<Identifier> resourceIds() {
        return Set.copyOf(resources.keySet());
    }

    public Set<Identifier> unlockedResources() {
        return Set.copyOf(unlockedResources);
    }
}
