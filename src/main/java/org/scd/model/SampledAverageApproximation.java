package org.scd.model;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.scd.Solution;
import org.scd.Designer.RiskMeasure;
import org.scd.sample.RandomScenario;
import org.scd.sample.ScenarioSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;

/**
 * "Solves" the scream problem using Sampled Average Approximation method
 */
public class SampledAverageApproximation {

    private static final Logger LOG = LoggerFactory.getLogger(SampledAverageApproximation.class);

    // Confidence value for bounds confidence interval
    private final double confidence;
    // Scenario sampler
    private final ScenarioSampler sampler;

    // RiskMeasure
    private final RiskMeasure riskMeasure;

    // Sampled solution
    private Solution solution;

    public SampledAverageApproximation(double confidence, ScenarioSampler sampler, RiskMeasure riskMeasure) {
        this.confidence = confidence;
        this.sampler = sampler;
        this.riskMeasure = riskMeasure;
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
        LOG.info("Sampling solution with risk {} and ({},{},{})", riskMeasure, m, n, n2);

        List<Solution> solutions = sampleSolutions(m, n);
        SolutionCostBound costLB = new SolutionCostBound(confidence, solutions.stream().map(s -> s.totalCost).collect(Collectors.toList()));
        SolutionCostBound costUB = computeUB(n2, solutions);

        LOG.info("Winning Solution : {}", solution);
        LOG.info("Sampled LB : {} \nSampled UB : {}", costLB, costUB);
    }

    /**
     * Computes LB of model by sampling scenarios and solving multiple master problems
     */
    private List<Solution> sampleSolutions(int m, int n) throws IloException {
        List<Solution> solutions = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            List<RandomScenario> scenarios = sampler.generate(5, n / 5);
            MulticutLShaped master = new MulticutLShaped(scenarios, riskMeasure);
            LOG.trace(">>>> MASTER {} SCENARIOS : {}", i, scenarios);
            master.solve();
            Solution solution = master.getSolution();
            solutions.add(solution);
            // Free native memory in each inner iteration to avoir Out Of Memory
            master.end();

        }
        return solutions;
    }

    /**
     * Computes UB of model by sampling many independent futures scenarios and solving the future decision problems with the solutions
     * obtained computing the UB
     */
    private SolutionCostBound computeUB(int n2, List<Solution> solutions) throws IloException {
        List<Double> ubSamples = new ArrayList<>();
        SortedMap<Double, Solution> costToSolution = new TreeMap<>();
        for (Solution solution : solutions) {
            System.out.println("Sampling UB for Solution : " + solution);
            long start = System.currentTimeMillis();
            SolutionCostBound ubSample = sampleCostForSolution(n2, solution);
            long end = System.currentTimeMillis();
            System.out.println("*** Elapsed time = " + (end - start) + " ms. *** " + ((end - start) / (double) n2) + "ms per scenario");

            costToSolution.put(ubSample.getSampledAverage(), solution);
            ubSamples.add(ubSample.getSampledAverage());
        }
        solution = costToSolution.get(costToSolution.firstKey());
        return new SolutionCostBound(confidence, ubSamples);
    }

    private SolutionCostBound sampleCostForSolution(int n2, Solution solution) throws IloException {
        List<Solution> saaSolutions = new ArrayList<>();
        LOG.info("{}", solution);
        for (RandomScenario sample : sampler.generate(1, n2)) {
            // Create and Solve subproblem for scenario
            ScenarioProblem subproblem = new ScenarioProblem(solution.policyData, sample);
            IloCplex.Status status = subproblem.solve();

            assert !status.equals(IloCplex.Status.Infeasible) : "SAA requires complete recourse for 2nd stage problem";

            Solution subproblemSol = subproblem.recordSolution();
            subproblemSol.totalCost += solution.facBackupCost;
            LOG.debug("{};{};{};{}", subproblemSol.totalCost, subproblemSol.avgLostSales, subproblemSol.invCarryCost, sample);

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
