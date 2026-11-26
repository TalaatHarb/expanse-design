package net.talaatharb.expanse.simulation;

import net.talaatharb.expanse.domain.DesignException;

final class DenseSolver {
    private DenseSolver() {
    }

    /** Gaussian elimination with partial pivoting; the inputs are consumed. */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int column = 0; column < n; column++) {
            int pivot = column;
            for (int row = column + 1; row < n; row++) {
                if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][column]) < 1e-21) {
                throw singular();
            }
            double[] swap = a[column];
            a[column] = a[pivot];
            a[pivot] = swap;
            double value = b[column];
            b[column] = b[pivot];
            b[pivot] = value;
            for (int row = column + 1; row < n; row++) {
                double factor = a[row][column] / a[column][column];
                if (factor != 0) {
                    for (int k = column; k < n; k++) {
                        a[row][k] -= factor * a[column][k];
                    }
                    b[row] -= factor * b[column];
                }
            }
        }
        double[] x = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = b[row];
            for (int k = row + 1; k < n; k++) {
                sum -= a[row][k] * x[k];
            }
            x[row] = sum / a[row][row];
            if (!Double.isFinite(x[row]) || Math.abs(x[row]) > 1e12) {
                throw singular();
            }
        }
        return x;
    }

    private static DesignException singular() {
        return new DesignException("SIM_SINGULAR",
                "The circuit matrix is singular: check for loops of voltage sources/inductors or shorted sources");
    }
}
