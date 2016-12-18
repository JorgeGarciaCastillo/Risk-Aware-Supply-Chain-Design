package org.scd;

import static org.scd.Solution.display;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.scd.model.MulticutLShaped;
import org.scd.model.SampledAverageApproximation;
import org.scd.model.SingleModel;
import org.scd.sample.LatinHypercubeSampler;
import org.scd.sample.RandomScenario;
import org.scd.sample.SingleScenarioSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ilog.concert.IloException;

/**
 * Main class to solve stochastic design model
 */
public class Designer {

    private static final Logger LOG = LoggerFactory.getLogger(Designer.class);

    /**
     * Solving algorithms
     */
    public enum Model {
        deterministic, discrete, full;
    }

    /**
     * Risk measures available for decomposed algorithms
     */
    public enum RiskMeasure {
        neutral, robust, variabilityIdx, probFinancialRisk, downsideRisk;
    }

    public static void main(String[] args) {
        Model model = args != null && args.length > 0 ? Model.valueOf(args[0]) : Model.discrete;
        RiskMeasure riskMeasure = args != null && args.length > 1 ? RiskMeasure.valueOf(args[1]) : RiskMeasure.neutral;
        int m = args != null && args.length > 2 ? Integer.valueOf(args[2]) : 2;
        int n = args != null && args.length > 3 ? Integer.valueOf(args[3]) : 1000;
        int n2 = args != null && args.length > 4 ? Integer.valueOf(args[4]) : 5000;

        Random random = new Random(0);
        switch (model) {
            case deterministic:
                RandomScenario scenario = new SingleScenarioSampler().generate(1, 1).get(0);
                solveUnified(scenario);
                break;
            case discrete:
                solveDiscretizedScenarios(random, n, riskMeasure);
                break;
            case full:
                solveFullStochastic(random, riskMeasure, m, n, n2);
                break;
            default:
                throw new IllegalStateException("Incorrect model name : " + Arrays.toString(Model.values()));
        }
    }

    /**
     * Solves the sample average approximation model
     * 
     * @param riskMeasure
     */
    private static void solveFullStochastic(Random random, RiskMeasure riskMeasure, int m, int n, int n2) {
        try {
            long start = System.currentTimeMillis();
            SampledAverageApproximation saa = new SampledAverageApproximation(0.95, new LatinHypercubeSampler(random), riskMeasure);
            saa.approximateSolution(m, n, n2);
            Solution s = saa.getSolution();
            long end = System.currentTimeMillis();
            display(s);
            LOG.info("*** Elapsed time = {} ms. ***", (end - start));
        } catch (IloException ex) {
            LOG.error("\n!!!Unable to solve the Sampled Average Approximation model:\n" + ex.getMessage() + "!!!");
        }
    }

    /**
     * Solves the full stochastic model
     * 
     * @param riskMeasure
     */
    private static void solveDiscretizedScenarios(Random random, int n, RiskMeasure riskMeasure) {
        try {
            long start = System.currentTimeMillis();
            List<RandomScenario> scenarios = new LatinHypercubeSampler(random).generate(1, n);
            scenarios = new SingleScenarioSampler().generate(1, 1);
            MulticutLShaped model = new MulticutLShaped(scenarios, riskMeasure);
            model.solve();
            Solution s = model.getSolution();
            long end = System.currentTimeMillis();
            display(s);
            model.end();
            LOG.info("*** Elapsed time = {} ms. ***", (end - start));
        } catch (IloException ex) {
            LOG.error("\n!!!Unable to solve the Benders model:\n{}!!!", ex.getMessage());
        }
    }


    /**
     * Solves deterministic unified model
     * 
     * @param scenario
     */
    private static void solveUnified(RandomScenario scenario) {
        try {
            long start = System.currentTimeMillis();
            SingleModel model = new SingleModel(scenario);
            Solution s = model.solve();
            long end = System.currentTimeMillis();
            display(s);
            LOG.info("*** Elapsed time = {} ms. ***", (end - start));
        } catch (IloException ex) {
            LOG.error("\nUnable to solve the unified model:\n" + ex.getMessage());
        }
    }

}
