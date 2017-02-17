package org.scd.sample;

import static org.junit.Assert.assertNotNull;

import java.util.Random;

import org.junit.Test;

/**
 * Unit tests for {@link ImprovedDistributedHypercubeSampling}
 */
public class ImprovedDistributedHypercubeSamplingTest {

    @Test
    public void testGenerateHypercubeSample() {
        LatinHypercubeSampler sampler = new ImprovedDistributedHypercubeSampling(new Random(0));
        double[][] sample = sampler.generateHypercubeSample(4, 5);
        assertNotNull(sample);
    }

}
