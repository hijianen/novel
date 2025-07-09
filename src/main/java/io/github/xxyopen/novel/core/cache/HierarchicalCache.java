package io.github.xxyopen.novel.core.cache;

import io.github.xxyopen.novel.core.constant.CacheConsts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;

public class HierarchicalCache implements Cache {

    private static final Logger logger = LoggerFactory.getLogger(HierarchicalCache.class);

    private final String name;
    private final Cache l1Cache; // Caffeine cache instance
    private final Cache l2Cache; // Redis cache instance
    // private final CacheConsts.CacheEnum cacheConfig; // May not be needed directly if L1/L2 already configured

    public HierarchicalCache(String name, Cache l1Cache, Cache l2Cache) {
        this.name = name;
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        // this.cacheConfig = cacheConfig;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public Object getNativeCache() {
        // Could return L1 or a composite. For simplicity, returning L1's native cache.
        return this.l1Cache.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        logger.trace("HierarchicalCache: Attempting to get key '{}' from cache '{}'", key, this.name);
        ValueWrapper valueWrapper = l1Cache.get(key);
        if (valueWrapper != null) {
            logger.trace("HierarchicalCache: L1 cache hit for key '{}' in cache '{}'", key, this.name);
            return valueWrapper;
        }
        logger.trace("HierarchicalCache: L1 cache miss for key '{}' in cache '{}'. Checking L2.", key, this.name);
        valueWrapper = l2Cache.get(key);
        if (valueWrapper != null) {
            logger.trace("HierarchicalCache: L2 cache hit for key '{}' in cache '{}'", key, this.name);
            Object value = valueWrapper.get();
            if (value != null) { // Should not be null if disableCachingNullValues is true for Redis
                logger.trace("HierarchicalCache: Populating L1 with L2 data for key '{}' in cache '{}'", key, this.name);
                l1Cache.put(key, value);
            }
            return valueWrapper;
        }
        logger.trace("HierarchicalCache: L2 cache miss for key '{}' in cache '{}'", key, this.name);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        logger.trace("HierarchicalCache: Attempting to get key '{}' of type '{}' from cache '{}'", key, type != null ? type.getName() : "null", this.name);
        T value = l1Cache.get(key, type);
        if (value != null) {
            logger.trace("HierarchicalCache: L1 cache hit for key '{}' (type: {}) in cache '{}'", key, type != null ? type.getName() : "null", this.name);
            return value;
        }
        logger.trace("HierarchicalCache: L1 cache miss for key '{}' (type: {}). Checking L2.", key, type != null ? type.getName() : "null", this.name);
        value = l2Cache.get(key, type);
        if (value != null) {
            logger.trace("HierarchicalCache: L2 cache hit for key '{}' (type: {}). Populating L1.", key, type != null ? type.getName() : "null", this.name);
            l1Cache.put(key, value);
            return value;
        }
        logger.trace("HierarchicalCache: L2 cache miss for key '{}' (type: {}) in cache '{}'", key, type != null ? type.getName() : "null", this.name);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        logger.trace("HierarchicalCache: Attempting to get key '{}' with valueLoader from cache '{}'", key, this.name);
        ValueWrapper wrapper = l1Cache.get(key);
        if (wrapper != null) {
            logger.trace("HierarchicalCache: L1 cache hit for key '{}' (valueLoader) in cache '{}'", key, this.name);
            return (T) wrapper.get();
        }

        logger.trace("HierarchicalCache: L1 cache miss for key '{}' (valueLoader). Checking L2.", key, this.name);
        wrapper = l2Cache.get(key);
        if (wrapper != null) {
            logger.trace("HierarchicalCache: L2 cache hit for key '{}' (valueLoader). Populating L1.", key, this.name);
            T valueFromL2 = (T) wrapper.get();
            if (valueFromL2 != null) { // Should not be null if disableCachingNullValues is true for Redis
                 l1Cache.put(key, valueFromL2);
            }
            return valueFromL2;
        }

        logger.trace("HierarchicalCache: L2 cache miss for key '{}' (valueLoader). Loading from source.", key, this.name);
        // Consider thundering herd protection here for production systems
        // For now, direct call to valueLoader
        T value;
        try {
            value = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, e);
        }

        if (value != null) { // Assuming underlying caches might not support nulls or we choose not to cache them.
            logger.trace("HierarchicalCache: Value loaded from source for key '{}'. Populating L2 and L1.", key, this.name);
            l2Cache.put(key, value);
            l1Cache.put(key, value);
        } else {
            // If value is null, and we want to cache nulls (e.g. as an indicator of "known null")
            // then the put calls would still happen. If not, they are skipped.
            // Spring's RedisCache by default does not support null values with `disableCachingNullValues=true`.
            // Caffeine supports null values.
            // For consistency, if Redis doesn't cache it, L1 probably shouldn't either,
            // unless L1 is explicitly configured to cache nulls and that's desired.
            // Current RedisCache config in HierarchicalCacheManager design disables null values.
            logger.trace("HierarchicalCache: Value loaded from source for key '{}' is null. Not caching.", key, this.name);
        }
        return value;
    }

    @Override
    public void put(Object key, Object value) {
        logger.trace("HierarchicalCache: Putting key '{}' into cache '{}'", key, this.name);
        if (value == null) {
            // Consistent with RedisCache not caching nulls by default in our setup.
            // Evicting ensures that if a null is explicitly put, it means "not present".
            logger.trace("HierarchicalCache: Value for key '{}' is null. Evicting from caches.", key, this.name);
            this.evict(key);
            return;
        }
        try {
            l2Cache.put(key, value);
            logger.trace("HierarchicalCache: Successfully put key '{}' into L2 cache '{}'", key, this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: Failed to put key '{}' into L2 cache '{}'. Error: {}", key, this.name, e.getMessage(), e);
            // Proceed to put into L1 for read availability, despite L2 failure.
        }
        try {
            l1Cache.put(key, value);
            logger.trace("HierarchicalCache: Successfully put key '{}' into L1 cache '{}'", key, this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: Failed to put key '{}' into L1 cache '{}'. Error: {}", key, this.name, e.getMessage(), e);
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        logger.trace("HierarchicalCache: Attempting putIfAbsent for key '{}' in cache '{}'", key, this.name);

        ValueWrapper existingL1 = l1Cache.get(key);
        if (existingL1 != null) {
            logger.trace("HierarchicalCache: putIfAbsent - Key '{}' found in L1. Returning existing.", key, this.name);
            return existingL1;
        }

        ValueWrapper existingL2 = l2Cache.get(key);
        if (existingL2 != null) {
            logger.trace("HierarchicalCache: putIfAbsent - Key '{}' found in L2. Populating L1 and returning existing.", key, this.name);
            Object valueFromL2 = existingL2.get();
            if (valueFromL2 != null) {
                l1Cache.put(key, valueFromL2);
            }
            return existingL2;
        }

        logger.trace("HierarchicalCache: putIfAbsent - Key '{}' not in L1 or L2. Putting new value.", key, this.name);
        if (value == null) {
             // If value is null, and we decided not to cache nulls (as in put method),
             // then we should not put it. Return null to indicate no existing value.
            logger.trace("HierarchicalCache: putIfAbsent - Value for key '{}' is null. Not putting.", key, this.name);
            return null;
        }

        // Attempt to put into L2. Note: Spring's RedisCache putIfAbsent might return the existing value if another thread put it.
        ValueWrapper l2PutResultWrapper = l2Cache.putIfAbsent(key, value);
        if (l2PutResultWrapper != null) { // Value was already present in L2 (possibly set by another thread)
            logger.trace("HierarchicalCache: putIfAbsent - L2 already contained key '{}'. Populating L1 with L2's value.", key, this.name);
            Object valueFromL2 = l2PutResultWrapper.get();
             if (valueFromL2 != null) {
                l1Cache.put(key, valueFromL2); // Ensure L1 has this concurrently set value
             }
            return l2PutResultWrapper; // Return the existing value from L2
        }

        // L2 did not have it, and we successfully put it (or L2's putIfAbsent is void and we assume success if no error)
        logger.trace("HierarchicalCache: putIfAbsent - Successfully put key '{}' into L2. Now putting into L1.", key, this.name);
        l1Cache.put(key, value); // Populate L1 with the new value
        logger.trace("HierarchicalCache: putIfAbsent - Successfully put key '{}' into L1.", key, this.name);
        return null; // As per Cache.putIfAbsent contract, return null if value was put.
    }


    @Override
    public void evict(Object key) {
        logger.trace("HierarchicalCache: Evicting key '{}' from cache '{}'", key, this.name);
        try {
            l2Cache.evict(key); // Evict from L2 first
            logger.trace("HierarchicalCache: Successfully evicted key '{}' from L2 cache '{}'", key, this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: Failed to evict key '{}' from L2 cache '{}'. Error: {}", key, this.name, e.getMessage(), e);
            // Proceed to evict from L1.
        }
        try {
            l1Cache.evict(key); // Then evict from L1
            logger.trace("HierarchicalCache: Successfully evicted key '{}' from L1 cache '{}'", key, this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: Failed to evict key '{}' from L1 cache '{}'. Error: {}", key, this.name, e.getMessage(), e);
        }
    }

    @Override
    public boolean evictIfPresent(Object key) {
        logger.trace("HierarchicalCache: evictIfPresent for key '{}' from cache '{}'", key, this.name);
        boolean l2Evicted = false;
        try {
            // Underlying Cache.evictIfPresent was added in Spring 5.3.
            // Assuming l2Cache is a modern Spring Cache instance.
            // If not, one might need to do a get then evict, which is not atomic.
            // For simplicity, directly calling. Add checks if using older Spring versions.
            l2Evicted = l2Cache.evictIfPresent(key);
             if (l2Evicted) logger.trace("HierarchicalCache: evictIfPresent - Key '{}' evicted from L2.", key, this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: evictIfPresent - Failed for L2 key '{}'. Error: {}", key, this.name, e.getMessage(), e);
        }
        boolean l1Evicted = false;
        try {
            l1Evicted = l1Cache.evictIfPresent(key);
            if (l1Evicted) logger.trace("HierarchicalCache: evictIfPresent - Key '{}' evicted from L1.", key, this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: evictIfPresent - Failed for L1 key '{}'. Error: {}", key, this.name, e.getMessage(), e);
        }
        return l1Evicted || l2Evicted;
    }


    @Override
    public void clear() {
        logger.info("HierarchicalCache: Clearing cache '{}'", this.name);
        try {
            l2Cache.clear(); // Clear L2 first
            logger.info("HierarchicalCache: Successfully cleared L2 cache '{}'", this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: Failed to clear L2 cache '{}'. Error: {}", this.name, e.getMessage(), e);
            // Proceed to clear L1.
        }
        try {
            l1Cache.clear(); // Then clear L1
            logger.info("HierarchicalCache: Successfully cleared L1 cache '{}'", this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: Failed to clear L1 cache '{}'. Error: {}", this.name, e.getMessage(), e);
        }
    }

    @Override
    public boolean invalidate() {
        logger.info("HierarchicalCache: Invalidating cache '{}'", this.name);
        boolean l2Invalidated = false;
        try {
            // Cache.invalidate() was added in Spring 5.3
            l2Invalidated = l2Cache.invalidate();
             if (l2Invalidated) logger.info("HierarchicalCache: invalidate - L2 cache '{}' invalidated.", this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: invalidate - Failed for L2 cache '{}'. Error: {}", this.name, e.getMessage(), e);
        }
        boolean l1Invalidated = false;
        try {
            l1Invalidated = l1Cache.invalidate();
            if (l1Invalidated) logger.info("HierarchicalCache: invalidate - L1 cache '{}' invalidated.", this.name);
        } catch (Exception e) {
            logger.error("HierarchicalCache: invalidate - Failed for L1 cache '{}'. Error: {}", this.name, e.getMessage(), e);
        }
        return l1Invalidated || l2Invalidated;
    }
}
