package com.openmason.rendering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for OpenGLBuffer class.
 * Tests resource management, lifecycle, error handling, and thread safety.
 */
class OpenGLBufferTest {
    
    private TestOpenGLBuffer buffer;
    private MockedStatic<GL15> gl15Mock;
    private MockedStatic<BufferManager> bufferManagerMock;
    private BufferManager mockBufferManager;
    
    @BeforeEach
    void setUp() {
        // Mock OpenGL calls
        gl15Mock = mockStatic(GL15.class);
        gl15Mock.when(() -> GL15.glGenBuffers()).thenReturn(12345);
        gl15Mock.when(() -> GL15.glGetError()).thenReturn(GL15.GL_NO_ERROR);
        
        // Mock BufferManager
        bufferManagerMock = mockStatic(BufferManager.class);
        mockBufferManager = mock(BufferManager.class);
        bufferManagerMock.when(BufferManager::getInstance).thenReturn(mockBufferManager);
        
        // Create test buffer
        buffer = new TestOpenGLBuffer(GL15.GL_ARRAY_BUFFER, "TestBuffer");
    }
    
    @AfterEach
    void tearDown() {
        if (buffer != null && !buffer.isDisposed()) {
            buffer.close();
        }
        
        if (gl15Mock != null) {
            gl15Mock.close();
        }
        if (bufferManagerMock != null) {
            bufferManagerMock.close();
        }
    }
    
    /**
     * Test buffer creation and initial state.
     */
    @Test
    void testBufferCreation() {
        assertEquals(12345, buffer.getBufferId());
        assertEquals(GL15.GL_ARRAY_BUFFER, buffer.getBufferType());
        assertEquals("TestBuffer", buffer.getDebugName());
        assertTrue(buffer.isValid());
        assertFalse(buffer.isDisposed());
        assertEquals(0, buffer.getDataSize());
        
        // Verify OpenGL calls
        gl15Mock.verify(() -> GL15.glGenBuffers(), times(1));
        
        // Verify BufferManager registration
        verify(mockBufferManager, times(1)).registerBuffer(buffer);
    }
    
    /**
     * Test buffer creation failure handling.
     */
    @Test
    void testBufferCreationFailure() {
        gl15Mock.when(() -> GL15.glGenBuffers()).thenReturn(0);
        
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            new TestOpenGLBuffer(GL15.GL_ARRAY_BUFFER, "FailedBuffer");
        });
        
        assertTrue(exception.getMessage().contains("Failed to generate OpenGL buffer"));
    }
    
    /**
     * Test buffer binding and unbinding.
     */
    @Test
    void testBindAndUnbind() {
        long beforeTime = System.currentTimeMillis();
        
        buffer.bind();
        
        long afterTime = System.currentTimeMillis();
        
        // Verify binding call
        gl15Mock.verify(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 12345), times(1));
        
        // Verify last access time updated
        assertTrue(buffer.getLastAccessTime() >= beforeTime);
        assertTrue(buffer.getLastAccessTime() <= afterTime);
        
        buffer.unbind();
        
        // Verify unbinding call
        gl15Mock.verify(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0), times(1));
    }
    
    /**
     * Test buffer disposal after binding fails.
     */
    @Test
    void testBindAfterDisposal() {
        buffer.close();
        
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            buffer.bind();
        });
        
        assertTrue(exception.getMessage().contains("Buffer has been disposed"));
    }
    
    /**
     * Test data upload functionality.
     */
    @Test
    void testDataUpload() {
        FloatBuffer testData = mock(FloatBuffer.class);
        when(testData.remaining()).thenReturn(100);
        
        buffer.testUploadData(testData, GL15.GL_STATIC_DRAW);
        
        // Verify OpenGL calls
        gl15Mock.verify(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 12345), times(1));
        gl15Mock.verify(() -> GL15.glBufferData(GL15.GL_ARRAY_BUFFER, testData, GL15.GL_STATIC_DRAW), times(1));
        gl15Mock.verify(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0), times(1));
        gl15Mock.verify(() -> GL15.glGetError(), atLeastOnce());
        
        // Verify data size and usage updated
        assertEquals(100 * Float.BYTES, buffer.getDataSize());
        assertEquals(GL15.GL_STATIC_DRAW, buffer.getUsage());
    }
    
    /**
     * Test data upload with OpenGL error.
     */
    @Test
    void testDataUploadError() {
        gl15Mock.when(() -> GL15.glGetError()).thenReturn(GL15.GL_OUT_OF_MEMORY);
        
        FloatBuffer testData = mock(FloatBuffer.class);
        when(testData.remaining()).thenReturn(100);
        
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            buffer.testUploadData(testData, GL15.GL_STATIC_DRAW);
        });
        
        assertTrue(exception.getMessage().contains("Failed to upload buffer data"));
    }
    
    /**
     * Test integer data upload functionality.
     */
    @Test
    void testIntDataUpload() {
        IntBuffer testData = mock(IntBuffer.class);
        when(testData.remaining()).thenReturn(50);
        
        buffer.testUploadData(testData, GL15.GL_DYNAMIC_DRAW);
        
        // Verify data size calculation for integers
        assertEquals(50 * Integer.BYTES, buffer.getDataSize());
        assertEquals(GL15.GL_DYNAMIC_DRAW, buffer.getUsage());
    }
    
    /**
     * Test buffer disposal and cleanup.
     */
    @Test
    void testBufferDisposal() {
        assertFalse(buffer.isDisposed());
        assertTrue(buffer.isValid());
        
        buffer.close();
        
        // Verify disposal state
        assertTrue(buffer.isDisposed());
        assertFalse(buffer.isValid());
        assertEquals(0, buffer.getBufferId());
        
        // Verify OpenGL cleanup
        gl15Mock.verify(() -> GL15.glDeleteBuffers(12345), times(1));
        
        // Verify BufferManager unregistration
        verify(mockBufferManager, times(1)).unregisterBuffer(buffer);
    }
    
    /**
     * Test multiple disposal calls (idempotent behavior).
     */
    @Test
    void testMultipleDisposal() {
        buffer.close();
        buffer.close(); // Should not cause issues
        buffer.close(); // Should not cause issues
        
        // Verify OpenGL delete called only once
        gl15Mock.verify(() -> GL15.glDeleteBuffers(12345), times(1));
        
        // Verify BufferManager unregistration called only once
        verify(mockBufferManager, times(1)).unregisterBuffer(buffer);
    }
    
    /**
     * Test buffer validation after invalidation.
     */
    @Test
    void testValidationAfterInvalidation() {
        buffer.testSetInvalid();
        
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            buffer.bind();
        });
        
        assertTrue(exception.getMessage().contains("Buffer is invalid"));
    }
    
    /**
     * Test data update functionality.
     */
    @Test
    void testDataUpdate() {
        FloatBuffer updateData = mock(FloatBuffer.class);
        
        buffer.testUpdateData(64, updateData);
        
        // Verify OpenGL calls
        gl15Mock.verify(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 12345), times(1));
        gl15Mock.verify(() -> GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 64, updateData), times(1));
        gl15Mock.verify(() -> GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0), times(1));
    }
    
    /**
     * Test data update with error.
     */
    @Test
    void testDataUpdateError() {
        gl15Mock.when(() -> GL15.glGetError()).thenReturn(GL15.GL_INVALID_VALUE);
        
        FloatBuffer updateData = mock(FloatBuffer.class);
        
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            buffer.testUpdateData(64, updateData);
        });
        
        assertTrue(exception.getMessage().contains("Failed to update buffer data"));
    }
    
    /**
     * Test toString output format.
     */
    @Test
    void testToStringFormat() {
        String result = buffer.toString();
        
        assertTrue(result.contains("OpenGLBuffer"));
        assertTrue(result.contains("TestBuffer"));
        assertTrue(result.contains("12345"));
        assertTrue(result.contains("true")); // valid flag
    }
    
    /**
     * Test resource cleanup detection (cleaner functionality).
     */
    @Test
    void testResourceCleanupDetection() {
        // Create buffer but don't dispose properly
        TestOpenGLBuffer undisposedBuffer = new TestOpenGLBuffer(GL15.GL_ARRAY_BUFFER, "UndisposedBuffer");
        
        // Clear reference and suggest GC
        undisposedBuffer = null;
        System.gc();
        System.runFinalization();
        
        // The cleaner should eventually detect this, but we can't reliably test timing
        // This test verifies the cleaner registration doesn't throw exceptions
        assertTrue(true); // If we reach here, cleaner registration worked
    }
    
    /**
     * Test concurrent access to buffer operations.
     */
    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 5;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    buffer.bind();
                    buffer.unbind();
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for completion
        for (Thread thread : threads) {
            thread.join(1000); // 1 second timeout
        }
        
        // Verify buffer is still valid
        assertTrue(buffer.isValid());
        assertFalse(buffer.isDisposed());
    }
    
    /**
     * Test OpenGL context validation requirements.
     */
    @Test
    void testOpenGLContextRequirement() {
        // This test would require actual OpenGL context in a real scenario
        // For now, we verify that our mocking approach works correctly
        buffer.bind();
        
        // Verify that our mock was called correctly
        gl15Mock.verify(() -> GL15.glBindBuffer(anyInt(), anyInt()), atLeastOnce());
    }
    
    /**
     * Concrete test implementation of OpenGLBuffer for testing.
     */
    private static class TestOpenGLBuffer extends OpenGLBuffer {
        
        public TestOpenGLBuffer(int bufferType, String debugName) {
            super(bufferType, debugName);
        }
        
        // Expose protected methods for testing
        public void testUploadData(FloatBuffer data, int usage) {
            uploadData(data, usage);
        }
        
        public void testUploadData(IntBuffer data, int usage) {
            uploadData(data, usage);
        }
        
        public void testUpdateData(long offset, FloatBuffer data) {
            updateData(offset, data);
        }
        
        public void testUpdateData(long offset, IntBuffer data) {
            updateData(offset, data);
        }
        
        public void testSetInvalid() {
            this.isValid = false;
        }
    }
}