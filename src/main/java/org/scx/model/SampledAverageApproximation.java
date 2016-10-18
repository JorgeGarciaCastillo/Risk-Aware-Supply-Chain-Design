package org.scx.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

import org.scx.data.RandomScenario;
import org.scx.data.RandomScenarioGenerator;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;

/**
 * "Solves" the scream problem using Sampled Average Approximation method
 */
public class SampledAverageApproximation {

    // Random seed
    private final Random random;
    private Solution solution;

    public SampledAverageApproximation(Random random) {
        this.random = random;
    }

    /**
     * 
     * @param m
     *        Number of master problem samples
     * @param n
     *        Number of scenarios per master problem
     * @param n2
     *        Number of second stage scenarios
     * @throws IloException
     *         If something wrong happens with CPLEX
     */
    public void approximateSolution(int m, int n, int n2) throws IloException {
        List<MulticutLShaped> masterSolvers = sampleMasterProblems(m, n);
        double costLB = computeLB(masterSolvers);
        System.out.println("Sampled LB : " + costLB);
        double costUB = computeUB(n2, masterSolvers);
        System.out.println("Sampled UB : " + costUB);
        System.out.println("[" + costLB + "," + costUB + "]");
    }

    /**
     * Computes LB of model by sampling scenarios and solving multiple master problems
     */
    private double computeLB(List<MulticutLShaped> masterSolvers) throws IloException {
        List<Solution> solutions = new ArrayList<>();
        for (MulticutLShaped master : masterSolvers) {
            master.solve();
            Solution solution = master.getSolution();
            solutions.add(solution);
        }
        return computeAverage(solutions);
    }

    /**
     * Computes UB of model by sampling many independent futures scenarios and solving the future decision problems with the solutions
     * obtained computing the UB
     */
    private double computeUB(int n2, List<MulticutLShaped> masterSolvers) throws IloException {
        List<Double> averagedCostOfSols = new ArrayList<>();
        SortedMap<Double, Solution> costToSolution = new TreeMap<>();
        for (MulticutLShaped master : masterSolvers) {
            System.out.println("Sampling UB for Solution : " + master.getSolution());
            long start = System.currentTimeMillis();
            Double averagedCost = sampleCostForSolution(n2, master);
            long end = System.currentTimeMillis();
            System.out.println("*** Elapsed time = " + (end - start) + " ms. *** " + ((end - start) / (double) n2) + "ms per scenario");

            costToSolution.put(averagedCost, master.getSolution());
            averagedCostOfSols.add(averagedCost);

            // Free master in each outer iteration to avoid Out of Memory Error
            master.end();
        }
        solution = costToSolution.get(costToSolution.firstKey());
        return averagedCostOfSols.stream().mapToDouble(a -> a).average().getAsDouble();
    }

    private Double sampleCostForSolution(int n2, MulticutLShaped master) throws IloException {
        List<RandomScenario> secondSample = RandomScenarioGenerator.generate(random, 10, n2 / 10);
        List<Solution> saaSolutions = new ArrayList<>();

        // Create subproblems
        List<ScenarioSubproblem> subproblems = new ArrayList<>();
        for (int i = 0; i < n2; i++) {
            ScenarioSubproblem subproblem = new ScenarioSubproblem(master, secondSample.get(i));
            subproblems.add(subproblem);
        }

        // Solve subproblems
        SubproblemsExecutor executor = new SubproblemsExecutor(subproblems);
        Map<ScenarioSubproblem, IloCplex.Status> solved = executor.solveSubproblems();
        for (ScenarioSubproblem subproblem : solved.keySet()) {
            Solution subproblemSol = subproblem.recordSolution();
            subproblemSol.totalCost += master.master.getValue(master.facBackupCost);
            saaSolutions.add(subproblem.recordSolution());

            // Free subproblem in each inner iteration to avoid Out of Memory Error
            subproblem.end();
        }

        return computeAverage(saaSolutions);
    }


    private List<MulticutLShaped> sampleMasterProblems(int m, int n) throws IloException {
        List<MulticutLShaped> masterSolvers = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            List<RandomScenario> scenarios = RandomScenarioGenerator.generate(random, 5, n / 5);
            MulticutLShaped lshaped = new MulticutLShaped(scenarios);
            masterSolvers.add(lshaped);
        }
        return masterSolvers;
    }

    private double computeAverage(List<Solution> solutions) {
        return solutions
                .stream()
                .mapToDouble(s -> s.totalCost)
                .average()
                .getAsDouble();
    }

    public Solution getSolution() {
        return solution;
    }
}
