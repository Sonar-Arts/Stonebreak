import com.openmason.texture.stonebreak.StonebreakTextureLoader;

public class texture_test {
    public static void main(String[] args) {
        System.out.println("Testing texture loading...");
        
        // Test if texture files can be found
        String[] variants = StonebreakTextureLoader.getAvailableVariants();
        System.out.println("Available variants: " + java.util.Arrays.toString(variants));
        
        // Test loading each variant
        for (String variant : variants) {
            System.out.println("\nTesting variant: " + variant);
            try {
                var cowVariant = StonebreakTextureLoader.getCowVariant(variant);
                if (cowVariant != null) {
                    System.out.println("✓ Successfully loaded: " + cowVariant.getDisplayName());
                    System.out.println("  Face mappings: " + cowVariant.getFaceMappings().size());
                } else {
                    System.out.println("✗ Failed to load variant: " + variant);
                }
            } catch (Exception e) {
                System.out.println("✗ Exception loading variant " + variant + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Test resource loading directly
        System.out.println("\nTesting direct resource access:");
        String[] paths = {
            "textures/mobs/cow/default_cow.json",
            "textures/mobs/cow/angus_cow.json",
            "textures/mobs/cow/highland_cow.json",
            "textures/mobs/cow/jersey_cow.json"
        };
        
        for (String path : paths) {
            java.io.InputStream stream = StonebreakTextureLoader.class.getClassLoader().getResourceAsStream(path);
            System.out.println(path + ": " + (stream != null ? "FOUND" : "NOT FOUND"));
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception e) {}
            }
        }
    }
}