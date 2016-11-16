package org.scx.sample;

import java.util.List;

/**
 * Scenario sampling interface
 */
public interface ScenarioSampler {

    /**
     * Sample a total of nbDemandScenarios*nbDisasterScenarios
     * @param nbDemandScenarios
     *        Number of demand scenarios to sample
     * @param nbDisasterScenarios
     *        Number of disaster scenarios to sample
     * 
     * @return List of sampled random scenarios
     */
    List<RandomScenario> generate(int nbDemandScenarios, int nbDisasterScenarios);

}
