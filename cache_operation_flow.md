# Hierarchical Cache Operation Flow

This document describes the operational flow of the implemented hierarchical caching system, which uses an L1 (Caffeine) cache and an L2 (Redis) cache. The `HierarchicalCacheManager` is the primary Spring `CacheManager`, and `HierarchicalCache` instances orchestrate the interactions.

## 1. Cache Read Operation (`@Cacheable` or `cache.get()`)

When a method annotated with `@Cacheable` is called, or `cache.get(key)` / `cache.get(key, valueLoader)` is invoked:

1.  **L1 (Caffeine) Lookup**:
    *   The `HierarchicalCache` first attempts to retrieve the value from its L1 Caffeine cache instance using the specified key.
    *   **L1 Hit**: If the value is found in L1:
        *   The value is returned immediately.
        *   No further interaction with L2 or the data source (e.g., database) occurs.
        *   *Flow: Request -> HierarchicalCache -> L1 (Hit) -> Response*

2.  **L1 Miss, L2 (Redis) Lookup**:
    *   If the value is not found in L1 (L1 miss):
        *   The `HierarchicalCache` attempts to retrieve the value from its L2 Redis cache instance.
    *   **L2 Hit**: If the value is found in L2:
        *   The value retrieved from L2 is then **populated into the L1 (Caffeine) cache** for faster access on subsequent requests.
        *   The value is returned.
        *   No interaction with the data source occurs.
        *   *Flow: Request -> HierarchicalCache -> L1 (Miss) -> L2 (Hit) -> L1 (Populate) -> Response*

3.  **L1 Miss, L2 Miss, Data Source Lookup (`valueLoader.call()` or method body execution)**:
    *   If the value is not found in L1 or L2 (both are misses):
        *   The `HierarchicalCache` (if using `get(key, valueLoader)`) or Spring's caching aspect (if using `@Cacheable` on a method) will trigger the loading of the value from the original data source. This usually means:
            *   Executing the body of the `@Cacheable` annotated method.
            *   Or, calling the `Callable valueLoader` provided to `cache.get()`.
    *   **Data Loaded**: Once the value is successfully loaded from the data source:
        *   The loaded value is **populated into the L2 (Redis) cache**.
        *   The loaded value is then **populated into the L1 (Caffeine) cache**.
        *   The value is returned.
        *   *Flow: Request -> HierarchicalCache -> L1 (Miss) -> L2 (Miss) -> Data Source (Load) -> L2 (Populate) -> L1 (Populate) -> Response*
    *   **Null Value from Source**: If the data source returns `null`:
        *   Based on the current `HierarchicalCache` implementation, `null` values loaded from source are **not** put into L2 or L1. The `put()` operations are skipped. This is consistent with the Redis cache being configured with `disableCachingNullValues(true)`.
        *   The `null` is returned to the caller.

## 2. Cache Write Operation (`@CachePut` or `cache.put()`)

When a method annotated with `@CachePut` is called (which always executes the method and caches its result), or `cache.put(key, value)` is invoked:

1.  **Value Preparation**:
    *   The value to be cached is obtained (either from the method result for `@CachePut` or directly for `cache.put()`).
    *   **Null Value Handling**: If the value is `null`:
        *   The current `HierarchicalCache.put()` implementation will treat this as an eviction request for the key. Both L1 and L2 caches will have the key evicted. No `null` value is stored.
        *   *Flow (for null value): Request -> HierarchicalCache.put(key, null) -> L2 (Evict key) -> L1 (Evict key) -> Response*

2.  **L2 (Redis) Write**:
    *   If the value is not `null`:
        *   The `HierarchicalCache` first attempts to write the value to its L2 Redis cache instance.
    *   **L2 Write Failure**: If writing to L2 fails (e.g., Redis unavailable, serialization error):
        *   An error is logged.
        *   The process continues to write to L1 to maintain L1 availability.

3.  **L1 (Caffeine) Write**:
    *   After attempting the L2 write (regardless of its success, as per current error handling which prioritizes L1 availability):
        *   The `HierarchicalCache` writes the value to its L1 Caffeine cache instance.
    *   **L1 Write Failure**: If writing to L1 fails:
        *   An error is logged.
    *   *Flow (for non-null value): Request -> HierarchicalCache.put() -> L2 (Put) -> L1 (Put) -> Response*

## 3. Cache Eviction Operation (`@CacheEvict` or `cache.evict()`)

When a method annotated with `@CacheEvict` is called, or `cache.evict(key)` is invoked:

1.  **L2 (Redis) Eviction**:
    *   The `HierarchicalCache` first attempts to evict the entry from its L2 Redis cache instance.
    *   **L2 Eviction Failure**: If evicting from L2 fails:
        *   An error is logged.
        *   The process continues to evict from L1.

2.  **L1 (Caffeine) Eviction**:
    *   After attempting the L2 eviction:
        *   The `HierarchicalCache` evicts the entry from its L1 Caffeine cache instance.
    *   **L1 Eviction Failure**: If evicting from L1 fails:
        *   An error is logged.
    *   *Flow: Request -> HierarchicalCache.evict() -> L2 (Evict) -> L1 (Evict) -> Response*

## 4. Cache Clear Operation (`cache.clear()`)

When `cache.clear()` is invoked (e.g., to remove all entries from a specific cache):

1.  **L2 (Redis) Clear**:
    *   The `HierarchicalCache` first attempts to clear all entries from its L2 Redis cache instance (respecting the cache name prefix).
    *   **L2 Clear Failure**: If clearing L2 fails:
        *   An error is logged.
        *   The process continues to clear L1.

2.  **L1 (Caffeine) Clear**:
    *   After attempting the L2 clear:
        *   The `HierarchicalCache` clears all entries from its L1 Caffeine cache instance.
    *   **L1 Clear Failure**: If clearing L1 fails:
        *   An error is logged.
    *   *Flow: Request -> HierarchicalCache.clear() -> L2 (Clear) -> L1 (Clear) -> Response*

## Summary of Key Behaviors:

*   **Read-Through**: Reads attempt L1, then L2, then data source. Hits at any level return quickly. Data is back-filled to higher cache levels on misses.
*   **Write-Through (to L1/L2)**: Puts generally update both L2 and then L1. The current strategy prioritizes L1 availability even if L2 write fails.
*   **Eviction Propagation**: Evictions are applied to both L2 and L1. The current strategy prioritizes attempting eviction on both levels even if one fails.
*   **Null Value Handling**:
    *   Nulls loaded from source are generally not cached in L1 or L2.
    *   Explicitly `put(key, null)` results in an eviction of the key from both L1 and L2.
*   **Error Handling**: Failures in one cache level (e.g., L2 being down) are logged, and operations on the other level (L1) are still attempted to maximize availability/consistency where possible.

This hierarchical approach aims to provide the speed of L1 in-memory caching with the shared, distributed nature of L2 Redis caching, and robust fallback to the source of truth.
