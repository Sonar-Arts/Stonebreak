package com.openmason.test;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Master test suite for Phase 3 OpenMason components.
 * 
 * This suite orchestrates all Phase 3 tests including:
 * - System Integration Testing (Phase3IntegrationTest)
 * - Critical Fixes Integration Testing (CriticalFixesIntegrationTest)
 * - Performance Benchmarking (ViewportPerformanceTest) 
 * - Camera Controls Validation (CameraControlsTest)
 * - Model Rendering Validation (ModelRenderingValidationTest)
 * - Error Scenario Testing (ErrorScenarioTest)
 * - Individual component tests (ArcBallCameraTest)
 * 
 * Provides comprehensive validation that the Phase 3 implementation:
 * - Meets professional performance requirements (60 FPS target)
 * - Integrates correctly with existing Phase 2 systems
 * - Handles all error scenarios gracefully
 * - Maintains memory efficiency and prevents leaks
 * - Delivers professional-grade 3D viewport functionality
 */
@Suite
@SuiteDisplayName("Phase 3 OpenMason Test Suite")
@SelectClasses({
    // Core integration testing
    Phase3IntegrationTest.class,
    
    // Critical fixes integration testing
    CriticalFixesIntegrationTest.class,
    
    // Performance validation
    ViewportPerformanceTest.class,
    
    // Component validation
    CameraControlsTest.class,
    ModelRenderingValidationTest.class,
    
    // Error scenario testing
    ErrorScenarioTest.class,
    
    // Individual component tests
    com.openmason.camera.ArcBallCameraTest.class
})
public class Phase3TestSuite {
    // Test suite configuration is handled by annotations
    // Individual test classes contain their own setup/teardown logic
}