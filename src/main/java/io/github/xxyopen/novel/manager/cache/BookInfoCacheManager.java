package io.github.xxyopen.novel.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.core.constant.CacheConsts;
import io.github.xxyopen.novel.core.constant.DatabaseConsts;
import io.github.xxyopen.novel.dao.entity.BookChapter;
import io.github.xxyopen.novel.dao.entity.BookInfo;
import io.github.xxyopen.novel.dao.mapper.BookChapterMapper;
import io.github.xxyopen.novel.dao.mapper.BookInfoMapper;
import io.github.xxyopen.novel.dto.resp.BookInfoRespDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 小说信息 缓存管理类
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class BookInfoCacheManager {

    private final BookInfoMapper bookInfoMapper;

    private final BookChapterMapper bookChapterMapper;

    /**
     * 从缓存中查询小说信息（先判断缓存中是否已存在，存在则直接从缓存中取，否则执行方法体中的逻辑后缓存结果）
     * Now uses the primary HierarchicalCacheManager by default.
     */
    @Cacheable(value = CacheConsts.BOOK_INFO_CACHE_NAME)
    public BookInfoRespDto getBookInfo(Long id) {
        // This method's logic might be re-evaluated. @Cacheable typically contains the
        // logic to load from source if missed. Calling another @CachePut annotated method
        // means the source is always hit by cachePutBookInfo, and then getBookInfo caches that result.
        return cachePutBookInfo(id);
    }

    /**
     * 缓存小说信息（不管缓存中是否存在都执行方法体中的逻辑，然后缓存起来）
     * Now uses the primary HierarchicalCacheManager by default.
     */
    @CachePut(value = CacheConsts.BOOK_INFO_CACHE_NAME)
    public BookInfoRespDto cachePutBookInfo(Long id) {
        // 查询基础信息
        BookInfo bookInfo = bookInfoMapper.selectById(id);
        if (bookInfo == null) {
            // Handle case where book is not found to avoid NPE
            // Depending on requirements, could return null, throw exception, or return empty DTO
            return null;
        }
        // 查询首章ID
        QueryWrapper<BookChapter> queryWrapper = new QueryWrapper<>();
        queryWrapper
            .eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, id)
            .orderByAsc(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        BookChapter firstBookChapter = bookChapterMapper.selectOne(queryWrapper);

        Long firstChapterId = null;
        if (firstBookChapter != null) {
            firstChapterId = firstBookChapter.getId();
        }

        // 组装响应对象
        return BookInfoRespDto.builder()
            .id(bookInfo.getId())
            .bookName(bookInfo.getBookName())
            .bookDesc(bookInfo.getBookDesc())
            .bookStatus(bookInfo.getBookStatus())
            .authorId(bookInfo.getAuthorId())
            .authorName(bookInfo.getAuthorName())
            .categoryId(bookInfo.getCategoryId())
            .categoryName(bookInfo.getCategoryName())
            .commentCount(bookInfo.getCommentCount())
            .firstChapterId(firstChapterId) // Use potentially null firstChapterId
            .lastChapterId(bookInfo.getLastChapterId())
            .picUrl(bookInfo.getPicUrl())
            .visitCount(bookInfo.getVisitCount())
            .wordCount(bookInfo.getWordCount())
            .build();
    }

    /**
     * Evicts book information from the cache.
     * Now uses the primary HierarchicalCacheManager by default.
     */
    @CacheEvict(value = CacheConsts.BOOK_INFO_CACHE_NAME)
    public void evictBookInfoCache(Long bookId) {
        // Calling this method automatically clears the cache for the bookId
    }

    /**
     * Queries the list of 500 most recently updated book IDs for each category
     * and caches them for 1 hour.
     * Now uses the primary HierarchicalCacheManager by default.
     */
    @Cacheable(value = CacheConsts.LAST_UPDATE_BOOK_ID_LIST_CACHE_NAME)
    public List<Long> getLastUpdateIdList(Long categoryId) {
        QueryWrapper<BookInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookTable.COLUMN_CATEGORY_ID, categoryId)
            .gt(DatabaseConsts.BookTable.COLUMN_WORD_COUNT, 0)
            .orderByDesc(DatabaseConsts.BookTable.COLUMN_LAST_CHAPTER_UPDATE_TIME)
            .last(DatabaseConsts.SqlEnum.LIMIT_500.getSql());
        return bookInfoMapper.selectList(queryWrapper).stream().map(BookInfo::getId).toList();
    }

}
