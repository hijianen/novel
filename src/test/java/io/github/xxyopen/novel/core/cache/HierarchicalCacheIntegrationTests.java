package io.github.xxyopen.novel.core.cache;

import io.github.xxyopen.novel.core.config.CacheConfig; // Assuming CacheConfig is where HierarchicalCacheManager is primary
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

// This is a conceptual integration test.
// For it to run, a Spring Boot environment with Redis (e.g., embedded or Testcontainers)
// and the necessary configurations (like CacheConfig making HierarchicalCacheManager primary) are needed.
// Also, the specific cache "integrationTestCache" would ideally be defined in CacheConsts.CacheEnum
// with appropriate (possibly short for testing) TTLs.

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = HierarchicalCacheIntegrationTests.TestConfig.class)
class HierarchicalCacheIntegrationTests {

    private static final Logger logger = LoggerFactory.getLogger(HierarchicalCacheIntegrationTests.class);

    public interface TestCacheableService {
        String getData(String key);
        String getDataWithLoader(String key);
        String updateData(String key, String value);
        void evictData(String key);
        int getGetDataCallCount();
        int getGetDataWithLoaderCallCount();
        void resetCallCounts();
        void primeDb(String key, String value);
        void clearDb();
    }

    @Service
    public static class TestCacheableServiceImpl implements TestCacheableService {
        private static final Logger log = LoggerFactory.getLogger(TestCacheableServiceImpl.class);
        public static final String TEST_CACHE_NAME = "integrationTestCache";
        private final Map<String, String> database = new HashMap<>();
        private int getDataCallCount = 0;
        private int getDataWithLoaderCallCount = 0;

        @Override
        @Cacheable(cacheNames = TEST_CACHE_NAME, key = "#key")
        public String getData(String key) {
            getDataCallCount++;
            log.info("Executing TestCacheableServiceImpl.getData (actual call) for key: {}", key);
            return database.get(key);
        }

        @Override
        @Cacheable(cacheNames = TEST_CACHE_NAME, key = "#key")
        public String getDataWithLoader(String key) {
            getDataWithLoaderCallCount++;
            log.info("Executing TestCacheableServiceImpl.getDataWithLoader (actual call) for key: {}", key);
            String value = "loadedValue:" + key;
            database.put(key, value);
            return value;
        }

        @Override
        @CachePut(cacheNames = TEST_CACHE_NAME, key = "#key")
        public String updateData(String key, String value) {
            log.info("Executing TestCacheableServiceImpl.updateData (actual call) for key: {}, value: {}", key, value);
            database.put(key, value);
            return value;
        }

        @Override
        @CacheEvict(cacheNames = TEST_CACHE_NAME, key = "#key")
        public void evictData(String key) {
            log.info("Executing TestCacheableServiceImpl.evictData (actual call) for key: {}", key);
        }

        @Override
        public int getGetDataCallCount() { return getDataCallCount; }
        @Override
        public int getGetDataWithLoaderCallCount() { return getDataWithLoaderCallCount; }
        @Override
        public void resetCallCounts() { getDataCallCount = 0; getDataWithLoaderCallCount = 0; }
        @Override
        public void primeDb(String key, String value) { database.put(key, value); }
        @Override
        public void clearDb() { database.clear(); }
    }

    @Configuration
    @EnableCaching // Enables Spring's caching capabilities
    @EnableAspectJAutoProxy // Ensures caching aspects are effective
    @Import(CacheConfig.class) // Import the main CacheConfig which should provide HierarchicalCacheManager
    static class TestConfig {
        // Provide the service bean for the test
        @Bean
        public TestCacheableService testCacheableService() {
            return new TestCacheableServiceImpl();
        }
    }

    @Autowired
    private TestCacheableService testService;

    @Autowired
    private CacheManager cacheManager; // Spring injects the @Primary CacheManager here

    @BeforeEach
    void setUpTest() {
        // Ensure the correct CacheManager is injected
        assertTrue(cacheManager instanceof HierarchicalCacheManager, "CacheManager should be an instance of HierarchicalCacheManager");

        // Clear database and reset call counts before each test
        testService.clearDb();
        testService.resetCallCounts();

        // Clear the specific test cache before each run
        Cache testCache = cacheManager.getCache(TestCacheableServiceImpl.TEST_CACHE_NAME);
        if (testCache != null) {
            testCache.clear();
        }
        logger.info("HierarchicalCacheIntegrationTests: Setup complete. Cache '{}' cleared.", TestCacheableServiceImpl.TEST_CACHE_NAME);
    }

    @AfterEach
    void tearDown() {
        Cache testCache = cacheManager.getCache(TestCacheableServiceImpl.TEST_CACHE_NAME);
        if (testCache != null) {
            testCache.clear();
        }
        testService.clearDb();
        logger.info("HierarchicalCacheIntegrationTests: Teardown complete. Cache '{}' cleared.", TestCacheableServiceImpl.TEST_CACHE_NAME);
    }

    @Test
    void testCachePopulationAndRetrieval_L1_L2_DB_Flow() {
        String key = "intTestKey1";
        String value = "intTestValue1";
        testService.primeDb(key, value);

        // --- First call: L1 miss, L2 miss, DB source method called. L1 & L2 populated. ---
        logger.info("IntegrationTest: First call for key '{}'", key);
        assertEquals(value, testService.getData(key), "Value should match after first call (from DB)");
        assertEquals(1, testService.getGetDataCallCount(), "Service's getData method should have been called once");

        Cache actualCache = cacheManager.getCache(TestCacheableServiceImpl.TEST_CACHE_NAME);
        assertNotNull(actualCache.get(key), "Cache should contain the key after DB call");
        assertEquals(value, actualCache.get(key).get(), "Cached value should match original value");

        // --- Second call: Should be L1 hit. DB source method NOT called. ---
        logger.info("IntegrationTest: Second call for key '{}'", key);
        assertEquals(value, testService.getData(key), "Value should match on second call (from L1 cache)");
        assertEquals(1, testService.getGetDataCallCount(), "Service's getData method should NOT be called again (L1 hit)");

        // --- To test L2 hit specifically (after L1 miss/expiry but before L2 expiry) ---
        // This would require either:
        // 1. Modifying HierarchicalCache to allow direct L1 eviction for a key (test hook).
        // 2. Setting a very short L1 TTL for "integrationTestCache" in CacheEnum and Thread.sleep().
        // 3. Directly interacting with the L1 Caffeine cache instance if accessible.
        // For this conceptual test, this specific scenario is hard to demonstrate without one of these.
        // If we assume L1 is somehow cleared but L2 is not:
        // (Simulated by clearing the whole HierarchicalCache and re-populating L2 only - not ideal)
        // For now, we'll skip the explicit L1-expiry-L2-hit test here.

        logger.info("IntegrationTest: Test for L1/L2/DB flow completed conceptually.");
    }

    @Test
    void testUpdateData_updatesCache() {
        String key = "updateKey";
        String initialValue = "initialValue";
        String updatedValue = "updatedValue";

        testService.primeDb(key, initialValue);

        // Initial load into cache
        logger.info("IntegrationTest: Initial load for updateKey '{}'", key);
        assertEquals(initialValue, testService.getData(key));
        assertEquals(1, testService.getGetDataCallCount());

        // Update the data using @CachePut
        logger.info("IntegrationTest: Updating data for updateKey '{}'", key);
        assertEquals(updatedValue, testService.updateData(key, updatedValue));

        // getDataCallCount should still be 1 as updateData is a different method
        assertEquals(1, testService.getGetDataCallCount());

        // Verify cache returns the updated value without calling the getData source method again
        logger.info("IntegrationTest: Getting data after update for updateKey '{}'", key);
        assertEquals(updatedValue, testService.getData(key));
        assertEquals(1, testService.getGetDataCallCount(), "Service's getData method should NOT be called (value from updated cache)");
    }

    @Test
    void testEvictData_removesFromCache() {
        String key = "evictKey";
        String value = "evictValue";
        testService.primeDb(key, value);

        // Load into cache
        logger.info("IntegrationTest: Initial load for evictKey '{}'", key);
        assertEquals(value, testService.getData(key));
        assertEquals(1, testService.getGetDataCallCount());

        Cache actualCache = cacheManager.getCache(TestCacheableServiceImpl.TEST_CACHE_NAME);
        assertNotNull(actualCache.get(key), "Cache should have the value before evict");

        // Evict data
        logger.info("IntegrationTest: Evicting data for evictKey '{}'", key);
        testService.evictData(key);
        assertNull(actualCache.get(key), "Cache should be null for the key after evict");

        // Try to get data again, should call the source method
        logger.info("IntegrationTest: Getting data after eviction for evictKey '{}'", key);
        assertEquals(value, testService.getData(key));
        assertEquals(2, testService.getGetDataCallCount(), "Service's getData method should be called again after eviction");
    }

    @Test
    void testGetDataWithLoader_loadsAndCaches() {
        String key = "loaderKeyIntTest";
        String expectedValue = "loadedValue:" + key;

        // First call: source method (getDataWithLoader) executes, value loaded and cached
        logger.info("IntegrationTest: First call for getDataWithLoader key '{}'", key);
        assertEquals(expectedValue, testService.getDataWithLoader(key));
        assertEquals(1, testService.getGetDataWithLoaderCallCount(), "getDataWithLoader method should be called once");

        Cache actualCache = cacheManager.getCache(TestCacheableServiceImpl.TEST_CACHE_NAME);
        assertNotNull(actualCache.get(key), "Cache should contain the key after loading");
        assertEquals(expectedValue, actualCache.get(key).get(), "Cached value should match loaded value");

        // Second call: value should come from cache, source method not executed again
        logger.info("IntegrationTest: Second call for getDataWithLoader key '{}'", key);
        assertEquals(expectedValue, testService.getDataWithLoader(key));
        assertEquals(1, testService.getGetDataWithLoaderCallCount(), "getDataWithLoader method should NOT be called again (cache hit)");
    }
}
