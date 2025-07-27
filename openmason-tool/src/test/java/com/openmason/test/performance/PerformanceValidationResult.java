package com.openmason.test.performance;

import java.util.List;

/**
 * Result of performance validation against requirements.
 */
public class PerformanceValidationResult {
    private final PerformanceBenchmark.PerformanceLevel level;
    private final List<String> issues;
    private final List<String> warnings;
    
    public PerformanceValidationResult(PerformanceBenchmark.PerformanceLevel level,
                                     List<String> issues, List<String> warnings) {
        this.level = level;
        this.issues = issues;
        this.warnings = warnings;
    }
    
    public PerformanceBenchmark.PerformanceLevel getLevel() { return level; }
    public List<String> getIssues() { return issues; }
    public List<String> getWarnings() { return warnings; }
    
    public boolean isAcceptable() {
        return level != PerformanceBenchmark.PerformanceLevel.CRITICAL;
    }
    
    public boolean hasIssues() {
        return !issues.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Performance Level: ").append(level);
        if (!issues.isEmpty()) {
            sb.append("\nIssues: ").append(String.join(", ", issues));
        }
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings: ").append(String.join(", ", warnings));
        }
        return sb.toString();
    }
}