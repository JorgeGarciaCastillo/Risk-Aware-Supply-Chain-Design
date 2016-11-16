package org.scx.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.scx.Data;

/**
 * Generates scenarios combining random demand and random disruptions
 */
public class MonteCarloSampler implements ScenarioSampler {

    private final Random random;

    public MonteCarloSampler(Random random) {
        this.random = random;
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
}
