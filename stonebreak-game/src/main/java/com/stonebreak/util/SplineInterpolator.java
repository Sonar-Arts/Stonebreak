package com.stonebreak.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SplineInterpolator {

    private final List<Point> points;

    public SplineInterpolator() {
        this.points = new ArrayList<>();
    }

    public void addPoint(double x, double y) {
        points.add(new Point(x, y));
        Collections.sort(points);
    }

    public double interpolate(double x) {
        if (points.isEmpty()) {
            return 0;
        }

        if (x <= points.get(0).getX()) {
            return points.get(0).getY();
        }

        if (x >= points.get(points.size() - 1).getX()) {
            return points.get(points.size() - 1).getY();
        }

        int i = 0;
        while (i < points.size() - 1 && x > points.get(i + 1).getX()) {
            i++;
        }

        Point p1 = points.get(i);
        Point p2 = points.get(i + 1);

        double t = (x - p1.getX()) / (p2.getX() - p1.getX());
        return p1.getY() + t * (p2.getY() - p1.getY());
    }

    private static class Point implements Comparable<Point> {
        private final double x;
        private final double y;

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        @Override
        public int compareTo(Point o) {
            return Double.compare(this.x, o.x);
        }
    }
}