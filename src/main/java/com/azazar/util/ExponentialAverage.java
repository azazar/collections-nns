package com.azazar.util;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public class ExponentialAverage {

    public double value = Double.NaN;
    public int period;
    private double mul1, mul2;

    private void initialize() {
        mul2 = 1d / (double)period;
        mul1 = 1d - mul2;
    }

    public ExponentialAverage(int period) {
        this.period = period;
        initialize();
    }

    public ExponentialAverage() {
        period = 100;
        initialize();
    }

    public void add(double value) {
        if (Double.isNaN(this.value))
            this.value = value;
        else
            this.value = this.value * mul1 + value * mul2;
    }

}
