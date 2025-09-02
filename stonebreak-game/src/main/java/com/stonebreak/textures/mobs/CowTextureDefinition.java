package com.stonebreak.textures.mobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.List;

public class CowTextureDefinition {
    
    @JsonProperty("cowVariants")
    private Map<String, CowVariant> cowVariants;
    
    @JsonProperty("textureAtlas")
    private TextureAtlas textureAtlas;
    
    public Map<String, CowVariant> getCowVariants() {
        return cowVariants;
    }
    
    public void setCowVariants(Map<String, CowVariant> cowVariants) {
        this.cowVariants = cowVariants;
    }
    
    public TextureAtlas getTextureAtlas() {
        return textureAtlas;
    }
    
    public void setTextureAtlas(TextureAtlas textureAtlas) {
        this.textureAtlas = textureAtlas;
    }
    
    public static class CowVariant {
        @JsonProperty("displayName")
        private String displayName;
        
        @JsonProperty("faceMappings")
        private Map<String, AtlasCoordinate> faceMappings;
        
        @JsonProperty("baseColors")
        private BaseColors baseColors;
        
        @JsonProperty("drawingInstructions")
        private Map<String, DrawingInstructions> drawingInstructions;
        
        public String getDisplayName() {
            return displayName;
        }
        
        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
        
        public Map<String, AtlasCoordinate> getFaceMappings() {
            return faceMappings;
        }
        
        public void setFaceMappings(Map<String, AtlasCoordinate> faceMappings) {
            this.faceMappings = faceMappings;
        }
        
        public BaseColors getBaseColors() {
            return baseColors;
        }
        
        public void setBaseColors(BaseColors baseColors) {
            this.baseColors = baseColors;
        }
        
        public Map<String, DrawingInstructions> getDrawingInstructions() {
            return drawingInstructions;
        }
        
        public void setDrawingInstructions(Map<String, DrawingInstructions> drawingInstructions) {
            this.drawingInstructions = drawingInstructions;
        }
    }
    
    public static class BaseColors {
        @JsonProperty("primary")
        private String primary;
        
        @JsonProperty("secondary")
        private String secondary;
        
        @JsonProperty("accent")
        private String accent;
        
        public String getPrimary() {
            return primary;
        }
        
        public void setPrimary(String primary) {
            this.primary = primary;
        }
        
        public String getSecondary() {
            return secondary;
        }
        
        public void setSecondary(String secondary) {
            this.secondary = secondary;
        }
        
        public String getAccent() {
            return accent;
        }
        
        public void setAccent(String accent) {
            this.accent = accent;
        }
    }
    
    public static class AtlasCoordinate {
        @JsonProperty("atlasX")
        private int atlasX;
        
        @JsonProperty("atlasY")
        private int atlasY;
        
        public int getAtlasX() {
            return atlasX;
        }
        
        public void setAtlasX(int atlasX) {
            this.atlasX = atlasX;
        }
        
        public int getAtlasY() {
            return atlasY;
        }
        
        public void setAtlasY(int atlasY) {
            this.atlasY = atlasY;
        }
    }
    
    public static class DrawingInstructions {
        @JsonProperty("partType")
        private String partType;
        
        @JsonProperty("baseTexture")
        private BaseTexture baseTexture;
        
        @JsonProperty("facialFeatures")
        private FacialFeatures facialFeatures;
        
        @JsonProperty("patterns")
        private List<Pattern> patterns;
        
        public String getPartType() {
            return partType;
        }
        
        public void setPartType(String partType) {
            this.partType = partType;
        }
        
        public BaseTexture getBaseTexture() {
            return baseTexture;
        }
        
        public void setBaseTexture(BaseTexture baseTexture) {
            this.baseTexture = baseTexture;
        }
        
        public FacialFeatures getFacialFeatures() {
            return facialFeatures;
        }
        
        public void setFacialFeatures(FacialFeatures facialFeatures) {
            this.facialFeatures = facialFeatures;
        }
        
        public List<Pattern> getPatterns() {
            return patterns;
        }
        
        public void setPatterns(List<Pattern> patterns) {
            this.patterns = patterns;
        }
    }
    
    public static class BaseTexture {
        @JsonProperty("fillColor")
        private String fillColor;
        
        @JsonProperty("darkenFactor")
        private Float darkenFactor;
        
        @JsonProperty("lightenFactor")
        private Float lightenFactor;
        
        public String getFillColor() {
            return fillColor;
        }
        
        public void setFillColor(String fillColor) {
            this.fillColor = fillColor;
        }
        
        public Float getDarkenFactor() {
            return darkenFactor;
        }
        
        public void setDarkenFactor(Float darkenFactor) {
            this.darkenFactor = darkenFactor;
        }
        
        public Float getLightenFactor() {
            return lightenFactor;
        }
        
        public void setLightenFactor(Float lightenFactor) {
            this.lightenFactor = lightenFactor;
        }
    }
    
    public static class FacialFeatures {
        @JsonProperty("expression")
        private String expression;
        
        @JsonProperty("eyes")
        private EyeFeatures eyes;
        
        @JsonProperty("nose")
        private NoseFeatures nose;
        
        @JsonProperty("mouth")
        private MouthFeatures mouth;
        
        @JsonProperty("blaze")
        private BlazeFeatures blaze;
        
        @JsonProperty("cheeks")
        private CheekFeatures cheeks;
        
        public String getExpression() {
            return expression;
        }
        
        public void setExpression(String expression) {
            this.expression = expression;
        }
        
        public EyeFeatures getEyes() {
            return eyes;
        }
        
        public void setEyes(EyeFeatures eyes) {
            this.eyes = eyes;
        }
        
        public NoseFeatures getNose() {
            return nose;
        }
        
        public void setNose(NoseFeatures nose) {
            this.nose = nose;
        }
        
        public MouthFeatures getMouth() {
            return mouth;
        }
        
        public void setMouth(MouthFeatures mouth) {
            this.mouth = mouth;
        }
        
        public BlazeFeatures getBlaze() {
            return blaze;
        }
        
        public void setBlaze(BlazeFeatures blaze) {
            this.blaze = blaze;
        }
        
        public CheekFeatures getCheeks() {
            return cheeks;
        }
        
        public void setCheeks(CheekFeatures cheeks) {
            this.cheeks = cheeks;
        }
    }
    
    public static class EyeFeatures {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("spacing")
        private String spacing;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("pupilColor")
        private String pupilColor;
        
        @JsonProperty("irisColor")
        private String irisColor;
        
        @JsonProperty("highlights")
        private List<Highlight> highlights;
        
        @JsonProperty("eyebrows")
        private EyebrowFeatures eyebrows;
        
        @JsonProperty("eyelashes")
        private List<Eyelash> eyelashes;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public String getSpacing() {
            return spacing;
        }
        
        public void setSpacing(String spacing) {
            this.spacing = spacing;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public String getPupilColor() {
            return pupilColor;
        }
        
        public void setPupilColor(String pupilColor) {
            this.pupilColor = pupilColor;
        }
        
        public String getIrisColor() {
            return irisColor;
        }
        
        public void setIrisColor(String irisColor) {
            this.irisColor = irisColor;
        }
        
        public List<Highlight> getHighlights() {
            return highlights;
        }
        
        public void setHighlights(List<Highlight> highlights) {
            this.highlights = highlights;
        }
        
        public EyebrowFeatures getEyebrows() {
            return eyebrows;
        }
        
        public void setEyebrows(EyebrowFeatures eyebrows) {
            this.eyebrows = eyebrows;
        }
        
        public List<Eyelash> getEyelashes() {
            return eyelashes;
        }
        
        public void setEyelashes(List<Eyelash> eyelashes) {
            this.eyelashes = eyelashes;
        }
    }
    
    public static class NoseFeatures {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("color")
        private String color;
        
        @JsonProperty("nostrils")
        private List<Nostril> nostrils;
        
        @JsonProperty("highlights")
        private List<Highlight> highlights;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
        
        public List<Nostril> getNostrils() {
            return nostrils;
        }
        
        public void setNostrils(List<Nostril> nostrils) {
            this.nostrils = nostrils;
        }
        
        public List<Highlight> getHighlights() {
            return highlights;
        }
        
        public void setHighlights(List<Highlight> highlights) {
            this.highlights = highlights;
        }
    }
    
    public static class MouthFeatures {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("color")
        private String color;
        
        @JsonProperty("expression")
        private String expression;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
        
        public String getExpression() {
            return expression;
        }
        
        public void setExpression(String expression) {
            this.expression = expression;
        }
    }
    
    public static class BlazeFeatures {
        @JsonProperty("enabled")
        private Boolean enabled;
        
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("width")
        private Integer width;
        
        @JsonProperty("length")
        private Integer length;
        
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("color")
        private String color;
        
        public Boolean getEnabled() {
            return enabled;
        }
        
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public Integer getWidth() {
            return width;
        }
        
        public void setWidth(Integer width) {
            this.width = width;
        }
        
        public Integer getLength() {
            return length;
        }
        
        public void setLength(Integer length) {
            this.length = length;
        }
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
    }
    
    public static class CheekFeatures {
        @JsonProperty("blush")
        private BlushFeatures blush;
        
        @JsonProperty("freckles")
        private List<Freckle> freckles;
        
        @JsonProperty("dimples")
        private List<Dimple> dimples;
        
        public BlushFeatures getBlush() {
            return blush;
        }
        
        public void setBlush(BlushFeatures blush) {
            this.blush = blush;
        }
        
        public List<Freckle> getFreckles() {
            return freckles;
        }
        
        public void setFreckles(List<Freckle> freckles) {
            this.freckles = freckles;
        }
        
        public List<Dimple> getDimples() {
            return dimples;
        }
        
        public void setDimples(List<Dimple> dimples) {
            this.dimples = dimples;
        }
    }
    
    public static class Pattern {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("shape")
        private String shape;
        
        @JsonProperty("color")
        private String color;
        
        @JsonProperty("positions")
        private List<Position> positions;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("opacity")
        private Integer opacity;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public String getShape() {
            return shape;
        }
        
        public void setShape(String shape) {
            this.shape = shape;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
        
        public List<Position> getPositions() {
            return positions;
        }
        
        public void setPositions(List<Position> positions) {
            this.positions = positions;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public Integer getOpacity() {
            return opacity;
        }
        
        public void setOpacity(Integer opacity) {
            this.opacity = opacity;
        }
    }
    
    public static class Size {
        @JsonProperty("width")
        private Integer width;
        
        @JsonProperty("height")
        private Integer height;
        
        public Integer getWidth() {
            return width;
        }
        
        public void setWidth(Integer width) {
            this.width = width;
        }
        
        public Integer getHeight() {
            return height;
        }
        
        public void setHeight(Integer height) {
            this.height = height;
        }
    }
    
    public static class Position {
        @JsonProperty("x")
        private Integer x;
        
        @JsonProperty("y")
        private Integer y;
        
        public Integer getX() {
            return x;
        }
        
        public void setX(Integer x) {
            this.x = x;
        }
        
        public Integer getY() {
            return y;
        }
        
        public void setY(Integer y) {
            this.y = y;
        }
    }
    
    public static class Highlight {
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("color")
        private String color;
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
    }
    
    public static class EyebrowFeatures {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("thickness")
        private Float thickness;
        
        @JsonProperty("color")
        private String color;
        
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public Float getThickness() {
            return thickness;
        }
        
        public void setThickness(Float thickness) {
            this.thickness = thickness;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
    }
    
    public static class Eyelash {
        @JsonProperty("startPosition")
        private Position startPosition;
        
        @JsonProperty("endPosition")
        private Position endPosition;
        
        @JsonProperty("color")
        private String color;
        
        public Position getStartPosition() {
            return startPosition;
        }
        
        public void setStartPosition(Position startPosition) {
            this.startPosition = startPosition;
        }
        
        public Position getEndPosition() {
            return endPosition;
        }
        
        public void setEndPosition(Position endPosition) {
            this.endPosition = endPosition;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
    }
    
    public static class Nostril {
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("color")
        private String color;
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
    }
    
    public static class BlushFeatures {
        @JsonProperty("enabled")
        private Boolean enabled;
        
        @JsonProperty("color")
        private String color;
        
        @JsonProperty("opacity")
        private Integer opacity;
        
        @JsonProperty("positions")
        private List<Position> positions;
        
        @JsonProperty("size")
        private Size size;
        
        public Boolean getEnabled() {
            return enabled;
        }
        
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
        
        public Integer getOpacity() {
            return opacity;
        }
        
        public void setOpacity(Integer opacity) {
            this.opacity = opacity;
        }
        
        public List<Position> getPositions() {
            return positions;
        }
        
        public void setPositions(List<Position> positions) {
            this.positions = positions;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
    }
    
    public static class Freckle {
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("color")
        private String color;
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public String getColor() {
            return color;
        }
        
        public void setColor(String color) {
            this.color = color;
        }
    }
    
    public static class Dimple {
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("depth")
        private Float depth;
        
        public Position getPosition() {
            return position;
        }
        
        public void setPosition(Position position) {
            this.position = position;
        }
        
        public Size getSize() {
            return size;
        }
        
        public void setSize(Size size) {
            this.size = size;
        }
        
        public Float getDepth() {
            return depth;
        }
        
        public void setDepth(Float depth) {
            this.depth = depth;
        }
    }
    
    public static class TextureAtlas {
        @JsonProperty("width")
        private int width;
        
        @JsonProperty("height")
        private int height;
        
        @JsonProperty("gridSize")
        private int gridSize;
        
        @JsonProperty("file")
        private String file;
        
        public int getWidth() {
            return width;
        }
        
        public void setWidth(int width) {
            this.width = width;
        }
        
        public int getHeight() {
            return height;
        }
        
        public void setHeight(int height) {
            this.height = height;
        }
        
        public int getGridSize() {
            return gridSize;
        }
        
        public void setGridSize(int gridSize) {
            this.gridSize = gridSize;
        }
        
        public String getFile() {
            return file;
        }
        
        public void setFile(String file) {
            this.file = file;
        }
    }
}