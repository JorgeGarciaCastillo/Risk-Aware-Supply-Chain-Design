package org.scx.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates scenarios combining random demand and random disruptions
 */
public class RandomScenarioGenerator {

    /**
     * Generates a list of nbDemandScenarios * nbDisasterScenarios with gaussian independent weekly demand and uniform disaster start time
     * 
     * TODO Use Latin Hypercube Sampling
     * 
     * @param random
     *        random seed to sample
     * @param nbDemandScenarios
     *        Nb of demand scenarios to sample
     * @param nbDisasterScenarios
     *        Nb of disaster scenarios to sample
     * @return List of sampled scenarios
     */
    public static final List<RandomScenario> generate(Random random, int nbDemandScenarios, int nbDisasterScenarios) {
        int[][] randomDemand = new int[nbDemandScenarios][Data.WEEKS_PER_YEAR];
        // Independently sampled demand for each week in each scenario
        for (int i = 0; i < randomDemand.length; i++) {
            for (int j = 0; j < randomDemand[i].length; j++) {
                randomDemand[i][j] = Double.valueOf(random.nextGaussian() * Data.STD_DEV + Data.MEAN_DEMAND).intValue();
            }
        }
        // Independently sampled disaster start with uniform distribution in each scenario
        int[][] disruptions = new int[nbDisasterScenarios][Data.NB_FACILITIES * 2];
        for (int i = 0; i < nbDisasterScenarios; i++) {
            for (int j = 0; j < disruptions[i].length; j += 2) {
                disruptions[i][j] = random.nextInt(Data.WEEKS_PER_YEAR);
                disruptions[i][j + 1] = random.nextInt(Data.WEEKS_PER_YEAR - disruptions[i][j]);
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

    /**
     * Single deterministic scenario to test
     */
    public static final RandomScenario generateSingle() {
        int[] randomDemand =
                new int[] {116, 93, 84, 113, 106, 88, 82, 97, 93, 98, 103, 107, 93, 93, 120, 89, 97, 103, 110, 103, 103, 91, 95, 99, 96,
                        97, 93, 97, 90, 104, 82, 84, 106, 96, 94, 105, 106, 95, 99, 114, 100, 98, 90, 114, 118, 103, 109, 108, 106, 83, 94,
                        84
                };
        return new RandomScenario(randomDemand, 4, 12, 8, 16, 20, 26);
    }
}
