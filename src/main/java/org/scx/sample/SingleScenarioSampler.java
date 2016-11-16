package org.scx.sample;

import java.util.Collections;
import java.util.List;

/**
 * Single deterministic scenario to test
 */
public class SingleScenarioSampler implements ScenarioSampler {

    @Override
    public List<RandomScenario> generate(int nbDemandScenarios, int nbDisasterScenarios) {
        int[] randomDemand =
                new int[] {116, 93, 84, 113, 106, 88, 82, 97, 93, 98, 103, 107, 93, 93, 120, 89, 97, 103, 110, 103, 103, 91, 95, 99, 96,
                        97, 93, 97, 90, 104, 82, 84, 106, 96, 94, 105, 106, 95, 99, 114, 100, 98, 90, 114, 118, 103, 109, 108, 106, 83, 94,
                        84
                };
        return Collections.singletonList(new RandomScenario(randomDemand, 4, 12, 8, 16, 20, 26));
    }

}
