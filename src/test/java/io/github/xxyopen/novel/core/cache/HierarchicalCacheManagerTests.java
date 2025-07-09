package io.github.xxyopen.novel.core.cache;

import io.github.xxyopen.novel.core.constant.CacheConsts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.cache.Cache;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HierarchicalCacheManagerTests {

    @Mock
    private RedisConnectionFactory redisConnectionFactory; // Mocked, not deeply used in these unit tests

    private HierarchicalCacheManager hierarchicalCacheManager;

    // Define some CacheEnum constants directly for test isolation, or use actuals if preferred
    private final CacheConsts.CacheEnum bookInfoCacheEnum =
        new CacheConsts.CacheEnum(0, "bookInfoCache", 3600, 100);
    private final CacheConsts.CacheEnum userInfoCacheEnum =
        new CacheConsts.CacheEnum(2, "userInfoCache", 7200, 50);
    private final List<CacheConsts.CacheEnum> cacheEnumValues = Arrays.asList(bookInfoCacheEnum, userInfoCacheEnum);

    @BeforeEach
    void setUp() {
        hierarchicalCacheManager = new HierarchicalCacheManager(redisConnectionFactory, cacheEnumValues);
    }

    @Test
    void getCache_returnsHierarchicalCacheInstanceForPreConfiguredCache() {
        Cache cache = hierarchicalCacheManager.getCache("bookInfoCache");
        assertNotNull(cache, "Cache 'bookInfoCache' should not be null");
        assertTrue(cache instanceof HierarchicalCache, "Cache should be an instance of HierarchicalCache");
        assertEquals("bookInfoCache", cache.getName(), "Cache name should match");

        Cache cache2 = hierarchicalCacheManager.getCache("userInfoCache");
        assertNotNull(cache2, "Cache 'userInfoCache' should not be null");
        assertTrue(cache2 instanceof HierarchicalCache, "Cache should be an instance of HierarchicalCache");
        assertEquals("userInfoCache", cache2.getName(), "Cache name should match");

        // Getting the same cache should return the same instance due to internal cacheMap
        assertSame(cache, hierarchicalCacheManager.getCache("bookInfoCache"), "Should return same instance for same name");
    }

    @Test
    void getCache_returnsHierarchicalCacheForDynamicallyRequestedCache() {
        // This relies on the default behavior of Spring's SimpleCacheManager (for Caffeine)
        // and RedisCacheManager to create caches on demand if not pre-configured.
        Cache dynamicCache = hierarchicalCacheManager.getCache("dynamicTestCacheName");
        assertNotNull(dynamicCache, "Dynamically requested cache should not be null");
        assertTrue(dynamicCache instanceof HierarchicalCache, "Dynamic cache should be HierarchicalCache");
        assertEquals("dynamicTestCacheName", dynamicCache.getName(), "Dynamic cache name should match");
    }


    @Test
    void getCacheNames_returnsAllPreConfiguredCacheNames() {
        Collection<String> cacheNames = hierarchicalCacheManager.getCacheNames();
        assertNotNull(cacheNames, "Cache names collection should not be null");
        assertEquals(2, cacheNames.size(), "Should be 2 pre-configured cache names");
        assertTrue(cacheNames.contains("bookInfoCache"), "Should contain 'bookInfoCache'");
        assertTrue(cacheNames.contains("userInfoCache"), "Should contain 'userInfoCache'");
    }

    @Test
    void constructor_handlesNullCacheEnumValuesGracefully() {
        // HierarchicalCacheManager's constructor has a check for isEmpty, not null directly for list.
        // Passing null would cause stream().collect() to NPE if not handled before.
        // Let's test with an empty list, which is handled.
        HierarchicalCacheManager manager = new HierarchicalCacheManager(redisConnectionFactory, null); // Passing null to see
        assertNotNull(manager.getCacheNames(), "Cache names should be empty list, not null");
        assertTrue(manager.getCacheNames().isEmpty(), "Cache names should be empty for null enum list");

        Cache dynamic = manager.getCache("someCacheFromNullEnumManager");
        assertNotNull(dynamic, "Dynamic cache creation should still work");
        assertTrue(dynamic instanceof HierarchicalCache);
    }

    @Test
    void constructor_handlesEmptyCacheEnumValuesGracefully() {
        HierarchicalCacheManager manager = new HierarchicalCacheManager(redisConnectionFactory, Collections.emptyList());
        assertNotNull(manager.getCacheNames(), "Cache names should be empty list, not null");
        assertTrue(manager.getCacheNames().isEmpty(), "Cache names should be empty for empty enum list");

        Cache dynamic = manager.getCache("someCacheFromEmptyEnumManager");
        assertNotNull(dynamic, "Dynamic cache creation should still work with empty enum list");
        assertTrue(dynamic instanceof HierarchicalCache);
    }

    @Test
    void constructor_throwsIfRedisConnectionFactoryIsNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            new HierarchicalCacheManager(null, cacheEnumValues);
        }, "Constructor should throw IllegalArgumentException if RedisConnectionFactory is null");
    }
}
