package org.scx.sample;

import static org.scx.Data.WEEKS_PER_YEAR;

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
import org.scx.Data;

/**
 * Generates scenarios combining random demand and random disruptions
 */
public class MonteCarloSampler implements ScenarioSampler {

    private final RandomGenerator generator;
    private final RealDistribution demandDistribution;
    private final IntegerDistribution disruptionDistribution;
    private final IntegerDistribution disasterLengthDistribution;


    public MonteCarloSampler(Random random) {
        generator = RandomGeneratorFactory.createRandomGenerator(random);
        demandDistribution = new NormalDistribution(generator, Data.MEAN_DEMAND, Data.STD_DEV);
        disruptionDistribution = new UniformIntegerDistribution(generator, 0, WEEKS_PER_YEAR);
        disasterLengthDistribution = new ZipfDistribution(Data.MAX_DISRUPTION, Data.DISASTER_CASUALTY_POWER_LAW_EXPONENT);

    }

    /**
     * Generates a list of nbDemandScenarios * nbDisasterScenarios with gaussian independent weekly demand and uniform disaster start time
     * 
     * @param nbDemandScenarios
     *        Nb of demand scenarios to sample
     * @param nbDisasterScenarios
     *        Nb of disaster scenarios to sample
     * 
     * @return List of sampled scenarios
     */
    @Override
    public List<RandomScenario> generate(int nbDemandScenarios, int nbDisasterScenarios) {
        int[][] randomDemand = new int[nbDemandScenarios][Data.WEEKS_PER_YEAR];
        // Independently sampled demand for each week in each scenario
        for (int i = 0; i < randomDemand.length; i++) {
            for (int j = 0; j < randomDemand[i].length; j++) {
                randomDemand[i][j] = (int) demandDistribution.sample();
            }
        }
        // Independently sampled disaster start with uniform distribution in each scenario
        int[][] disruptions = new int[nbDisasterScenarios][Data.NB_FACILITIES * 2];
        for (int i = 0; i < nbDisasterScenarios; i++) {
            for (int j = 0; j < disruptions[i].length; j += 2) {
                disruptions[i][j] = this.disruptionDistribution.sample();
                int disasterLenght = Math.min(WEEKS_PER_YEAR - disruptions[i][j], Math.max(1, disasterLengthDistribution.sample()));
                disruptions[i][j + 1] = disasterLenght;
            }
        }

        List<RandomScenario> scenarios = new ArrayList<>();
        for (int i = 0; i < randomDemand.length; i++) {
            for (int j = 0; j < disruptions.length; j++) {
                scenarios.add(new RandomScenario(randomDemand[i], disruptions[j][0], disruptions[j][1], disruptions[j][2],
                        disruptions[j][3], disruptions[j][4], disruptions[j][5]));
            }
        }
        return scenarios;
    }
}
