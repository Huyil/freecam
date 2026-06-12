package com.zergatul.freecam.ui;

public class LinearValueMapper implements ValueMapper {

    private final double min;
    private final double max;

    public LinearValueMapper(double min, double max) {
        this.min = min;
        this.max = max;
    }

    @Override
    public double toSliderValue(double value) {
        return (value - min) / (max - min);
    }

    @Override
    public double toSettingValue(double value) {
        return min + value * (max - min);
    }

    @Override
    public String toDisplay(double value) {
        return String.format("%.1f", toSettingValue(value));
    }
}
