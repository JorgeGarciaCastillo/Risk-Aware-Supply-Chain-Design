package org.scx.model;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.scx.sample.RandomScenario;
import org.scx.sample.ScenarioSampler;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;

/**
 * "Solves" the scream problem using Sampled Average Approximation method
 */
public class SampledAverageApproximation {

    // Confidence value for bounds confidence interval
    private final double confidence;
    // Scenario sampler
    private final ScenarioSampler sampler;

    // Sampled solution
    private Solution solution;

    public SampledAverageApproximation(double confidence, ScenarioSampler sampler) {
        this.confidence = confidence;
        this.sampler = sampler;
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
        SolutionCostBound costLB = computeLB(masterSolvers);
        SolutionCostBound costUB = computeUB(n2, masterSolvers);

        System.out.println("Sampled LB : " + costLB);
        System.out.println("Sampled UB : " + costUB);
    }

    private List<MulticutLShaped> sampleMasterProblems(int m, int n) throws IloException {
        List<MulticutLShaped> masterSolvers = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            List<RandomScenario> scenarios = sampler.generate(5, n / 5);
            MulticutLShaped lshaped = new MulticutLShaped(scenarios);
            masterSolvers.add(lshaped);
        }
        return masterSolvers;
    }

    /**
     * Computes LB of model by sampling scenarios and solving multiple master problems
     */
    private SolutionCostBound computeLB(List<MulticutLShaped> masterSolvers) throws IloException {
        List<Solution> solutions = new ArrayList<>();
        for (MulticutLShaped master : masterSolvers) {
            master.solve();
            Solution solution = master.getSolution();
            solutions.add(solution);

            // Free native memory from subproblems as it will not be used anymore
            master.endSubproblems();
        }
        return new SolutionCostBound(confidence, solutions.stream().map(s -> s.totalCost).collect(Collectors.toList()));
    }

    /**
     * Computes UB of model by sampling many independent futures scenarios and solving the future decision problems with the solutions
     * obtained computing the UB
     */
    private SolutionCostBound computeUB(int n2, List<MulticutLShaped> masterSolvers) throws IloException {
        List<Double> ubSamples = new ArrayList<>();
        SortedMap<Double, Solution> costToSolution = new TreeMap<>();
        for (MulticutLShaped master : masterSolvers) {
            System.out.println("Sampling UB for Solution : " + master.getSolution());
            long start = System.currentTimeMillis();
            SolutionCostBound ubSample = sampleCostForSolution(n2, master);
            long end = System.currentTimeMillis();
            System.out.println("*** Elapsed time = " + (end - start) + " ms. *** " + ((end - start) / (double) n2) + "ms per scenario");

            costToSolution.put(ubSample.getSampledAverage(), master.getSolution());
            ubSamples.add(ubSample.getSampledAverage());

            // Free master in each outer iteration to avoid Out of Memory Error
            master.end();
        }
        solution = costToSolution.get(costToSolution.firstKey());
        return new SolutionCostBound(confidence, ubSamples);
    }

    private SolutionCostBound sampleCostForSolution(int n2, MulticutLShaped master) throws IloException {
        List<Solution> saaSolutions = new ArrayList<>();
        for (RandomScenario sample : sampler.generate(10, n2 / 10)) {
            // Create and Solve subproblem for scenario
            ScenarioSubproblem subproblem = new ScenarioSubproblem(master, sample);
            IloCplex.Status status = subproblem.solve();

            assert !status.equals(IloCplex.Status.Infeasible) : "SAA requires complete recourse for 2nd stage problem";

            Solution subproblemSol = subproblem.recordSolution();
            subproblemSol.totalCost += master.master.getValue(master.facBackupCost);
            saaSolutions.add(subproblemSol);

            // Free subproblem in each inner iteration to avoid Out of Memory Error
            subproblem.end();
        }

        return new SolutionCostBound(confidence, saaSolutions.stream().map(s -> s.totalCost).collect(Collectors.toList()));
    }


    public Solution getSolution() {
        return solution;
    }
}
