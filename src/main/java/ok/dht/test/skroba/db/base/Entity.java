package ok.dht.test.skroba.db.base;

import java.time.Instant;

public class Entity implements Comparable<Entity> {
    private final boolean tombstone;
    private final long timestamp;
    private final byte[] value;
    
    private byte[] serialized;
    
    public Entity(final byte[] value) {
        this(false, Instant.now()
                .toEpochMilli(), value);
    }
    
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    public Entity(final boolean tombstone, final long timestamp, final byte[] value) {
        this.tombstone = tombstone;
        this.timestamp = timestamp;
        this.value = value;
    }
    
    public Entity(final long timestamp, final byte[] value) {
        this(false, timestamp, value);
    }
    
    public static Entity tombstone(final long timestamp) {
        return new Entity(true, timestamp, new byte[0]);
    }
    
    public static Entity deserialize(final byte[] item) {
        final int metaPos = 1 + Long.BYTES;
        final int valueLength = item.length - metaPos;
        final boolean tombstone = item[0] == Byte.MIN_VALUE;
        final byte[] value = new byte[valueLength];
        long timestamp = 0;
        
        for (int i = 1; i < Long.BYTES + 1; i++) {
            timestamp <<= Byte.SIZE;
            timestamp |= (item[i] & 0xFF);
        }
        
        System.arraycopy(item, metaPos, value, 0, valueLength);
        
        return new Entity(tombstone, timestamp, value);
    }
    
    @Override
    public int compareTo(final Entity o) {
        return Long.compare(timestamp, o.timestamp);
    }
    
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    public byte[] serialize() {
        if (serialized != null) {
            return serialized.clone();
        }
        
        final byte[] result = new byte[value.length + Long.BYTES + 1];
        
        result[0] = isTombstone() ? Byte.MIN_VALUE : Byte.MAX_VALUE;
        
        long timestampLocal = this.timestamp;
        
        for (int i = Long.BYTES; i >= 1; i--) {
            result[i] = (byte) (timestampLocal & 0xFF);
            timestampLocal >>= Byte.SIZE;
        }
        
        System.arraycopy(value, 0, result, Long.BYTES + 1, value.length);
        
        serialized = result;
        
        return result;
    }
    
    public boolean isTombstone() {
        return tombstone;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public byte[] getValue() {
        return value.clone();
    }
}
