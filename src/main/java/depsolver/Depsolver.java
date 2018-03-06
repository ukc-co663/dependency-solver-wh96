package depsolver;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.ImmutableList;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.api.*;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

            System.out.println(problem.getRepo().size());
            System.out.println(problem.getInitial().size());

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
        System.out.println("Submitted to Z3 after: " + (z3Start - start) + " ms");

        OptimizationProverEnvironment prover = solverContext.newOptimizationProverEnvironment();
//        ProverEnvironment prover = solverContext.newProverEnvironment(SolverContext.ProverOptions.GENERATE_UNSAT_CORE);

        // Pseudo-Boolean
        FormulaManager fmgr = solverContext.getFormulaManager();
        BooleanFormulaManager bmgr = fmgr.getBooleanFormulaManager();
        IntegerFormulaManager imgr = fmgr.getIntegerFormulaManager();

        // Constants
        final IntegerFormula ZERO = imgr.makeNumber(0);
        final IntegerFormula ONE = imgr.makeNumber(1);

        // Repository
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
        Map<String, Package> initials = problem.getInitial();
        for (String s : initials.keySet()) {
            System.out.println(s);
            IntegerFormula v = variables.get(s);
            System.out.println("Made it");
            IntegerFormula uninstallCost = imgr.makeNumber(UNINSTALL_COST);
            System.out.println("Doubly made it");
            System.out.println(uninstallCost);
            System.out.println(v);
            IntegerFormula newValue = imgr.multiply(uninstallCost, v);
            System.out.println("Here?");
            sizedVariables.put(s, newValue);
        }

        // assert dependencies and conflicts
        for (String s : repo.keySet()) {

            // dependencies
            for (List<String> l : repo.get(s).getDepends()) {

                // construct the dependency formula : c0 * d0 + c1 * d1 + ... + cn * dn - p >= 0
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

                // construct the conflict formula : c + p <= 1
                IntegerFormula sum = imgr.add(variables.get(c), variables.get(s));
                BooleanFormula ineq = imgr.lessOrEquals(sum, ONE);

                // add the formula as a constraint to the proverEnvironment
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
        List<String> result = new ArrayList<>();
        if (!prover.isUnsat()) {

            ImmutableList<Model.ValueAssignment> assignments = prover.getModelAssignments();
            List<String> toBeInstalled = assignments.stream()
                    .filter(a -> a.getValue().toString().equals("1"))
                    .filter(a -> !initials.containsKey(a.getName()))
                    .filter(a -> !a.getName().equals(VIRTUAL_PACKAGE_UUID))
                    .map(a -> "+" + a.getName())
                    .collect(Collectors.toList());

            List<String> toBeRemoved = assignments.stream()
                    .filter(a -> a.getValue().toString().equals("0"))
                    .filter(a -> initials.containsKey(a.getName()))
                    .map(a -> "-" + a.getName())
                    .collect(Collectors.toList());

            result.addAll(toBeRemoved);
            result.addAll(toBeInstalled);

        } else {
            System.out.println("Unsat");
//            System.out.println(prover.getUnsatCore());
        }

        return result;
    }
}
