/**
 * Stonebreak's asset containers (#88): SBO, SBE and SBT. They are game formats, so they live with the
 * game, not in the Open Mason engine; the engine reaches them only through its format plugin points
 * ({@code FormatCodec}, {@code TextureDecoder}, {@code EntityModelImporter}).
 */
module stonebreak.formats {
    requires transitive openmason.engine.core;
    requires transitive com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires org.slf4j;

    exports com.stonebreak.format.sbo;
    exports com.stonebreak.format.sbe;
    exports com.stonebreak.format.sbt;

    // Jackson binds the manifests.
    opens com.stonebreak.format.sbo to com.fasterxml.jackson.databind;
    opens com.stonebreak.format.sbe to com.fasterxml.jackson.databind;
    opens com.stonebreak.format.sbt to com.fasterxml.jackson.databind;

    provides com.openmason.engine.format.spi.FormatCodec with
            com.stonebreak.format.sbo.SBOCodec,
            com.stonebreak.format.sbe.SBECodec,
            com.stonebreak.format.sbt.SBTCodec;
    provides com.openmason.engine.format.spi.TextureDecoder with
            com.stonebreak.format.sbt.SBTCodec;
}
