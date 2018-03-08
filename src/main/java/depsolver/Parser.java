package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

/**
 * @author wh96
 * @since 2018-03-01
 */
public class Parser {

    private static Map<String, Package> repoMap = new HashMap<>();
    private static Map<String, Package> initialMap = new HashMap<>();

    /**
     *
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
            Map<String, List<Package>> repoRoughMap = repo.stream()
                    .collect(Collectors.groupingBy(Package::getName));

            // rewrite dependencies, replacing inequalities with identities
            // initialise Map: ineq -> (uuid, position); allows us to compute only once
            Map<String, Set<DepPosition>> dependencyMap = new HashMap<>();

            for (Package p : repo) {
                int numberOfDependencyEntries = p.getDepends().size();
                for (int i = 0; i < numberOfDependencyEntries; i++) {
                    for (String dependency : p.getDepends().get(i)) {
                        dependencyMap.computeIfAbsent(dependency, k -> new HashSet<>());
                        dependencyMap.get(dependency).add(new DepPosition(p.getUuid(), i));
                    }
                }
            }

            // perform the rewrite
            for (String clause : dependencyMap.keySet()) {
                // compute the replacement strings
                // A < 3 => {A = 1, A = 2} etc.

                // Look up the package uuid and the position of the list which contains this
                // dependency.

                // Remove "clause" from the list

                // Add the replacement to the list
            }


            // create a virtual package; the goal state is just installing |VIRT|
            Package virtual = createVirtual(constraints);

            // convert repo from a List<String> to a Map<String, Package>
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

    /**
     *
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
     *
     */
    private static String readFile(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        StringBuilder sb = new StringBuilder();
        br.lines().forEach(sb::append);
        return sb.toString();
    }
}
