package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

public class Parser {

    private static Map<String, Package> repoMap = new HashMap<>();
    private static Map<String, Package> initialMap = new HashMap<>();

    /**
     * Main method of the class.
     * <p>
     * Parse the input files. Rewrite dependencies and conflicts using only "=" relations. Return
     * the results of parsing with the Problem wrapper class.
     *
     * @param repoStr        The path to repository.json
     * @param initialStr     The path to initial.json
     * @param constraintsStr The path to constrains.json
     * @return The results of parsing, wrapped using the Problem class.
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

            // create a virtual package; the goal state is just installing |VIRT|
            Package virtual = createVirtual(constraints);
            repo.add(virtual);

            // turn repo into a map from a name to a list of packages with that name
            Map<String, List<Package>> repoRoughMap = repo.stream()
                    .collect(Collectors.groupingBy(Package::getName));

            // rewrite dependencies and conflicts, replacing inequalities with identities

            // initialise a rewrite cache to speed things up
            Map<String, Set<String>> rewriteCache = new HashMap<>();

            // perform the rewrite
            for (Package p : repo) {
                List<List<String>> newDeps = new ArrayList<>();
                for (List<String> clause : p.getDepends()) {
                    List<String> newClause = new ArrayList<>();
                    for (String dependency : clause) {
                        if (rewriteCache.get(dependency) != null) {
                            newClause.addAll(rewriteCache.get(dependency));
                        } else {
                            Set<String> replacements = unfold(dependency, repoRoughMap);
                            rewriteCache.put(dependency, replacements);
                            newClause.addAll(replacements);
                        }
                    }
                    newDeps.add(newClause);
                }
                p.setDepends(newDeps);

                List<String> newConfls = new ArrayList<>();
                for (String conflict : p.getConflicts()) {
                    if (rewriteCache.get(conflict) != null) {
                        newConfls.addAll(rewriteCache.get(conflict));
                    } else {
                        Set<String> replacements = unfold(conflict, repoRoughMap);
                        rewriteCache.put(conflict, replacements);
                        newConfls.addAll(replacements);
                    }

                }
                p.setConflicts(newConfls);
            }


            // convert repo from a List<String> to a Map<String, Package>
            repoMap = repo.stream().collect(toMap(Package::getUuid, p -> p));

            // convert initial from a List<String> to a Map<String, Package>
            initial.forEach(k -> initialMap.put(k, repoMap.get(k)));

        } catch (IOException e) {
            e.printStackTrace();
        }

        return new Problem(repoMap, initialMap);
    }

    /**
     * Take a String representing a dependency or a conflict, and return a Set of Strings that
     * express the same dependencies or conflicts, using only "=" as the relation between Package
     * name and Package version. A rough Map version of the repository is used to improve
     * performance.
     *
     * @param folded       The original String, which may use "<", ">=" etc.
     * @param repoRoughMap A Map representation of the repository. Keys are Package names; Values
     *                     are Lists of all Packages with that name (may be multiple versions, e.g.
     *                     A => [A=2.0, A=2.1] etc). The Map is "rough" because the keys are not
     *                     as precise as the UUIDs used for keys later.
     * @return A Set of Strings representing a dependency or conflict of a Package.
     */
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
                    .collect(Collectors.toSet());
        }

        return result;
    }

    /**
     * Generate a Predicate from a String, to be used when filtering the repo to rewrite
     * dependencies/conflicts from inequalities to lists of equalities that have the same truth
     * conditions.
     *
     * @param op      A String representing the type of inequality being rewritten.
     * @param version The version of the package used to complete the Predicate.
     * @return A Predicate which selects Packages which satisfy the supplied inequality.
     */
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
     * Creates a "Virtual" package that wraps the contents of constraints.json. Positive
     * constraints e.g. "+A=2" are added as dependencies; negative constraints e.g. "-B=3.1" are
     * added as conflicts.
     *
     * @param constraints The String read from constraints.json
     * @return A Virt
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
     * Helper function to read a File to a String
     *
     * @param filename The path to the File to be read
     * @return A String holding the contents of the file
     * @throws IOException
     */
    private static String readFile(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        StringBuilder sb = new StringBuilder();
        br.lines().forEach(sb::append);
        return sb.toString();
    }
}
