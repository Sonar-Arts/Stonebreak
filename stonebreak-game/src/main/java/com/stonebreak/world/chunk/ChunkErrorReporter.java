package com.stonebreak.world.chunk;

import com.stonebreak.util.MemoryProfiler;
import com.stonebreak.world.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

public class ChunkErrorReporter {
    private static final String ERROR_LOG_FILE = "chunk_gl_errors.txt";
    
    public void reportMeshBuildError(Chunk chunk, Exception e, String context) {
        System.err.println(context + " for chunk at (" + 
            chunk.getChunkX() + ", " + 
            chunk.getChunkZ() + "): " + e.getMessage());
        System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
    }
    
    public void reportMaxRetriesReached(Chunk chunk) {
        System.err.println("WARNING: Chunk (" + chunk.getChunkX() + ", " + chunk.getChunkZ() + 
            ") failed mesh build after " + WorldConfiguration.MAX_FAILED_CHUNK_RETRIES + " retries. Giving up.");
        MemoryProfiler.getInstance().incrementAllocation("FailedChunk");
    }
    
    public void reportGLUpdateError(Chunk chunk, Exception e, int updatesThisFrame, int maxUpdatesPerFrame, int queueSize) {
        System.err.println("CRITICAL: Exception during applyPreparedDataToGL for chunk (" + 
            chunk.getChunkX() + ", " + chunk.getChunkZ() + ")");
        System.err.println("Time: " + LocalDateTime.now());
        System.err.println("Updates this frame: " + updatesThisFrame + "/" + maxUpdatesPerFrame);
        System.err.println("Queue size: " + queueSize);
        System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
        System.err.println("Exception: " + e.getMessage());
        System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        
        writeErrorToFile(chunk, e, updatesThisFrame, maxUpdatesPerFrame, queueSize);
    }
    
    private void writeErrorToFile(Chunk chunk, Exception e, int updatesThisFrame, int maxUpdatesPerFrame, int queueSize) {
        try (FileWriter fw = new FileWriter(ERROR_LOG_FILE, true)) {
            fw.write("=== CHUNK GL ERROR " + LocalDateTime.now() + " ===\n");
            fw.write("Chunk: (" + chunk.getChunkX() + ", " + chunk.getChunkZ() + ")\n");
            fw.write("Updates: " + updatesThisFrame + "/" + maxUpdatesPerFrame + "\n");
            fw.write("Queue size: " + queueSize + "\n");
            fw.write("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB\n");
            fw.write("Exception: " + e.getMessage() + "\n");
            fw.write("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()) + "\n\n");
        } catch (IOException logEx) {
            System.err.println("Failed to write chunk GL error log: " + logEx.getMessage());
        }
    }
}