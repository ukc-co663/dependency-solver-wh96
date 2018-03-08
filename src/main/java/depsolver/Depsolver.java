package depsolver;

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

    private static final int UNINSTALL_COST = -1000000;
    static final String VIRTUAL_PACKAGE_NAME = "|VIRT|";
    static final String VIRTUAL_PACKAGE_VERSION = "1";
    static final String VIRTUAL_PACKAGE_UUID = VIRTUAL_PACKAGE_NAME + "=" + VIRTUAL_PACKAGE_VERSION;

    /**
     * Entry point. Delegates major operations to other methods, then writes out the results to a
     * file.
     *
     * @param args Should be called with 3 arguments (in order): repository.json, initial.json,
     *             constraints.json
     */
    public static void main(String[] args) {
        try (SolverContext solverContext = SolverContextFactory.createSolverContext(Solvers.Z3)) {

            Problem problem = Parser.parse(args[0], args[1], args[2]); // repo, initial, constraints
            List<String> solution = Depsolver.solve(solverContext, problem);
            // System.out.println(JSON.toJSON(solution));

        } catch (InvalidConfigurationException | InterruptedException | SolverException e) {
            e.printStackTrace();
        }
    }

    /**
     * Take a Problem produced by the Parser and generate a representation to be submitted to the
     * Z3 SMT solver. The classes used are imported from SoSy Lab, which provides a general
     * purpose interface to SMT solvers.
     *
     * @param solverContext A wrapper for various configuration options and settings for the SMT
     *                      solver.
     * @param problem       The Problem to be solved.
     * @return A List of Strings, each of which is a command e.g. "+A=2.0"
     * @throws InterruptedException
     * @throws SolverException
     */
    private static List<String> solve(SolverContext solverContext, Problem problem) throws
            InterruptedException,
            SolverException {

        OptimizationProverEnvironment prover = solverContext.newOptimizationProverEnvironment();

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
            IntegerFormula v = variables.get(s);
            IntegerFormula uninstallCost = imgr.makeNumber(UNINSTALL_COST);
            IntegerFormula newValue = imgr.multiply(uninstallCost, v);
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
        int _handle = prover.minimize(sum);

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
        }

        return result;
    }
}
