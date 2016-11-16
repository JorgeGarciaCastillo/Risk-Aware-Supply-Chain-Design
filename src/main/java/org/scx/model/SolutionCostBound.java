package org.scx.model;

import java.util.List;

import org.apache.commons.math3.distribution.TDistribution;

/**
 * Bounds on costs sample.
 * <p>
 * We use the t distribution rather than the normal distribution as variance is not known and has to be estimated from sample data. T
 * distribution is very similar to the standard normal distribution. However, with smaller sample sizes, the t distribution is leptokurtic,
 * which means it has relatively more scores in its tails than does the normal distribution. As a result, you have to extend farther from
 * the mean to contain a given proportion of the area
 */
public class SolutionCostBound {

    private final double sampledAverage;
    private final double sampledVariance;
    private final double confidenceIntervalLB;
    private final double confidenceIntervalUB;

    /**
     * Constructs solution bound intervals.
     * 
     * @param confidence
     *        Desired confidence on the interval
     * @param sampledCosts
     *        Costs from samples
     */
    public SolutionCostBound(double confidence, List<Double> sampledCosts) {
        this.sampledAverage = computeAverage(sampledCosts);
        this.sampledVariance = computeSampleVariance(sampledCosts);

        // Create T Distribution with N-1 degrees of freedom
        TDistribution tDist = new TDistribution(sampledCosts.size() - 1);
        // Calculate critical value
        double critVal = tDist.inverseCumulativeProbability(1.0 - (1 - confidence) / 2);

        double margin = critVal * Math.sqrt(sampledVariance / sampledCosts.size());
        this.confidenceIntervalLB = sampledAverage - margin;
        this.confidenceIntervalUB = sampledAverage + margin;
    }

    private double computeAverage(List<Double> solutions) {
        return solutions
                .stream()
                .mapToDouble(s -> s)
                .average()
                .getAsDouble();
    }

    private double computeSampleVariance(List<Double> solutions) {
        return solutions
                .stream()
                .mapToDouble(s -> Math.pow(sampledAverage - s, 2))
                .sum() / (solutions.size() - 1);
    }

    public double getConfidenceIntervalLB() {
        return confidenceIntervalLB;
    }

    public double getConfidenceIntervalUB() {
        return confidenceIntervalUB;
    }

    public double getSampledAverage() {
        return sampledAverage;
    }

    public double getSampledVariance() {
        return sampledVariance;
    }

    @Override
    public String toString() {
        return "[" + confidenceIntervalLB + ", " + confidenceIntervalUB + "]";
    }

}
