# 多级缓存机制分析报告

## 1. 概述

本项目集成了 Caffeine 作为本地一级缓存（L1）和 Redis 作为远程二级缓存（L2）的组件。然而，通过对相关代码（`CacheConfig.java`, `CacheConsts.java`, 以及缓存管理器如 `BookInfoCacheManager.java`）的分析，当前的实现并非一个自动化的、透明的多级缓存体系。系统提供了两个独立的缓存管理器，开发者在应用层通过注解显式选择使用哪一个缓存（Caffeine 或 Redis），或者默认使用被标记为 `@Primary` 的 Caffeine 缓存。

## 2. 核心组件分析

### 2.1. `pom.xml` - 依赖项
项目包含了以下关键的缓存相关依赖：
- `org.springframework.boot:spring-boot-starter-cache`: Spring 官方缓存抽象。
- `org.springframework.boot:spring-boot-starter-data-redis`: Spring Data Redis，用于 Redis 集成。
- `com.github.ben-manes.caffeine:caffeine`: Caffeine 本地缓存库。

这表明项目具备了实现 Caffeine 和 Redis 缓存的基础。

### 2.2. `CacheConfig.java` - 缓存配置
此文件定义了两个核心的 Spring `CacheManager` Bean：

-   **`caffeineCacheManager()`**:
    -   使用 `com.github.benmanes.caffeine.cache.Caffeine` 构建。
    -   被注解为 `@Primary`，意味着如果缓存注解（如 `@Cacheable`）没有显式指定 `cacheManager`，则默认使用此管理器。
    -   遍历 `CacheConsts.CacheEnum` 中的所有枚举条目。
    -   如果枚举条目的 `isLocal()` 方法返回 `true`（即 `type` 为 0 或 1），则为该条目创建一个 `CaffeineCache` 实例。
    -   配置缓存的最大容量（`maximumSize`）和过期时间（`expireAfterWrite`，基于 `ttl`）。

-   **`redisCacheManager(RedisConnectionFactory connectionFactory)`**:
    -   使用 `org.springframework.data.redis.cache.RedisCacheWriter` 和 `RedisCacheConfiguration` 构建。
    -   同样遍历 `CacheConsts.CacheEnum`。
    -   如果枚举条目的 `isRemote()` 方法返回 `true`（即 `type` 为 1 或 2），则为该条目创建一个 `RedisCacheConfiguration`。
    -   配置缓存的过期时间（`entryTtl`，基于 `ttl`）和 Redis 键前缀（`CacheConsts.REDIS_CACHE_PREFIX`）。
    -   禁用了缓存空值 (`disableCachingNullValues`)。

**关键点**：
-   这两个管理器是独立配置和运作的。
-   如果一个 `CacheEnum` 条目的 `type` 设置为 `1`（表示本地和远程），`CacheConfig` 会在 `caffeineCacheManager` 中创建一个 Caffeine 缓存实例，并在 `redisCacheManager` 中创建一个 Redis 缓存实例，两者同名但独立。这种配置不会自动形成 L1 到 L2 的层级关系。

### 2.3. `CacheConsts.java` - 缓存常量
此文件定义了所有缓存相关的常量，最核心的是 `CacheEnum`：

-   **`CacheEnum`**:
    -   定义了应用中所有逻辑缓存的名称、TTL（Time-To-Live）、最大大小（`maxSize`，主要用于 Caffeine）以及一个关键的 `type` 字段。
    -   `type` 字段：
        -   `0`: 仅本地缓存 (Caffeine)。
        -   `1`: 本地和远程缓存 (Caffeine 和 Redis)。
        -   `2`: 仅远程缓存 (Redis)。
    -   提供了 `isLocal()` (`type <= 1`) 和 `isRemote()` (`type >= 1`) 的辅助方法。
    -   **当前枚举值中并没有实际使用 `type = 1` 的配置。** 大部分配置为 `type = 0` (Caffeine) 或 `type = 2` (Redis)。

-   **其他常量**:
    -   `REDIS_CACHE_PREFIX = "Cache::Novel::"`: Redis 缓存键的统一前缀。
    -   `CAFFEINE_CACHE_MANAGER = "caffeineCacheManager"`: Caffeine 缓存管理器的 Bean 名称。
    -   `REDIS_CACHE_MANAGER = "redisCacheManager"`: Redis 缓存管理器的 Bean 名称。

### 2.4. 缓存管理器组件 (例如, `BookInfoCacheManager.java`)
这类组件（位于 `io.github.xxyopen.novel.manager.cache` 包下）负责具体业务数据的缓存逻辑。

-   **注解驱动**:
    -   使用 Spring 的 `@Cacheable`, `@CachePut`, `@CacheEvict` 注解来声明缓存行为。
    -   **显式指定 `cacheManager`**: 在这些注解中，通过 `cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER` 或 `cacheManager = CacheConsts.REDIS_CACHE_MANAGER` 来明确指定该缓存操作应由哪个缓存管理器处理。
    -   例如，在 `BookInfoCacheManager` 中：
        ```java
        @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER, value = CacheConsts.BOOK_INFO_CACHE_NAME)
        public BookInfoRespDto getBookInfo(Long id) {
            // ...
        }
        ```
        这里明确了 `getBookInfo` 的结果会由 `caffeineCacheManager` 管理，并存储在名为 `BOOK_INFO_CACHE_NAME` 的 Caffeine 缓存中。

-   **数据源交互**:
    -   当发生缓存未命中（对于 `@Cacheable`）或需要强制更新缓存（对于 `@CachePut`）时，这些管理器的方法会调用相应的 MyBatis Mapper（DAO 层）与数据库交互。

**关键点**:
-   缓存管理器组件是实际选择 L1 或 L2 缓存的地方，通过注解的 `cacheManager` 属性实现。
-   没有在这些组件中看到自动的 L1 -> L2 -> 数据库的查询逻辑。查询要么命中指定的缓存，要么直接执行方法体（通常是数据库查询）。

## 3. 执行流程

当一个被缓存注解标记的方法被调用时：

1.  **AOP 拦截**: Spring 的缓存切面（AOP）拦截方法调用。
2.  **缓存管理器选择**:
    -   切面根据注解中的 `cacheManager` 属性确定使用哪个 `CacheManager` Bean (`caffeineCacheManager` 或 `redisCacheManager`)。
    -   如果注解中未指定 `cacheManager`，则使用 `@Primary` 的 `caffeineCacheManager`。
3.  **缓存键生成**: 根据方法参数生成缓存键。
4.  **缓存操作**:
    -   **`@Cacheable`**:
        -   选定的缓存管理器在其管理的缓存中查找该键。
        -   **命中**: 如果找到，直接返回缓存值，方法体不执行。
        -   **未命中**: 执行方法体（通常是查询数据库），将返回值存入选定缓存管理器的对应缓存中，然后返回结果。
    -   **`@CachePut`**:
        -   始终执行方法体。
        -   将方法返回值存入（或更新）选定缓存管理器的对应缓存中。
    -   **`@CacheEvict`**:
        -   从选定缓存管理器的对应缓存中移除指定的键（或所有条目）。
        -   然后执行方法体（默认行为）。

**核心要点**:
-   整个流程是针对**单一选定缓存管理器**的。如果选择了 Caffeine，操作就只在 Caffeine 中进行；如果选择了 Redis，就只在 Redis 中进行。
-   **没有自动的 L1 到 L2 的降级查询机制**。例如，如果 `@Cacheable` 指定了 `caffeineCacheManager`，在 Caffeine 未命中时，系统不会自动去 `redisCacheManager` 中查找。

## 4. 调用关系

典型的调用链如下：

`Controller` -> `Service Impl (例如 BookServiceImpl)` -> `Cache Manager Component (例如 BookInfoCacheManager)` -> (AOP 拦截) -> `Spring CacheManager Bean (CaffeineCacheManager 或 RedisCacheManager)` -> `Underlying Cache Store (Caffeine map 或 Redis)`

-   **Controller**: 处理 HTTP 请求，调用 Service 层。
-   **Service Impl**: 实现业务逻辑，当需要可缓存数据时，调用相应的 Cache Manager Component。
-   **Cache Manager Component**: 封装特定数据的缓存获取、更新、清除逻辑，通过注解与 Spring CacheManager Bean 交互。这些组件内部在缓存未命中时会调用 DAO/Mapper。
-   **DAO/Mapper**: 数据库访问层。

这种结构将业务逻辑与缓存管理策略（具体到是使用 L1 还是 L2）分离开来，但缓存的选择权在 Cache Manager Component 的注解层面。

## 5. 总结与结论

-   **机制实现**: 项目拥有 L1 (Caffeine) 和 L2 (Redis) 的缓存能力，并通过独立的 Spring `CacheManager` Bean 进行管理。
-   **非层级透明**: 当前不是一个透明的、自动化的多级缓存系统。它更像是一个“可选单级缓存”系统，开发者可以为不同的缓存需求选择使用 Caffeine 或 Redis。
-   **`type = 1` 的误区**: `CacheEnum` 中的 `type = 1` (local and remote) 配置，如果被使用，会导致在 Caffeine 和 Redis 中分别创建同名的、但完全独立的缓存实例，而不是一个层级结构。
-   **显式选择**: L1 或 L2 的使用是通过在缓存注解中明确指定 `cacheManager` 来决定的。如果未指定，则默认为 `@Primary` 的 `caffeineCacheManager`。
-   **无自动降级**: 不存在从 L1 未命中后自动查询 L2 的机制。

**改进方向 (如果需要真正的多级缓存)**:
若要实现真正的“查L1 -> 未命中则查L2 -> 未命中则查DB，并回填L1、L2”的多级缓存模式，需要：
1.  **自定义 `CacheManager`**: 实现一个自定义的 Spring `CacheManager`，它内部封装对 Caffeine 和 Redis 的操作，并实现层级查询和回填逻辑。
2.  **手动组合**: 在 Cache Manager Component 的方法内部手动编写逻辑：先查 Caffeine，再查 Redis，最后查数据库，并手动更新两个缓存。这种方式代码侵入性较大。

目前，该系统的“多级缓存”是指提供了多种缓存级别供开发者按需选用，而非一个自动化的层级缓存解决方案。
