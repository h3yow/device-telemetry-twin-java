package com.github.h3yow.devicetelemetrytwinjava.rule;

/**
 * 阈值比较运算符。
 */
public enum ComparisonOp {
    GT, GTE, LT, LTE;

    public boolean test(double value, double threshold) {
        return switch (this) {
            case GT -> value > threshold;
            case GTE -> value >= threshold;
            case LT -> value < threshold;
            case LTE -> value <= threshold;
        };
    }
}
