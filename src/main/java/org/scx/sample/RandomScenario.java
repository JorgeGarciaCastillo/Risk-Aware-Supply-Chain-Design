package org.scx.sample;

import java.util.Arrays;

/**
 * Random Scenario for Scream problem
 */
public class RandomScenario {

    private final int[] randomDemand;

    private final int dcDisruptionStart;
    private final int dcDisruptionDuration;

    private final int plantDisruptionStart;
    private final int plantDisruptionDuration;

    private final int supplierDisruptionStart;
    private final int supplierDisruptionDuration;


    public RandomScenario(int[] randomDemand, int dcDisruptionStart, int dcDisruptionDuration, int planDisrupionStart,
            int plantDisruptionDuration, int supplyDisruptionStart, int supplyDisruptionDuration) {
        this.randomDemand = randomDemand;
        this.dcDisruptionStart = dcDisruptionStart;
        this.dcDisruptionDuration = dcDisruptionDuration;
        this.plantDisruptionStart = planDisrupionStart;
        this.plantDisruptionDuration = plantDisruptionDuration;
        this.supplierDisruptionStart = supplyDisruptionStart;
        this.supplierDisruptionDuration = supplyDisruptionDuration;
    }



    public int[] getRandomDemand() {
        return randomDemand;
    }



    public int getDcDisruptionStart() {
        return dcDisruptionStart;
    }


    public int getDcDisruptionDuration() {
        return dcDisruptionDuration;
    }


    public int getDcDisruptionEnd() {
        return dcDisruptionStart + dcDisruptionDuration;
    }

    public boolean isDcDisrupted(int week) {
        return week >= dcDisruptionStart && week < getDcDisruptionEnd();
    }

    public int getPlantDisruptionStart() {
        return plantDisruptionStart;
    }

    public int getPlantDisruptionDuration() {
        return plantDisruptionDuration;
    }


    public int getPlantDisruptionEnd() {
        return plantDisruptionStart + plantDisruptionDuration;
    }

    public boolean isPlantDisrupted(int week) {
        return week >= plantDisruptionStart && week < getPlantDisruptionEnd();
    }

    public int getSupplierDisruptionStart() {
        return supplierDisruptionStart;
    }

    public int getSupplierDisruptionDuration() {
        return supplierDisruptionDuration;
    }

    public int getSupplierDisruptionEnd() {
        return supplierDisruptionStart + supplierDisruptionDuration;
    }

    public boolean isSupplierDisrupted(int week) {
        return week >= supplierDisruptionStart && week < getSupplierDisruptionEnd();
    }

    @Override
    public String toString() {
        return "RandomScenario {Supplier Disruption=[" + supplierDisruptionStart + ", " + supplierDisruptionDuration + "],"
                + "Plant Disruption=[" + plantDisruptionStart + ", " + plantDisruptionDuration + "],"
                + "DC Disruption=[" + dcDisruptionStart + ", " + dcDisruptionDuration + "], randomDemand=" + Arrays.toString(randomDemand)
                + "]}";
    }
}
