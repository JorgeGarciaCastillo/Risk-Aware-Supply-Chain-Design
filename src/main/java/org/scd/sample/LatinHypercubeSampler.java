package org.scd.sample;

import static org.scd.Data.NB_FACILITIES;
import static org.scd.Data.WEEKS_PER_YEAR;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.commons.math3.distribution.IntegerDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.distribution.UniformIntegerDistribution;
import org.apache.commons.math3.distribution.ZipfDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.RandomGeneratorFactory;
import org.scd.Data;

/**
 * Generates scenarios random demand and disruption using LatinHypercube sampling
 */
public class LatinHypercubeSampler implements ScenarioSampler {

    protected final RandomGenerator generator;
    private final RealDistribution demandDistribution;
    private final IntegerDistribution disruptionDistribution;
    private final IntegerDistribution disasterLengthDistribution;

    public LatinHypercubeSampler(Random random) {
        generator = RandomGeneratorFactory.createRandomGenerator(random);
        demandDistribution = new NormalDistribution(generator, Data.MEAN_DEMAND, Data.STD_DEV);
        disruptionDistribution = new UniformIntegerDistribution(generator, 0, WEEKS_PER_YEAR);
        //        disasterLengthDistribution = new UniformIntegerDistribution(generator, 0, WEEKS_PER_YEAR);
        disasterLengthDistribution = new ZipfDistribution(Data.MAX_DISRUPTION, Data.DISASTER_CASUALTY_POWER_LAW_EXPONENT);

    }

    /**
     * Generates a list of nbDemandScenarios * nbDisasterScenarios with gaussian independent weekly demand, uniform disaster start time, and
     * Zipf distribution of disaster length.
     * <p>
     * Zipf distribution is a kind of "discrete power law", which is used because disaster disruption probability have fat tails
     * <p>
     * It uses LatinHypercube sampling to reduce variance and increase and reduce uncertainty in confidence interval
     * 
     * @param nbDemandScenarios
     *        Nb of demand scenarios to sample
     * @param nbDisasterScenarios
     *        Nb of disaster scenarios to sample
     * 
     * @see NormalDistribution
     * @see UniformIntegerDistribution
     * @see ZipfDistribution
     * 
     *      {@link http://personal.rhul.ac.uk/uhte/014/Natural%20Disasters.pdf}
     * @return List of sampled scenarios
     */
    @Override
    public final List<RandomScenario> generate(int nbDemandScenarios, int nbDisasterScenarios) {
        double[][] distributionSample =
                generateHypercubeSample(nbDemandScenarios * nbDisasterScenarios, WEEKS_PER_YEAR + NB_FACILITIES * 2);

        List<RandomScenario> scenarios = new ArrayList<>();

        for (int i = 0; i < distributionSample.length; i++) {
            int[] randomDemand = new int[WEEKS_PER_YEAR];
            int j = 0;
            for (; j < WEEKS_PER_YEAR; j++) {
                randomDemand[j] = (int) demandDistribution.inverseCumulativeProbability(distributionSample[i][j]);
            }

            int[] randomDisruption = new int[NB_FACILITIES * 2];
            for (; j < distributionSample[i].length; j += 2) {
                randomDisruption[j - WEEKS_PER_YEAR] = disruptionDistribution.inverseCumulativeProbability(distributionSample[i][j]);
                int disasterLenght = Math.min(disasterLengthDistribution.inverseCumulativeProbability(distributionSample[i][j + 1]),
                        WEEKS_PER_YEAR - randomDisruption[j - WEEKS_PER_YEAR]);
                randomDisruption[j + 1 - WEEKS_PER_YEAR] = disasterLenght;
            }
            scenarios.add(new RandomScenario(randomDemand,
                    randomDisruption[0],
                    randomDisruption[1],
                    randomDisruption[2],
                    randomDisruption[3],
                    randomDisruption[4],
                    randomDisruption[5]));
        }
        return scenarios;
    }

    /**
     * Generates sequences using Latin hypercube sampling (LHS). Each axis is divided into {@code N} stripes and exactly one point may exist
     * in each stripe.
     * <p>
     * References:
     * <ol>
     * <li>McKay M.D., Beckman, R.J., and Conover W.J. "A Comparison of Three Methods for Selecting Values of Input Variables in the
     * Analysis of Output from a Computer Code." Technometrics, 21(2):239-245, 1979.
     * </ol>
     */
    public double[][] generateHypercubeSample(int N, int D) {
        double[][] result = new double[N][D];
        double[] temp = new double[N];
        double d = 1.0 / N;

        for (int i = 0; i < D; i++) {
            for (int j = 0; j < N; j++) {
                temp[j] = j * d + generator.nextDouble() * ((j + 1) * d - j * d);
            }

            shuffle(temp);

            for (int j = 0; j < N; j++) {
                result[j][i] = temp[j];
            }
        }

        return result;
    }

    private void shuffle(double[] array) {
        for (int i = array.length - 1; i >= 1; i--) {
            int j = generator.nextInt(i + 1);
            if (i != j) {
                double temp = array[i];
                array[i] = array[j];
                array[j] = temp;
            }
        }
    }
}
