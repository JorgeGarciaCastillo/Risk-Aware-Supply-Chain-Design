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
                new int[] {114, 87, 97, 91, 100, 108, 88, 96, 115, 98, 105, 102, 104, 89, 106, 110, 101, 110, 90, 97, 95, 87, 97, 94, 95,
                        103, 110, 115, 90, 86, 92, 113, 107, 98, 92, 92, 86, 105, 105, 98, 108, 100, 92, 93, 113, 92, 120, 98, 88, 111, 91,
                        108
                };
        return Collections.singletonList(new RandomScenario(randomDemand, 19, 12, 4, 14, 3, 7));
    }


}
