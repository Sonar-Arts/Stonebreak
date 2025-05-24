package com.stonebreak;

import java.util.Objects;

public class ItemStack {

    // Unified interface for item/block types that can be in an ItemStack
    public interface CraftableIdentifier {
        String getName();
        int getMaxStackSize();
        boolean isAir(); // To check for 'empty' types
    }

    // Adapters for existing BlockType and ItemType enums
    public static class BlockTypeAdapter implements CraftableIdentifier {
        private final BlockType blockType;

        public BlockTypeAdapter(BlockType blockType) {
            this.blockType = blockType;
        }

        public BlockType getWrappedType() { return blockType; }

        @Override
        public String getName() {
            return blockType.getName();
        }

        @Override
        public int getMaxStackSize() {
            return blockType.getMaxStackSize();
        }

        @Override
        public boolean isAir() {
            return blockType == BlockType.AIR;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockTypeAdapter that = (BlockTypeAdapter) o;
            return blockType == that.blockType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockType);
        }
    }

    public static class ItemTypeAdapter implements CraftableIdentifier {
        private final ItemType itemType;

        public ItemTypeAdapter(ItemType itemType) {
            this.itemType = itemType;
        }

        public ItemType getWrappedType() { return itemType; }

        @Override
        public String getName() {
            return itemType.getDisplayName();
        }

        @Override
        public int getMaxStackSize() {
            return itemType.getMaxStackSize();
        }
        
        @Override
        public boolean isAir() {
            return false; // ItemType has no "AIR" equivalent, check for null identifier for empty items.
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemTypeAdapter that = (ItemTypeAdapter) o;
            return itemType == that.itemType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemType);
        }
    }

    private CraftableIdentifier identifier;
    private int count;

    // Public constructor for BlockType-based ItemStacks
    public ItemStack(BlockType blockType, int count) {
        if (blockType == null) {
            throw new IllegalArgumentException("BlockType cannot be null.");
        }
        if (blockType == BlockType.AIR || count <= 0) { // Air blocks are always empty or count <= 0 effectively makes it empty
            this.identifier = new BlockTypeAdapter(BlockType.AIR);
            this.count = 0;
        } else {
            this.identifier = new BlockTypeAdapter(blockType);
            this.count = count;
        }
    }

    // Public constructor for ItemType-based ItemStacks
    public ItemStack(ItemType itemType, int count) {
        if (itemType == null) {
            throw new IllegalArgumentException("ItemType cannot be null.");
        }
        if (count <= 0) { // Count <= 0 effectively makes it empty
            this.identifier = null;
            this.count = 0;
        } else {
            this.identifier = new ItemTypeAdapter(itemType);
            this.count = count;
        }
    }
    
    // Private constructor for internal use (e.g., ItemStack.empty() or copying), allows direct identifier setting
    private ItemStack(CraftableIdentifier identifier, int count, boolean isInitialEmpty) {
        if (isInitialEmpty) {
            this.identifier = null;
            this.count = 0;
        } else {
            this.identifier = identifier;
            this.count = count;
        }
    }

    public static ItemStack empty() {
        return new ItemStack(null, 0, true); // Represents a truly empty slot
    }

    public boolean isBlock() {
        return identifier instanceof BlockTypeAdapter;
    }

    public boolean isItem() {
        return identifier instanceof ItemTypeAdapter;
    }

    public BlockType getBlockType() {
        if (isBlock()) {
            return ((BlockTypeAdapter) identifier).getWrappedType();
        }
        return null;
    }

    public int getBlockTypeId() {
        if (isBlock()) {
            BlockType block = getBlockType();
            return block != null ? block.getId() : -1;
        }
        return -1;
    }

    public ItemType getItemType() {
        if (isItem()) {
            return ((ItemTypeAdapter) identifier).getWrappedType();
        }
        return null;
    }
    
    public CraftableIdentifier getIdentifier() { // New getter for the identifier itself
        return identifier;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        if (identifier != null && identifier.isAir()) {
            this.count = 0; // Air always has 0 count
        } else if (count <= 0) {
            clearToEmptyState(); // Becomes empty if count is zero or less
        } else {
            this.count = count;
        }
    }
    
    private void clearToEmptyState() {
        this.identifier = null; // Null out identifier for empty stack
        this.count = 0;
    }

    public void incrementCount(int amount) {
        if (isEmpty() || amount <= 0) return; // Can't increment an empty stack or by non-positive amount
        this.count += amount;
        // No upper limit check here, assuming maxStackSize is handled externally if needed.
    }

    public void decrementCount(int amount) {
        if (isEmpty() || amount <= 0) return; // Can't decrement an empty stack or by non-positive amount
        this.count -= amount;
        if (this.count <= 0) {
            clearToEmptyState(); // Make it empty if count drops to 0 or below
        }
    }

    public boolean isEmpty() {
        // An ItemStack is empty if its count is 0 or less, OR if it has no identifier (e.g. from empty()).
        // identifier.isAir() is used specifically for BlockType.AIR which conceptually can't hold a count > 0.
        return (count <= 0) || (identifier == null) || (identifier.isAir() && count == 0);
    }


    public int getMaxStackSize() {
        return identifier != null ? identifier.getMaxStackSize() : 1; // Default to 1 if no identifier (e.g. empty)
    }

    public ItemStack copy() {
        if (isEmpty()) {
            return ItemStack.empty();
        }
        return new ItemStack(this.identifier, this.count, false); // Explicitly not an initial empty state
    }
    
    public ItemStack copy(int newCount) {
        if (newCount <= 0 || this.identifier == null || (this.identifier.isAir() && newCount > 0)) { // If trying to make AIR stack with count > 0
            return ItemStack.empty();
        }
        return new ItemStack(this.identifier, newCount, false); // Explicitly not an initial empty state
    }

    public void clear() {
        clearToEmptyState();
    }

    public String getDisplayName() {
        if (isEmpty()) {
            return "Empty";
        }
        return identifier.getName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemStack that = (ItemStack) o;

        // Both empty stacks are equal
        if (this.isEmpty() && that.isEmpty()) {
            return true;
        }
        // One is empty, other is not (an empty stack doesn't equal a stack of 0 items of a specific type if the types are different)
        if (this.isEmpty() || that.isEmpty()) {
            return false;
        }

        // Compare by identifier, ignoring count for type equality
        return Objects.equals(this.identifier, that.identifier);
    }

    public boolean equalsTypeAndIgnoreCount(ItemStack other) {
        // This method becomes redundant as the default equals method now compares based on type only
        // The previous definition of equals ignored count and focused on type, which is what this method now duplicates.
        // It's effectively the same as calling .equals() given the current definition.
        // Kept for backward compatibility, but might be considered for removal/refactor elsewhere.
        return this.equals(other);
    }

    @Override
    public int hashCode() {
        // Hash code based on the identifier only, ignoring count.
        // If empty (identifier is null or AIR and count 0), hash code can be 0 or some constant
        // to be consistent with equals where all empty stacks are equal.
        if (isEmpty()) { // All empty stacks should have same hash to be consistent with equals
            return 0; // A consistent hash for empty stacks
        }
        return Objects.hash(identifier);
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "ItemStack{EMPTY, count=0}";
        }
        String typeName = identifier.getName();
        String type = isBlock() ? "Block" : "Item";
        return "ItemStack{" +
                "type=" + typeName +
                ", count=" + count +
                ", is" + type + "=" + true + // Explicitly state if it's a Block or Item
                '}';
    }
}