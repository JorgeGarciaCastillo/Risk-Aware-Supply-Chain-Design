package org.scd.sample;

import java.util.Random;

import org.apache.commons.math3.linear.ArrayRealVector;

/**
 * Generates scenarios random demand and disruption using Improved Distributed Hypercube sampling
 */
public class ImprovedDistributedHypercubeSampling extends LatinHypercubeSampler {

    public ImprovedDistributedHypercubeSampling(Random random) {
        super(random);
    }



    /******************************************************************************
     * IHS implements the improved distributed hypercube sampling algorithm.
     *
     * Discussion:
     *
     * N Points in an M dimensional Latin hypercube are to be selected. Each of the M coordinate dimensions is discretized to the values 1
     * through N. The points are to be chosen in such a way that no two points have any coordinate value in common. This is a standard Latin
     * hypercube requirement, and there are many solutions.
     *
     * This algorithm differs in that it tries to pick a solution which has the property that the points are "spread out" as evenly as
     * possible. It does this by determining an optimal even spacing, and using the DUPLICATION factor to allow it to choose the best of the
     * various options available to it.
     * <p>
     * References:
     * <ol>
     * <li>Brian Beachkofski, Ramana Grandhi, Improved Distributed Hypercube Sampling, American Institute of Aeronautics and Astronomics
     * Paper 2002-1274</li>
     * </ol>
     */
    @Override
    public double[][] generateHypercubeSample(int N, int D) {
        int duplication = 5;
        double opt = N / Math.pow(N, 1.0 / D);
        double[][] x = new double[D][N];

        // Pick the first point
        for (int i = 0; i < D; i++) {
            x[i][N - 1] = generator.nextInt(N);
        }

        // Initialize avail,
        // and set an entry in a random row of each column of avail to point_num
        int[][] avail = new int[D][N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < D; i++) {
                avail[i][j] = j;
            }
        }

        for (int i = 0; i < D; i++) {
            avail[i][(int) x[i][N - 1]] = N - 1;
        }

        // Main loop
        // Assign a vaue to x[:][count] for count = N - 2 down to 2
        int[][] point = new int[D][(N - 1) * duplication];
        int[] list = new int[(N - 1) * duplication];
        for (int count = N - 2; count >= 1; count--) {
            // Generate valid points
            for (int i = 0; i < D; i++) {
                for (int k = 0; k < duplication; k++) {
                    System.arraycopy(avail[i], 0, list, k * count, count + 1);
                }

                int[] point_idxs = new int[count * duplication];
                for (int r = 0; r < point_idxs.length; r++) {
                    point_idxs[r] = generator.nextInt(duplication);
                }

                for (int k = count * duplication - 1; k >= 0; k--) {
                    int pk = point_idxs[k];
                    point[i][k] = list[pk];
                    list[pk] = list[k];
                }
            }

            // For each candidate, determine the distance to all the points that have already beean selected, and save the minimum value
            double min_all = Integer.MAX_VALUE;
            int best = 0;

            for (int k = 0; k < duplication * count; k++) {

                double min_can = Integer.MAX_VALUE;
                for (int j = count; j < N; j++) {
                    ArrayRealVector differenceVector = new ArrayRealVector(D);
                    for (int d = 0; d < D; d++) {
                        differenceVector.append(point[d][k] - x[d][j]);
                    }

                    min_can = Math.min(min_can, differenceVector.getNorm());
                }

                if (Math.abs(min_can - opt) < min_all) {
                    min_all = Math.abs(min_can - opt);
                    best = k;
                }
            }

            for (int i = 0; i < D; i++) {
                x[i][count] = point[i][best];
            }

            // Having chosen x[:][count] update avail
            for (int i = 0; i < D; i++) {
                for (int j = 0; j < N; j++) {
                    if (avail[i][j] == x[i][count]) {
                        avail[i][j] = avail[i][count];
                    }
                }
            }
        }

        for (int i = 0; i < D; i++) {
            x[i][0] = avail[i][0];
        }

        return transposeMatrix(normalize(x));
    }

    private double[][] normalize(double[][] matrix) {
        double[][] normarlized = new double[matrix.length][matrix[0].length];
        double d = 1.0 / matrix[0].length;
        for (int i = 0; i < normarlized.length; i++) {
            for (int j = 0; j < normarlized[0].length; j++) {
                normarlized[i][j] = matrix[i][j] * d + generator.nextDouble() * ((matrix[i][j] + 1) * d - matrix[i][j] * d);
            }
        }
        return normarlized;
    }

    private double[][] transposeMatrix(double[][] m) {
        double[][] temp = new double[m[0].length][m.length];
        for (int i = 0; i < m.length; i++) {
            for (int j = 0; j < m[0].length; j++) {
                temp[j][i] = m[i][j];

            }

        }
        return temp;
    }
}
