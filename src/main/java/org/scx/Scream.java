package org.scx;

import static java.lang.String.format;

import java.util.List;
import java.util.Random;

import org.scx.data.Data;
import org.scx.data.RandomScenario;
import org.scx.model.MulticutLShaped;
import org.scx.model.SampledAverageApproximation;
import org.scx.model.SingleModel;
import org.scx.model.Solution;

import ilog.concert.IloException;

/**
 * This the main class to solve a very close approximation of the Scream game
 */
public class Scream {

    public static void main(String[] args) {
        // Build and solve full stochastic bender decomposed model
        Random random = new Random(0);
        solveFullStochastic(random);
    }

    /**
     * Solves the sample average approximation model
     */
    private static void solveFullStochastic(Random random) {
        try {
            long start = System.currentTimeMillis();
            SampledAverageApproximation saa = new SampledAverageApproximation(random);
            saa.approximateSolution(10, 50, 500);
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
     */
    protected static void solveDiscretizedScenarios(List<RandomScenario> scenarios) {
        try {
            long start = System.currentTimeMillis();
            MulticutLShaped model = new MulticutLShaped(scenarios);
            model.solve();
            Solution s = model.getSolution();
            long end = System.currentTimeMillis();
            display(s);
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
    protected static void solveUnified(RandomScenario scenario) {
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
