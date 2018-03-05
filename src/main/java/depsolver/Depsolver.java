package depsolver;

import com.alibaba.fastjson.JSON;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.api.*;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;

import java.util.*;
import java.util.stream.Collectors;

import static org.sosy_lab.java_smt.SolverContextFactory.Solvers;

public class Depsolver {

    private static long start;
    private static long z3Start;
    private static long z3End;

    private static final int UNINSTALL_COST = -1000000;
    static final String VIRTUAL_PACKAGE_NAME = "_VIRT_";
    static final String VIRTUAL_PACKAGE_VERSION = "1";
    static final String VIRTUAL_PACKAGE_UUID = VIRTUAL_PACKAGE_NAME + "=" + VIRTUAL_PACKAGE_VERSION;

    public static void main(String[] args) {
        start = System.currentTimeMillis();
        try (SolverContext solverContext = SolverContextFactory.createSolverContext(Solvers.Z3)) {

            Problem problem = Parser.parse(args[0], args[1], args[2]); // repo, initial, constraints
            List<String> solution = Depsolver.solve(solverContext, problem);
            z3End = System.currentTimeMillis();
            System.out.println("Z3 took " + (z3End - z3Start) + "ms");

            // write out JSON of the solution
            System.out.println(JSON.toJSON(solution));

        } catch (InvalidConfigurationException | InterruptedException | SolverException e) {
            e.printStackTrace();
        }
    }

    private static List<String> solve(SolverContext solverContext, Problem problem) throws InterruptedException,
            SolverException {
        z3Start = System.currentTimeMillis();
        System.out.println("Submitted to Z3 after: " + ((z3Start - start) / 1000) + " seconds");
        OptimizationProverEnvironment prover = solverContext.newOptimizationProverEnvironment();

        // Pseudo-Boolean
        FormulaManager fmgr = solverContext.getFormulaManager();
        BooleanFormulaManager bmgr = fmgr.getBooleanFormulaManager();
        IntegerFormulaManager imgr = fmgr.getIntegerFormulaManager();

        // Constants
        final IntegerFormula ZERO = imgr.makeNumber(0);
        final IntegerFormula ONE = imgr.makeNumber(1);

        Map<String, Package> repo = problem.getRepo();


        // for each package in repo, create a variable
        Map<String, IntegerFormula> variables = new HashMap<>();
        for (String s : repo.keySet()) {
            variables.put(s, imgr.makeVariable(s));
        }

        // add constraint that variables are either 0 or 1
        for (IntegerFormula v : variables.values()) {
            BooleanFormula constraint = bmgr.or(imgr.equal(v, ZERO),
                    imgr.equal(v, ONE));
            prover.addConstraint(constraint);
        }

        // add sizes to variables
        Map<String, IntegerFormula> sizedVariables = new HashMap<>();
        for (String s : repo.keySet()) {
            IntegerFormula v = variables.get(s);
            IntegerFormula c = imgr.makeNumber(repo.get(s).getSize());
            IntegerFormula cTimesV = imgr.multiply(c, v);
            sizedVariables.put(s, cTimesV);
        }

        // update the cost of initially installed packages
        Set<Package> initials = problem.getInitial();
        for (Package p : initials) {
            IntegerFormula oldValue = variables.get(p.getUUID());
            IntegerFormula uninstallCost = imgr.makeNumber(UNINSTALL_COST);
            IntegerFormula newValue = imgr.multiply(uninstallCost, oldValue);
            sizedVariables.put(p.getUUID(), newValue);
        }

        // assert dependencies and conflicts
        for (String s : repo.keySet()) {

            // dependencies
            for (List<String> l : repo.get(s).getDepends()) {

                // Construct the dependency formula : c0 * d0 + c1 * d1 + ... + cn * dn - p >= 0
                List<IntegerFormula> sumList = l.stream()
                        .map(variables::get)
                        .collect(Collectors.toList());

                // Add the formula as a constraint to the proverEnvironment
                if (!sumList.isEmpty()) {
                    IntegerFormula sum = imgr.sum(sumList);
                    IntegerFormula total = imgr.subtract(sum, variables.get(s));
                    BooleanFormula ineq = imgr.greaterOrEquals(total, ZERO);
                    prover.addConstraint(ineq);

                }

            }

            // conflicts
            for (String c : repo.get(s).getConflicts()) {

                // Construct the conflict formula : c + p <= 1
                IntegerFormula sum = imgr.add(variables.get(c), variables.get(s));
                BooleanFormula ineq = imgr.lessOrEquals(sum, ONE);

                // Add the formula as a constraint to the proverEnvironment
                prover.addConstraint(ineq);

            }

        }

        // install the virtual package
        BooleanFormula installVirt = imgr.greaterOrEquals(variables.get(VIRTUAL_PACKAGE_UUID), ONE);
        prover.addConstraint(installVirt);



        // optimize
        List<IntegerFormula> sumList = new ArrayList<>(sizedVariables.values());
        IntegerFormula sum = imgr.sum(sumList);
        int handle = prover.minimize(sum);

        // process results
        if (!prover.isUnsat()) {
            System.out.println("Sat");
//            System.out.println("Model: " + model);
            System.out.println(prover.getModelAssignments());

        } else {
            System.out.println("Unsat");
//            System.out.println(prover.getUnsatCore());
        }

        return new ArrayList<>();
    }
}
