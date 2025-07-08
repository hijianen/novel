# 推荐功能分析

本项目包含两种主要的推荐功能：首页推荐和书籍详情页的相关书籍推荐。

## 一、首页小说推荐

首页的小说推荐是一种编辑运营驱动的推荐方式，即推荐内容主要通过后台人工配置产生。

### 1. 流程

用户访问应用首页时，前端调用后端API获取首页推荐的小说列表。

### 2. 调用关系

1.  `io.github.xxyopen.novel.controller.front.HomeController`:
    *   `listHomeBooks()` 方法：作为API入口，接收前端请求。
2.  `io.github.xxyopen.novel.service.HomeService` (接口) 和 `io.github.xxyopen.novel.service.impl.HomeServiceImpl` (实现类):
    *   `listHomeBooks()` 方法：业务逻辑处理，实际调用 `HomeBookCacheManager`。
3.  `io.github.xxyopen.novel.manager.cache.HomeBookCacheManager`:
    *   `listHomeBooks()` 方法：核心逻辑，负责从数据库查询、组装并缓存推荐数据。
        *   查询 `home_book` 表获取推荐配置。
        *   根据 `home_book` 中的 `book_id` 查询 `book_info` 表获取书籍详细信息。
        *   将结果组装成 `HomeBookRespDto` 列表。

### 3. 结构设计和关键点

*   **数据表驱动**:
    *   `home_book` 表：核心配置表，存储哪些书籍被推荐、推荐的类型以及排序。
        *   `id`: 主键。
        *   `type`: 推荐类型 (例如，0-轮播图, 1-顶部栏, 2-本周强推等)，用于区分首页不同位置的推荐内容。
        *   `sort`: 推荐排序，决定在同一类型中书籍的显示顺序。
        *   `book_id`:关联的小说ID。
    *   `book_info` 表：存储小说的详细信息。
*   **DTO**: `HomeBookRespDto` 用于封装返回给前端的推荐书籍数据，包含类型、书籍ID、书名、封面、作者、描述等。
*   **缓存机制**:
    *   `HomeBookCacheManager` 中的 `listHomeBooks()` 方法使用了 `@Cacheable` 注解 (Caffeine缓存)，将查询和组装好的首页推荐数据缓存起来，提高后续请求的响应速度。缓存名为 `CacheConsts.HOME_BOOK_CACHE_NAME`。
*   **关注点**:
    *   推荐内容依赖于 `home_book` 表中的数据，因此更新推荐内容需要操作此表（通常通过后台管理系统）。
    *   `type` 字段的设计使得首页可以灵活配置多种不同模块的推荐。

## 二、书籍详情页相关书籍推荐 ("猜你喜欢" 或同类推荐)

当用户查看某本小说的详情时，系统会推荐与该小说同类型的其他几本小说。

### 1. 流程

用户进入小说详情页时，后端根据当前小说的ID，查询并返回一个相关推荐的小说列表。

### 2. 调用关系

1.  前端调用 (推测存在对应的 `BookController` 中的API，例如 `/api/front/book/rec_books/{bookId}`)
2.  `io.github.xxyopen.novel.service.BookService` (接口) 和 `io.github.xxyopen.novel.service.impl.BookServiceImpl` (实现类):
    *   `listRecBooks(Long bookId)` 方法：核心业务逻辑。
3.  `io.github.xxyopen.novel.manager.cache.BookInfoCacheManager`:
    *   `getBookInfo(Long id)` 方法：获取当前书籍的信息，特别是其 `categoryId`。
    *   `getLastUpdateIdList(Long categoryId)` 方法：获取指定分类下最新更新的最多500本小说的ID列表。

### 3. 结构设计和关键点

*   **推荐逻辑**:
    1.  获取当前查看书籍的 `categoryId`。
    2.  调用 `BookInfoCacheManager.getLastUpdateIdList(categoryId)` 获取该分类下最近更新的最多500本书籍ID列表。此列表按 `last_chapter_update_time` 降序排序。
    3.  从这500本书籍ID中，使用 `SecureRandom.getInstanceStrong()` 随机选择固定数量 (当前为 `REC_BOOK_COUNT = 4`) 的不重复的书籍ID。
    4.  根据选中的书籍ID，查询完整的书籍信息 (`BookInfoRespDto`)。
*   **数据表与字段**:
    *   `book_info` 表：
        *   `id`: 书籍ID。
        *   `category_id`: 书籍分类ID，是推荐匹配的核心字段。
        *   `last_chapter_update_time`: 最新章节更新时间，用于筛选热门/活跃书籍作为推荐候选池。
        *   `word_count`: 字数，`getLastUpdateIdList` 方法中会筛选 `word_count > 0` 的书籍。
*   **DTO**: `BookInfoRespDto` 用于封装返回给前端的推荐书籍数据。
*   **缓存机制**:
    *   `BookInfoCacheManager.getBookInfo()`: 缓存单本小说的详细信息。
    *   `BookInfoCacheManager.getLastUpdateIdList()`: 缓存每个分类下的最新更新书籍ID列表 (`CacheConsts.LAST_UPDATE_BOOK_ID_LIST_CACHE_NAME`)。这避免了对 `book_info` 表的频繁排序查询。
*   **关键点**:
    *   **基于分类的推荐**: 这是一种简单的内容相似性推荐，认为同类书籍用户可能也喜欢。
    *   **结合新近度与随机性**: 首先筛选出同类中最近更新的一批书（最多500本），然后从中随机挑选，兼顾了内容更新和一定的多样性。
    *   `NoSuchAlgorithmException`: 在 `BookServiceImpl.listRecBooks` 中声明，是因为 `SecureRandom.getInstanceStrong()` 可能在特定环境下抛出此异常。
    *   固定推荐数量为4本。

## 三、总结

该项目的推荐系统主要包含两种策略：
1.  **人工编辑推荐**：用于首页等重要展示位置，保证推荐质量和运营目的。
2.  **基于分类的随机推荐**：用于书籍详情页，提供相关性推荐，实现简单有效。

目前未发现更复杂的推荐算法，如协同过滤（用户行为分析）、更深度的内容分析（如标签、关键词）等。若要提升推荐效果，可以考虑引入用户阅读历史 (`UserReadHistory` 表似乎存在，可作为数据基础) 等数据进行更个性化的推荐。
