package depsolver;

import java.util.Map;
import java.util.Set;

/**
 * @author wh96
 * @since 2018-03-04
 */
public class Problem {
    private final Map<String, Package> repo;
    private final Set<Package> initial;

    public Problem(Map<String, Package> repo, Set<Package> initial) {
        this.repo = repo;
        this.initial = initial;
    }

    public Map<String, Package> getRepo() {
        return repo;
    }

    public Set<Package> getInitial() {
        return initial;
    }
}
