package depsolver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author wh96
 * @since 2018-03-01
 */
public class Parser {

    private static List<Package> repo;
    private static List<String> initial;
    private static List<String> constraints;

    private static Map<String, Package> repoMap = new HashMap<>();
    private static Set<Package> initialSet = new HashSet<>();

    /**
     * Main method of the class
     *
     * @param repoStr
     * @param initialStr
     * @param constraintsStr
     */
    public static Problem parse(String repoStr, String initialStr, String constraintsStr) {

        try {
            // Define two types to allow fastJSON to parse dependencies
            TypeReference<List<Package>> repoType = new TypeReference<List<Package>>() {
            };
            TypeReference<List<String>> strListType = new TypeReference<List<String>>() {
            };

            repo = JSON.parseObject(readFile(repoStr), repoType);
            initial = JSON.parseObject(readFile(initialStr), strListType);
            constraints = JSON.parseObject(readFile(constraintsStr), strListType);

            // pre-process dependencies
            repo.forEach(Parser::preprocess);

            // add virtual package to repo
            Package virtual = new Package();
            virtual.setName("_VIRTUAL_");
            virtual.setVersion("1.0");
            virtual.setSize(0);
            for (String constraint : constraints) {
                if (constraint.startsWith("+")) {
                    // add to dependencies of _VIRTUAL_
                    List<List<String>> current = virtual.getDepends();
                    if (current.size() > 0) {
                        current.get(0).add(constraint.substring(1));
                    } else {
                        current.add(0, new ArrayList<>());
                        current.get(0).add(constraint.substring(1));
                    }
                } else {
                    // add to conflicts of _VIRTUAL_
                    List<String> current = virtual.getConflicts();
                    current.add(constraint.substring(1));
                }
            }
            preprocess(virtual);

            // convert initial from a List<String> to a Set<Package>
            initialSet = initial.stream()
                    .map(repoMap::get)
                    .collect(Collectors.toSet());

        } catch (IOException e) {
            e.printStackTrace();
        }
        return new Problem(repoMap, initialSet);
    }

    /**
     * Read a file as a string
     *
     * @param filename The file
     * @return The string
     * @throws IOException
     */
    private static String readFile(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        StringBuilder sb = new StringBuilder();
        br.lines().forEach(sb::append);
        return sb.toString();
    }

    /**
     * Pre-process a package so its dependencies are listed atomically. For
     * example, A depends on "B < 3" will be replaced by A depends on [B = 1; B = 2]
     *
     * @param p The package
     */
    private static void preprocess(Package p) {

        // unfold inequalities in dependencies
        List<List<String>> deps = p.getDepends();
        List<List<String>> newDeps = new ArrayList<>();

        if (deps != null) {
            deps.forEach(clause -> {
                List<String> newClause = new ArrayList<>();
                clause.stream().map(Parser::unfold).forEach(newClause::addAll);
                newDeps.add(newClause);
            });
            p.setDepends(newDeps);
        }

        // unfold inequalities in conflicts
        List<String> confls = p.getConflicts();
        List<String> newConfls = new ArrayList<>();

        if (confls != null) {
            confls.stream().map(Parser::unfold).forEach(newConfls::addAll);
            p.setConflicts(newConfls);
        }

        // add to repoMap, so packages can easily be accessed by name & version
        String key = p.getUUID();
        repoMap.put(key, p);
    }

    /**
     * Helper method for preprocess.
     * <p>
     * Convert a string expressing an inequality into a list of strings expressing
     * identities, all of which satisfy the inquality.
     *
     * @param s The string expressing an inequality
     * @return The list of strings expressing identities
     */
    private static List<String> unfold(String s) {

        // determine the operator
        String op = s.replaceAll("[.+a-zA-Z0-9-]", "");
        String[] parts = s.split(op);

        String name = parts[0];
        String version = parts.length == 2 ? parts[1] : null;

        // Pick the right predicate to be used when filtering the repo
        Predicate<Package> myPred;

        switch (op) {
            case "=":
                myPred = p -> p.getVersion().equals(version);
                break;
            case "<":
                myPred = p -> p.getVersion().compareTo(version) < 0;
                break;
            case "<=":
                myPred = p -> p.getVersion().compareTo(version) <= 0;
                break;
            case ">":
                myPred = p -> p.getVersion().compareTo(version) > 0;
                break;
            case ">=":
                myPred = p -> p.getVersion().compareTo(version) >= 0;
                break;
            default:
                myPred = p -> true;
        }

        List<String> result = repo.stream()
                .filter(p -> p.getName().equals(name))
                .filter(myPred)
                .map(Package::getUUID)
                .collect(Collectors.toList());

        return result;
    }

}
