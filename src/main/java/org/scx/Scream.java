package org.scx;

import static java.lang.String.format;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.scx.model.MulticutLShaped;
import org.scx.model.SampledAverageApproximation;
import org.scx.model.SingleModel;
import org.scx.sample.LatinHypercubeSampler;
import org.scx.sample.RandomScenario;
import org.scx.sample.SingleScenarioSampler;

import ilog.concert.IloException;

/**
 * Main class to solve a very close approximation of the Scream game
 */
public class Scream {

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
            System.out.println("*** Elapsed time = " + (end - start) + " ms. ***");
        } catch (IloException ex) {
            System.err.println("\n!!!Unable to solve the Sampled Average Approximation model:\n" + ex.getMessage() + "!!!");
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
            List<RandomScenario> scenarios = new LatinHypercubeSampler(random).generate(5, n / 5);
            MulticutLShaped model = new MulticutLShaped(scenarios, riskMeasure);
            model.solve();
            Solution s = model.getSolution();
            long end = System.currentTimeMillis();
            display(s);
            model.end();
            System.out.println("*** Elapsed time = " + (end - start) + " ms. ***");
        } catch (IloException ex) {
            System.err.println("\n!!!Unable to solve the Benders model:\n" + ex.getMessage() + "!!!");
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
            System.out.println("*** Elapsed time = " + (end - start) + " ms. ***");
        } catch (IloException ex) {
            System.err.println("\nUnable to solve the unified model:\n" + ex.getMessage());
        }
    }


    /**
     * Display solution
     * 
     * @param s
     */
    private static void display(Solution s) {
        System.out.println("***\nThe unified model's solution has total cost "
                + String.format("%10.5f", s.totalCost)
                + ".\nInventory Cost: " + s.invCarryCost
                + ".\nBackup Cost: " + s.facBackupCost
                + ".\nLost Sales Cost: " + s.avgLostSales);
        System.out
                .println("WEEK  SupplierProd backupSupplier  backupWIPTransfer  wip  plantProd  backupPlant  fgToDC  fgStock  dcTransfer  backupFGTransfer  backupDC  fgToCustomer  LostSales");
        for (int i = 0; i < Data.WEEKS_PER_YEAR; i++) {
            System.out.print(i);
            System.out.print(" \t" + format("%.2f", s.supplierProd[i]));
            System.out.print(" \t" + format("%.2f", s.backupSupplier[i]));
            System.out.print(" \t" + format("%.2f", s.backupWIPTransfer[i]));
            System.out.print(" \t" + format("%.2f", s.wip[i]));
            System.out.print(" \t" + format("%.2f", s.plantProd[i]));
            System.out.print(" \t" + format("%.2f", s.backupPlant[i]));
            System.out.print(" \t" + format("%.2f", s.fgToDC[i]));
            System.out.print(" \t" + format("%.2f", s.fgStock[i]));
            System.out.print(" \t" + format("%.2f", s.dcTransfer[i]));
            System.out.print(" \t" + format("%.2f", s.backupFGTransfer[i]));
            System.out.print(" \t" + format("%.2f", s.backupDC[i]));
            System.out.print(" \t" + format("%.2f", s.fgToCustomer[i]));
            System.out.print(" \t" + format("%.2f", s.lostSales[i]));
            System.out.println();
        }
        System.out.println("BackUpPolicy : " + s.backupPolicy);
    }
}
