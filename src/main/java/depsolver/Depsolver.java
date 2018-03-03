package depsolver;

import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.api.*;

public class Main {
    public static void main(String[] args) {
//        TypeReference<List<Package>> repoType = new TypeReference<List<Package>>() {
//        };
//        List<Package> repo = JSON.parseObject(readFile(args[0]), repoType);
//        TypeReference<List<String>> strListType = new TypeReference<List<String>>() {
//        };
//        List<String> initial = JSON.parseObject(readFile(args[1]), strListType);
//        List<String> constraints = JSON.parseObject(readFile(args[2]), strListType);
//
//        // CHANGE CODE BELOW:
//        // using repo, initial and constraints, compute a solution and print the answer
//        for (Package p : repo) {
//            System.out.printf("package %s version %s\n", p.getName(), p.getVersion());
//            if (p.getDepends() != null) {
//                for (List<String> clause : p.getDepends()) {
//                    System.out.printf("  dep:");
//                    for (String q : clause) {
//                        System.out.printf(" %s", q);
//                    }
//                    System.out.printf("\n");
//                }
//            }
//            if (p.getConflicts() != null) {
//                for (String clause : p.getConflicts()) {
//                    System.out.printf("  con: %s%n", clause);
//                }
//            }
//            System.out.println();
//        }

        solve();
    }

//    static String readFile(String filename) throws IOException {
//        BufferedReader br = new BufferedReader(new FileReader(filename));
//        StringBuilder sb = new StringBuilder();
//        br.lines().forEach(line -> sb.append(line));
//        return sb.toString();
//    }

    private static void solve() {
        SolverContext context = null;
        try {
             context = SolverContextFactory.createSolverContext(SolverContextFactory.Solvers.Z3);
        } catch (InvalidConfigurationException e) {
            e.printStackTrace();
        }
        assert context != null;
        FormulaManager fmgr = context.getFormulaManager();

        BooleanFormulaManager bmgr = fmgr.getBooleanFormulaManager();

        // Declare variables
        BooleanFormula p = bmgr.makeVariable("p");
        BooleanFormula d1 = bmgr.makeVariable("d1");
        BooleanFormula d2 = bmgr.makeVariable("d2");
        BooleanFormula d3 = bmgr.makeVariable("d3");
        BooleanFormula c = bmgr.makeVariable("c1");

        // Construct constraints
        BooleanFormula constr1 = bmgr.or(bmgr.not(p), d1, d2);
        BooleanFormula constr2 = bmgr.or(bmgr.not(p), d3);
        BooleanFormula constr3 = bmgr.or(bmgr.not(p), bmgr.not(c));

        // Test for satisfiability
        try (ProverEnvironment prover = context.newProverEnvironment(SolverContext.ProverOptions.GENERATE_MODELS)) {
            // install p
            prover.addConstraint(p);

            // respect dependencies
            prover.addConstraint(constr1);
            prover.addConstraint(constr2);
            prover.addConstraint(constr3);

            boolean isUnsat = prover.isUnsat();

            if (!isUnsat) {
                Model model = prover.getModel();
                System.out.print(model);
            }
        } catch (InterruptedException | SolverException e) {
            e.printStackTrace();
        }
    }


}
