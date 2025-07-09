package io.github.xxyopen.novel.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.xxyopen.novel.core.constant.CacheConsts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class HierarchicalCacheManager implements CacheManager {

    private static final Logger logger = LoggerFactory.getLogger(HierarchicalCacheManager.class);

    private final ConcurrentMap<String, Cache> cacheMap = new ConcurrentHashMap<>(16);
    private final Map<String, CacheConsts.CacheEnum> cacheConfigurations;

    private final CacheManager caffeineCacheManagerDelegate;
    private final CacheManager redisCacheManagerDelegate;

    public HierarchicalCacheManager(RedisConnectionFactory redisConnectionFactory, List<CacheConsts.CacheEnum> cacheEnumValues) {
        if (redisConnectionFactory == null) {
            throw new IllegalArgumentException("RedisConnectionFactory cannot be null");
        }
        if (CollectionUtils.isEmpty(cacheEnumValues)) {
            logger.warn("No CacheEnum values provided to HierarchicalCacheManager. No caches will be pre-configured.");
            this.cacheConfigurations = Collections.emptyMap();
        } else {
            this.cacheConfigurations = cacheEnumValues.stream()
                .collect(Collectors.toConcurrentMap(CacheConsts.CacheEnum::getName, v -> v));
        }

        this.caffeineCacheManagerDelegate = buildCaffeineCacheManager(cacheEnumValues);
        this.redisCacheManagerDelegate = buildRedisCacheManager(redisConnectionFactory, cacheEnumValues);

        // Initialize internal caches for known names
        if (!CollectionUtils.isEmpty(cacheEnumValues)) {
            for (CacheConsts.CacheEnum cacheEnum : cacheEnumValues) {
                this.cacheMap.put(cacheEnum.getName(), createHierarchicalCache(cacheEnum.getName()));
            }
        }
        logger.info("HierarchicalCacheManager initialized with {} pre-configured caches.", this.cacheMap.size());
    }

    private SimpleCacheManager buildCaffeineCacheManager(List<CacheConsts.CacheEnum> cacheEnumValues) {
        SimpleCacheManager manager = new SimpleCacheManager();
        if (!CollectionUtils.isEmpty(cacheEnumValues)) {
            List<CaffeineCache> caches = new ArrayList<>();
            for (CacheConsts.CacheEnum c : cacheEnumValues) {
                Caffeine<Object, Object> caffeineBuilder = Caffeine.newBuilder()
                    .recordStats()
                    .maximumSize(c.getMaxSize() > 0 ? c.getMaxSize() : CacheConsts.CacheEnum.HOME_BOOK_CACHE.getMaxSize()); // Default max size if not set
                if (c.getTtl() > 0) {
                    caffeineBuilder.expireAfterWrite(Duration.ofSeconds(c.getTtl()));
                }
                caches.add(new CaffeineCache(c.getName(), caffeineBuilder.build()));
                logger.debug("HierarchicalCacheManager: Configured Caffeine cache named '{}' with TTL {}s, MaxSize {}", c.getName(), c.getTtl(), c.getMaxSize());
            }
            manager.setCaches(caches);
        }
        return manager;
    }

    private RedisCacheManager buildRedisCacheManager(RedisConnectionFactory connectionFactory, List<CacheConsts.CacheEnum> cacheEnumValues) {
        RedisCacheWriter redisCacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory);

        // Default config for caches not explicitly defined in CacheEnum or for general use
        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .prefixCacheNameWith(CacheConsts.REDIS_CACHE_PREFIX);

        Map<String, RedisCacheConfiguration> initialCacheConfigurations = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(cacheEnumValues)) {
            for (CacheConsts.CacheEnum c : cacheEnumValues) {
                RedisCacheConfiguration redisCfg = RedisCacheConfiguration.defaultCacheConfig()
                    .disableCachingNullValues()
                    .prefixCacheNameWith(CacheConsts.REDIS_CACHE_PREFIX);
                if (c.getTtl() > 0) {
                    redisCfg = redisCfg.entryTtl(Duration.ofSeconds(c.getTtl()));
                } else {
                    // Potentially "cache forever" if TTL is 0, depending on RedisCacheManager interpretation
                    // Or use a very long default TTL if "forever" is not desired.
                    // For now, rely on RedisCacheManager's default for no TTL (which might mean no expiry)
                }
                initialCacheConfigurations.put(c.getName(), redisCfg);
                logger.debug("HierarchicalCacheManager: Configured Redis cache named '{}' with TTL {}s", c.getName(), c.getTtl());
            }
        }

        RedisCacheManager redisManager = RedisCacheManager.builder(redisCacheWriter)
            .cacheDefaults(defaultCacheConfig)
            .withInitialCacheConfigurations(initialCacheConfigurations)
            .transactionAware() // Optional: if cache operations should be part of transactions
            .build();

        // RedisCacheManager.initializeCaches() is called internally on build() for recent Spring versions.
        // If using an older version, you might need to call it explicitly.
        return redisManager;
    }


    private HierarchicalCache createHierarchicalCache(String name) {
        Cache l1Cache = this.caffeineCacheManagerDelegate.getCache(name);
        Cache l2Cache = this.redisCacheManagerDelegate.getCache(name);

        if (l1Cache == null) {
            logger.warn("HierarchicalCacheManager: L1 (Caffeine) cache named '{}' could not be created or found by delegate. This is unexpected if pre-configured.", name);
            // Fallback: create a default Caffeine cache on the fly for L1? Or throw?
            // For now, let's assume it should have been created by buildCaffeineCacheManager.
            // If it's truly dynamic, buildCaffeineCacheManager would need to support on-the-fly creation.
            throw new IllegalArgumentException("L1 cache '" + name + "' not found in Caffeine delegate. Ensure it's in CacheEnum.");
        }
        if (l2Cache == null) {
            logger.warn("HierarchicalCacheManager: L2 (Redis) cache named '{}' could not be created or found by delegate. This is unexpected if pre-configured.", name);
            // Similar to L1, RedisCacheManager should have created it.
            throw new IllegalArgumentException("L2 cache '" + name + "' not found in Redis delegate. Ensure it's in CacheEnum.");
        }

        // CacheConsts.CacheEnum config = this.cacheConfigurations.get(name);
        // The config isn't strictly needed by HierarchicalCache constructor if L1/L2 are already configured with TTL/MaxSize.
        return new HierarchicalCache(name, l1Cache, l2Cache);
    }

    @Override
    public Cache getCache(String name) {
        return this.cacheMap.computeIfAbsent(name, cacheName -> {
            logger.info("HierarchicalCacheManager: Cache named '{}' was not pre-configured. Creating dynamically (if supported by delegates) or failing.", cacheName);
            // This part handles caches requested at runtime that were not in CacheEnum.
            // The current delegate builders are based on CacheEnum, so they won't find these.
            // For truly dynamic caches, the delegate managers would need to be configured to allow dynamic creation.
            // For now, this will likely fail if 'name' is not in CacheEnum due to createHierarchicalCache's checks.
            // A more robust dynamic creation would involve:
            // 1. caffeineCacheManagerDelegate.getCache(name) would need to create one if absent.
            // 2. redisCacheManagerDelegate.getCache(name) would need to create one if absent (RedisCacheManager can do this if allowRuntimeCacheCreation is true).
            // This is a simplification: assuming all used cache names are in CacheEnum.
            CacheConsts.CacheEnum config = this.cacheConfigurations.get(cacheName);
            if (config == null) {
                 logger.warn("HierarchicalCacheManager: No configuration found in CacheEnum for dynamically requested cache '{}'. Using default delegate behavior.", cacheName);
                 // Attempt to get from delegates - they might create with defaults if configured to do so
                 Cache l1 = caffeineCacheManagerDelegate.getCache(cacheName); // SimpleCacheManager creates on demand
                 Cache l2 = redisCacheManagerDelegate.getCache(cacheName); // RedisCacheManager creates on demand if allowRuntimeCacheCreation = true (default)
                 if (l1 != null && l2 != null) {
                    return new HierarchicalCache(cacheName, l1, l2);
                 } else {
                    logger.error("HierarchicalCacheManager: Could not dynamically create both L1 and L2 for cache '{}'", cacheName);
                    return null; // Or throw
                 }
            }
            return createHierarchicalCache(cacheName);
        });
    }

    @Override
    public Collection<String> getCacheNames() {
        // Returns names of pre-configured caches.
        // If dynamic creation is fully supported, this might also need to reflect dynamically created ones.
        return Collections.unmodifiableSet(this.cacheConfigurations.keySet());
    }
}
