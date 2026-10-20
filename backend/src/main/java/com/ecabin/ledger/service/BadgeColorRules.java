package com.ecabin.ledger.service;

/** Shared WCAG AA contrast validation for administrator-configured catalog badges. */
final class BadgeColorRules {
    private BadgeColorRules() {}

    static void requireAccessible(String background, String text, String border, String displayStyle) {
        double[] backgroundRgb = rgb(background);
        double[] textRgb = rgb(text);
        double[] borderRgb = rgb(border);
        double[] effectiveBackground = switch (displayStyle) {
            case "OUTLINE" -> new double[] {255, 255, 255};
            case "SOFT" -> blend(backgroundRgb, new double[] {255, 255, 255}, 0.133);
            default -> backgroundRgb;
        };
        if (contrast(textRgb, effectiveBackground) < 4.5) {
            throw new IllegalArgumentException("Badge text and background must meet WCAG AA contrast (4.5:1). Choose different colors.");
        }
        if ("OUTLINE".equals(displayStyle) && contrast(borderRgb, effectiveBackground) < 3.0) {
            throw new IllegalArgumentException("Outline badge border must have at least 3:1 contrast against the page background.");
        }
    }

    private static double[] rgb(String hex) {
        return new double[] {
            Integer.parseInt(hex.substring(1, 3), 16),
            Integer.parseInt(hex.substring(3, 5), 16),
            Integer.parseInt(hex.substring(5, 7), 16)
        };
    }

    private static double[] blend(double[] foreground, double[] background, double alpha) {
        return new double[] {
            foreground[0] * alpha + background[0] * (1 - alpha),
            foreground[1] * alpha + background[1] * (1 - alpha),
            foreground[2] * alpha + background[2] * (1 - alpha)
        };
    }

    private static double contrast(double[] first, double[] second) {
        double firstLuminance = luminance(first);
        double secondLuminance = luminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05) / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private static double luminance(double[] color) {
        double red = channel(color[0]);
        double green = channel(color[1]);
        double blue = channel(color[2]);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double channel(double value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
}
