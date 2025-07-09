package io.github.xxyopen.novel.core.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HierarchicalCacheTests {

    @Mock
    private Cache l1Cache;
    @Mock
    private Cache l2Cache;

    private HierarchicalCache hierarchicalCache;
    private final String cacheName = "testCache";
    private final String testKey = "testKey";
    private final String testValue = "testValue";

    @BeforeEach
    void setUp() {
        hierarchicalCache = new HierarchicalCache(cacheName, l1Cache, l2Cache);
    }

    @Test
    void getName() {
        assertEquals(cacheName, hierarchicalCache.getName());
    }

    @Test
    void getNativeCache_returnsL1NativeCache() {
        Object nativeL1 = new Object();
        when(l1Cache.getNativeCache()).thenReturn(nativeL1);
        assertSame(nativeL1, hierarchicalCache.getNativeCache());
    }

    // Test get(Object key)
    @Test
    void get_l1Hit() {
        when(l1Cache.get(testKey)).thenReturn(new SimpleValueWrapper(testValue));
        Cache.ValueWrapper result = hierarchicalCache.get(testKey);
        assertNotNull(result);
        assertEquals(testValue, result.get());
        verify(l2Cache, never()).get(testKey);
    }

    @Test
    void get_l1Miss_l2Hit() {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(new SimpleValueWrapper(testValue));

        Cache.ValueWrapper result = hierarchicalCache.get(testKey);
        assertNotNull(result);
        assertEquals(testValue, result.get());
        verify(l1Cache).put(testKey, testValue); // Verify L1 population
    }

    @Test
    void get_l1Miss_l2Miss() {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(null);

        Cache.ValueWrapper result = hierarchicalCache.get(testKey);
        assertNull(result);
    }

    @Test
    void get_l1Miss_l2Hit_l2ReturnsNullValueWrapper() { // Should not happen if Redis doesn't store nulls
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(new SimpleValueWrapper(null)); // L2 stores a null

        Cache.ValueWrapper result = hierarchicalCache.get(testKey);
        assertNotNull(result); // Wrapper itself is not null
        assertNull(result.get()); // Value is null
        // In current HierarchicalCache, if L2 returns a wrapper with null, L1 is populated with null.
        // If Redis is configured with disableCachingNullValues=true, this path for l2Cache.get() returning a null value in wrapper is less likely.
        // If it does happen (e.g. L2 is some other cache type that stores nulls), L1 would cache it.
        verify(l1Cache).put(testKey, null);
    }


    // Test get(Object key, Class<T> type)
    @Test
    void getWithType_l1Hit() {
        when(l1Cache.get(testKey, String.class)).thenReturn(testValue);
        String result = hierarchicalCache.get(testKey, String.class);
        assertEquals(testValue, result);
        verify(l2Cache, never()).get(testKey, String.class);
    }

    @Test
    void getWithType_l1Miss_l2Hit() {
        when(l1Cache.get(testKey, String.class)).thenReturn(null);
        when(l2Cache.get(testKey, String.class)).thenReturn(testValue);

        String result = hierarchicalCache.get(testKey, String.class);
        assertEquals(testValue, result);
        verify(l1Cache).put(testKey, testValue); // Verify L1 population
    }

    @Test
    void getWithType_l1Miss_l2Miss() {
        when(l1Cache.get(testKey, String.class)).thenReturn(null);
        when(l2Cache.get(testKey, String.class)).thenReturn(null);

        String result = hierarchicalCache.get(testKey, String.class);
        assertNull(result);
    }

    // Test get(Object key, Callable<T> valueLoader)
    @Test
    @SuppressWarnings("unchecked")
    void getWithValueLoader_l1Hit() throws Exception {
        when(l1Cache.get(testKey)).thenReturn(new SimpleValueWrapper(testValue));
        Callable<String> valueLoader = mock(Callable.class);

        String result = hierarchicalCache.get(testKey, valueLoader);
        assertEquals(testValue, result);
        verify(valueLoader, never()).call();
        verify(l2Cache, never()).get(testKey);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getWithValueLoader_l1Miss_l2Hit() throws Exception {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(new SimpleValueWrapper(testValue));
        Callable<String> valueLoader = mock(Callable.class);

        String result = hierarchicalCache.get(testKey, valueLoader);
        assertEquals(testValue, result);
        verify(l1Cache).put(testKey, testValue); // L1 population
        verify(valueLoader, never()).call();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getWithValueLoader_l1Miss_l2Miss_loadSuccess() throws Exception {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(null);
        Callable<String> valueLoader = mock(Callable.class);
        when(valueLoader.call()).thenReturn(testValue);

        String result = hierarchicalCache.get(testKey, valueLoader);
        assertEquals(testValue, result);
        verify(valueLoader).call();
        verify(l2Cache).put(testKey, testValue); // L2 population
        verify(l1Cache).put(testKey, testValue); // L1 population
    }

    @Test
    @SuppressWarnings("unchecked")
    void getWithValueLoader_l1Miss_l2Miss_loadReturnsNull() throws Exception {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(null);
        Callable<String> valueLoader = mock(Callable.class);
        when(valueLoader.call()).thenReturn(null); // Loader returns null

        String result = hierarchicalCache.get(testKey, valueLoader);
        assertNull(result);
        verify(valueLoader).call();
        // Verify L2 and L1 are NOT populated if loader returns null, based on current HierarchicalCache logic in implementation
        verify(l2Cache, never()).put(eq(testKey), any());
        verify(l1Cache, never()).put(eq(testKey), any());
    }


    @Test
    @SuppressWarnings("unchecked")
    void getWithValueLoader_loaderThrowsException() throws Exception {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(null);
        Callable<String> valueLoader = mock(Callable.class);
        when(valueLoader.call()).thenThrow(new RuntimeException("Load failed"));

        assertThrows(Cache.ValueRetrievalException.class, () -> {
            hierarchicalCache.get(testKey, valueLoader);
        });
    }

    // Test put(Object key, Object value)
    @Test
    void put_successful() {
        hierarchicalCache.put(testKey, testValue);
        verify(l2Cache).put(testKey, testValue); // L2 first
        verify(l1Cache).put(testKey, testValue); // Then L1
    }

    @Test
    void put_nullValue_evicts() {
        hierarchicalCache.put(testKey, null);
        // Based on current logic, putting null should call evict
        verify(l2Cache).evict(testKey);
        verify(l1Cache).evict(testKey);
        verify(l2Cache, never()).put(eq(testKey), isNull());
        verify(l1Cache, never()).put(eq(testKey), isNull());
    }


    @Test
    void put_l2Fails_l1StillCalled() {
        doThrow(new RuntimeException("L2 put failed")).when(l2Cache).put(testKey, testValue);
        hierarchicalCache.put(testKey, testValue);
        verify(l2Cache).put(testKey, testValue);
        verify(l1Cache).put(testKey, testValue); // L1 should still be called
    }

    // Test evict(Object key)
    @Test
    void evict_successful() {
        hierarchicalCache.evict(testKey);
        verify(l2Cache).evict(testKey); // L2 first
        verify(l1Cache).evict(testKey); // Then L1
    }

    @Test
    void evict_l2Fails_l1StillCalled() {
        doThrow(new RuntimeException("L2 evict failed")).when(l2Cache).evict(testKey);
        hierarchicalCache.evict(testKey);
        verify(l2Cache).evict(testKey);
        verify(l1Cache).evict(testKey); // L1 should still be called
    }

    // Test putIfAbsent
    @Test
    void putIfAbsent_keyInL1() {
        when(l1Cache.get(testKey)).thenReturn(new SimpleValueWrapper("existingL1Value"));

        Cache.ValueWrapper result = hierarchicalCache.putIfAbsent(testKey, testValue);

        assertNotNull(result);
        assertEquals("existingL1Value", result.get());
        verify(l2Cache, never()).get(any());
        verify(l1Cache, never()).put(any(), any());
        verify(l2Cache, never()).put(any(), any());
         verify(l2Cache, never()).putIfAbsent(any(), any());
    }

    @Test
    void putIfAbsent_keyInL2_notInL1() {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(new SimpleValueWrapper("existingL2Value"));

        Cache.ValueWrapper result = hierarchicalCache.putIfAbsent(testKey, testValue);

        assertNotNull(result);
        assertEquals("existingL2Value", result.get());
        verify(l1Cache).put(testKey, "existingL2Value"); // L1 populated
        verify(l2Cache, never()).putIfAbsent(any(), any());
        verify(l2Cache, never()).put(any(),any());
    }

    @Test
    void putIfAbsent_keyNotInL1OrL2_valuePut() {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(null);
        when(l2Cache.putIfAbsent(testKey, testValue)).thenReturn(null);

        Cache.ValueWrapper result = hierarchicalCache.putIfAbsent(testKey, testValue);

        assertNull(result);
        verify(l2Cache).putIfAbsent(testKey, testValue);
        verify(l1Cache).put(testKey, testValue);
    }

    @Test
    void putIfAbsent_keyNotInL1OrL2_valueIsNull() {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(null);

        Cache.ValueWrapper result = hierarchicalCache.putIfAbsent(testKey, null);

        assertNull(result);
        verify(l2Cache, never()).putIfAbsent(any(), any());
        verify(l1Cache, never()).put(any(), any());
    }

    @Test
    void putIfAbsent_concurrentPutInL2() {
        when(l1Cache.get(testKey)).thenReturn(null);
        when(l2Cache.get(testKey)).thenReturn(null);
        when(l2Cache.putIfAbsent(testKey, testValue)).thenReturn(new SimpleValueWrapper("concurrentValue"));

        Cache.ValueWrapper result = hierarchicalCache.putIfAbsent(testKey, testValue);

        assertNotNull(result);
        assertEquals("concurrentValue", result.get());
        verify(l1Cache).put(testKey, "concurrentValue");
    }


    // Test clear()
    @Test
    void clear_successful() {
        hierarchicalCache.clear();
        verify(l2Cache).clear(); // L2 first
        verify(l1Cache).clear(); // Then L1
    }

    @Test
    void clear_l2Fails_l1StillCalled() {
        doThrow(new RuntimeException("L2 clear failed")).when(l2Cache).clear();
        hierarchicalCache.clear();
        verify(l2Cache).clear();
        verify(l1Cache).clear(); // L1 should still be called
    }
}
