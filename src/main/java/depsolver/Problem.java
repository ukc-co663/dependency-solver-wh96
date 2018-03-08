package depsolver;

import java.util.Map;

public class Problem {
    private final Map<String, Package> repo;
    private final Map<String, Package> initial;

    public Problem(Map<String, Package> repo, Map<String, Package> initial) {
        this.repo = repo;
        this.initial = initial;
    }

    public Map<String, Package> getRepo() {
        return repo;
    }

    public Map<String, Package> getInitial() {
        return initial;
    }
}
