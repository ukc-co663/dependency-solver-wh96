package depsolver;

import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.api.*;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.sosy_lab.java_smt.SolverContextFactory.Solvers;

public class Depsolver {


    public static void main(String[] args) {
        try (SolverContext solverContext = SolverContextFactory.createSolverContext(Solvers.Z3)) {

            Problem problem = Parser.parse(args[0], args[1], args[2]); // repo, initial, constraints
            Solution solution = Depsolver.solve(solverContext, problem);

            // write out JSON of the solution
            // System.out.println(JSON.toJSON(solution));

        } catch (InvalidConfigurationException | InterruptedException | SolverException e) {
            e.printStackTrace();
        }
    }

    private static Solution solve(SolverContext solverContext, Problem problem) throws InterruptedException, SolverException {

        OptimizationProverEnvironment prover = solverContext.newOptimizationProverEnvironment();

        // Pseudo-Boolean
        FormulaManager fmgr = solverContext.getFormulaManager();
        BooleanFormulaManager bmgr = fmgr.getBooleanFormulaManager();
        IntegerFormulaManager imgr = fmgr.getIntegerFormulaManager();

        // Constants
        final IntegerFormula ZERO = imgr.makeNumber(0);
        final IntegerFormula ONE = imgr.makeNumber(1);


        Map<String, Package> repo = problem.getRepo();
        Set<Package> initial = problem.getInitial();


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

        // assert dependencies and conflicts
        for (String s : repo.keySet()) {

            // dependencies
            for (List<String> l : repo.get(s).getDepends()) {

                // Construct the dependency formula : c0 * d0 + c1 * d1 + ... + cn * dn - p >= 0
                List<IntegerFormula> sumList = l.stream().map(sizedVariables::get).collect(Collectors.toList());

                // catch no deps
                IntegerFormula sum = ZERO;

                if (!sumList.isEmpty()) {
                    sum = imgr.sum(sumList);
                }

                IntegerFormula neg = imgr.negate(variables.get(s));
                IntegerFormula total = imgr.add(sum, neg);
                BooleanFormula ineq = imgr.greaterOrEquals(total, ZERO);

                // Add the formula as a constraint to the proverEnvironment
                prover.addConstraint(ineq);
            }

            // conflicts
            for (String c : repo.get(s).getConflicts()) {

                // Construct the conflict formula : c + p <= 1
                IntegerFormula sum = imgr.add(variables.get(c), variables.get(s));
                BooleanFormula ineq = imgr.lessOrEquals(sum, ONE);

                // Add the formula as a constraint to the proverEnvironment
                prover.addConstraint(ineq);

            }

            // install the virtual package
            BooleanFormula installVirt = imgr.greaterOrEquals(sizedVariables.get("_VIRTUAL_=1"), ONE);
            prover.addConstraint(installVirt);

            // optimize
            // prover.minimize()

        }

        // System.out.println(prover.isUnsat());

        prover.isUnsat();
        System.out.println(prover.getModel());

        return new Solution();
    }
}
