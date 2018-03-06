package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * @author wh96
 * @since 2018-03-01
 */
public class Parser {

    private static Map<String, Package> repoMap = new HashMap<>();
    private static Map<String, Package> initialMap = new HashMap<>();

    /**
     * @param repoStr
     * @param initialStr
     * @param constraintsStr
     * @return
     */
    public static Problem parse(String repoStr, String initialStr, String constraintsStr) {

        try {
            // Define two types to allow fastJSON to parse dependencies
            TypeReference<List<Package>> repoType = new TypeReference<List<Package>>() {
            };
            TypeReference<List<String>> strListType = new TypeReference<List<String>>() {
            };

            List<Package> repo = JSON.parseObject(readFile(repoStr), repoType);
            List<String> initial = JSON.parseObject(readFile(initialStr), strListType);
            List<String> constraints = JSON.parseObject(readFile(constraintsStr), strListType);

            // turn repo into a map from a name to a list of packages with that name
            Map<String, List<Package>> repoRoughMap = repo.stream().collect(Collectors.groupingBy(Package::getName));

            // create a virtual package; the goal state is just installing |VIRT|
            Package virtual = createVirtual(constraints);

            // create an initial queue for pruning the repo

            // initial is a string, needs to be package
            Deque<Package> queue = repo.stream()
                    .filter(p -> ! initial.contains(p.getUuid()))
                    .collect(Collectors.toCollection(LinkedList::new));

            queue.add(virtual);

            // prune the map to only those packages reachable from virtual
            // repoMap = pruneRepoMap(repoRoughMap, queue);
            repoMap = repo.stream().collect(toMap(Package::getUuid, p -> p));

            // add the virtual package to the repo
            repoMap.put(Depsolver.VIRTUAL_PACKAGE_UUID, virtual);

            // convert initial from a List<String> to a Map<String, Package>
            initial.forEach(k -> initialMap.put(k, repoMap.get(k)));

        } catch (IOException e) {
            e.printStackTrace();
        }

        return new Problem(repoMap, initialMap);
    }

    private static Map<String, Package> pruneRepoMap(Map<String, List<Package>> repoRoughMap, Deque<Package> queue) {

        Set<String> reachablePackageUuids = new HashSet<>();
        Map<String, Package> reachablePackageMap = new HashMap<>();

        for (Package p : queue) {
            String uuid = p.getUuid();
            reachablePackageMap.put(uuid, p);
        }

        // build and return a map from reachable package UUIDs to reachable packages
        while (!queue.isEmpty()) {
            // rewrite initial's dependencies and constraints
            for (List<String> dep : queue.pop().getDepends()) {
                for (String clause : dep) {
                    Set<String> newReachablePackageUuids = unfold(clause, repoRoughMap).stream()
                            .filter(s -> !reachablePackageUuids.contains(s))
                            .collect(toSet());

                    for (String uuid : newReachablePackageUuids) {
                        String name = uuid.split("=")[0];
                        String version = uuid.split("=")[1];

                        Package pack = repoRoughMap.get(name).stream()
                                .filter(p -> p.getVersion().equals(version))
                                .collect(toList())
                                .get(0);

                        reachablePackageMap.put(uuid, pack);
                        queue.add(pack);
                    }

                    reachablePackageUuids.addAll(newReachablePackageUuids);
                }
            }
        }

        return reachablePackageMap;
    }

    private static Set<String> unfold(String folded, Map<String, List<Package>> repoRoughMap) {

        Set<String> result = new HashSet<>();

        // determine the operator
        String name = folded;
        String op = folded.replaceAll("[.+a-zA-Z0-9-]", "");
        String version = "";

        if (!op.equals("")) {
            String[] parts = folded.split(op);
            name = parts[0];
            version = parts[1];
        }

        // Pick the right predicate to be used when filtering the repo
        Predicate<Package> predicate = getPredicate(op, version);

        List<Package> candidatePackages = repoRoughMap.get(name);

        if (candidatePackages != null) {
            result = candidatePackages
                    .stream()
                    .filter(predicate)
                    .map(Package::getUuid)
                    .collect(toSet());
        }

        return result;
    }

    private static Predicate<Package> getPredicate(String op, String version) {
        switch (op) {
            case "=":
                return p -> p.getVersion().equals(version);
            case "<":
                return p -> p.getVersion().compareTo(version) < 0;
            case "<=":
                return p -> p.getVersion().compareTo(version) <= 0;
            case ">":
                return p -> p.getVersion().compareTo(version) > 0;
            case ">=":
                return p -> p.getVersion().compareTo(version) >= 0;
            default:
                return p -> true;
        }
    }

    /**
     * @param constraints
     * @return
     */
    private static Package createVirtual(List<String> constraints) {
        Package virtual = new Package();

        virtual.setName(Depsolver.VIRTUAL_PACKAGE_NAME);
        virtual.setVersion(Depsolver.VIRTUAL_PACKAGE_VERSION);
        virtual.setSize(1);

        // dependencies
        List<List<String>> virtualDepends = constraints.stream()
                .filter(c -> c.startsWith("+"))
                .map(c -> c.substring(1))
                .map(Arrays::asList)
                .collect(Collectors.toList());

        virtual.setDepends(virtualDepends);

        // conflicts
        List<String> virtualConflicts = constraints.stream()
                .filter(c -> c.startsWith("-"))
                .map(c -> c.substring(1))
                .collect(Collectors.toList());

        virtual.setConflicts(virtualConflicts);

        return virtual;
    }

    /**
     * @param filename
     * @return
     * @throws IOException
     */
    private static String readFile(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        StringBuilder sb = new StringBuilder();
        br.lines().forEach(sb::append);
        return sb.toString();
    }
}
