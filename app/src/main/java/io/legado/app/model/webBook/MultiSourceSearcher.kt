package io.legado.app.model.webBook

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 多源并发搜索 + 智能合并去重
 */
object MultiSourceSearcher {

    data class SearchResult(
        val book: SearchBook,
        val sourceName: String,
        val sourceUrl: String,
        val responseTime: Long,
        val score: Double = 0.0
    )

    suspend fun searchAllSources(
        sources: List<BookSource>,
        key: String,
        page: Int = 1,
        maxConcurrency: Int = 8
    ): List<SearchResult> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        val results = mutableListOf<SearchResult>()
        sources.map { source ->
            async {
                semaphore.withPermit {
                    try {
                        val start = System.currentTimeMillis()
                        val books = WebBook.searchBookAwait(source, key, page)
                        val elapsed = System.currentTimeMillis() - start
                        books.map { book ->
                            SearchResult(book, source.bookSourceName, source.bookSourceUrl, elapsed, calcScore(book, key))
                        }
                    } catch (e: Exception) { emptyList() }
                }
            }
        }.awaitAll().forEach { results.addAll(it) }
        // 按书名+作者去重，保留最快源
        results.groupBy { "${it.book.name}|${it.book.author}" }
            .map { it.value.minByOrNull { r -> r.responseTime }!! }
            .sortedByDescending { it.score }
    }

    private fun calcScore(book: SearchBook, keyword: String): Double {
        var s = 0.0
        if (book.name == keyword) s += 10.0
        else if (book.name.contains(keyword)) s += 5.0
        if (!book.author.isNullOrBlank()) s += 1.0
        // 有最新章节信息加分
        if (!book.latestChapterTitle.isNullOrBlank()) s += 1.0
        return s
    }
}
