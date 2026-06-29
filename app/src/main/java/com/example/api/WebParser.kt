package com.example.api

import android.content.Context
import android.util.Log
import org.jsoup.Jsoup
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

object WebParser {
    private const val TAG = "WebParser"

    /**
     * Extracts updates for a specific URL, automatically choosing between:
     * 1. Smart RSS / XML Parsing (if appropriate or detected)
     * 2. Intelligent AI Parsing with Gemini (if useAiSmartScan is toggled and key is active)
     * 3. HTML Scraping using Jsoup (Default fallback)
     */
    suspend fun fetchUpdates(
        url: String,
        useAiSmartScan: Boolean = false
    ): ParseResult {
        try {
            val resolvedUrl = sanitizeUrl(url)
            val connection = Jsoup.connect(resolvedUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36")
                .timeout(15000)
                .ignoreContentType(true)
                .followRedirects(true)

            val response = connection.execute()
            val contentType = response.contentType() ?: ""
            val body = response.body()

            // Calculate content hash for change detection
            val currentHash = md5(body)

            // 1. Check if the contentType is XML/RSS or if URL ends in feed/xml or body is XML
            val isXml = contentType.contains("xml") || contentType.contains("rss") || body.trim().startsWith("<?xml") || url.contains("feed") || url.contains("rss")
            
            if (isXml) {
                Log.d(TAG, "Parsing as XML/RSS feed: $url")
                val updates = parseRssXml(body, resolvedUrl)
                return ParseResult(updates, currentHash)
            }

            // 2. Parsed via HTML Jsoup
            val document = response.parse()
            
            if (useAiSmartScan && GeminiClient.isApiKeyConfigured()) {
                Log.d(TAG, "Parsing HTML as Smart AI Scan: $url")
                // Extract clean text representing the page content (removing scripts, styles, etc.)
                document.select("script, style, iframe, header, footer, nav, noscript").remove()
                val bodyText = document.body().text()
                val parsedUpdates = GeminiClient.analyzeWebpageContent(bodyText, resolvedUrl)
                
                val updates = parsedUpdates.map {
                    ParsedUpdateItem(
                        title = it.title,
                        type = it.type,
                        briefDescription = it.briefDescription,
                        url = it.url,
                        publishDate = if (it.publishDate.isNotEmpty()) it.publishDate else formatPublishTime(System.currentTimeMillis())
                    )
                }
                return ParseResult(updates, currentHash)
            } else {
                Log.d(TAG, "Parsing HTML via structural scraping: $url")
                // Scraping fallback
                val updates = scrapeHtmlPage(document, resolvedUrl)
                return ParseResult(updates, currentHash)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching site updates: ", e)
            throw e
        }
    }

    private fun sanitizeUrl(url: String): String {
        return if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
    }

    /**
     * Parses RSS XML structure.
     */
    private fun parseRssXml(xmlString: String, baseUrl: String): List<ParsedUpdateItem> {
        val updates = mutableListOf<ParsedUpdateItem>()
        try {
            val doc = Jsoup.parse(xmlString, "", org.jsoup.parser.Parser.xmlParser())
            val items = doc.select("item")
            for (item in items.take(15)) {
                val title = item.select("title").text().trim()
                var link = item.select("link").text().trim()
                if (link.isEmpty()) {
                    link = item.select("guid").text().trim()
                }
                var description = item.select("description").text().trim()
                // Strip HTML tags from description
                description = Jsoup.parse(description).text().trim()
                if (description.length > 120) {
                    description = description.take(120) + "..."
                }

                if (title.isNotEmpty()) {
                    val pubDateStr = item.select("pubDate, dc|date, updated, published").text().trim()
                    val parsedDate = parseRssDate(pubDateStr)
                    if (parsedDate != null) {
                        val diffMillis = System.currentTimeMillis() - parsedDate.time
                        val hours = diffMillis / (1000L * 60 * 60)
                        if (hours > 48 || hours < -5) {
                            Log.d(TAG, "Skipping RSS item since it was published $hours hours ago (outside 48h limit): $title")
                            continue
                        }
                    } else if (pubDateStr.isNotEmpty() && isClearlyOlderThan48Hours(pubDateStr)) {
                        Log.d(TAG, "Skipping RSS item on clear old string date text check: $title")
                        continue
                    }

                    val formattedPublishDate = if (parsedDate != null) {
                        formatPublishTime(parsedDate.time)
                    } else if (pubDateStr.isNotEmpty()) {
                        pubDateStr
                    } else {
                        formatPublishTime(System.currentTimeMillis())
                    }

                    updates.add(
                        ParsedUpdateItem(
                            title = title,
                            type = "خبر", // Default RSS to news
                            briefDescription = if (description.isEmpty()) "خبر جديد من RSS Feed" else description,
                            url = makeAbsoluteUrl(link, baseUrl),
                            publishDate = formattedPublishDate
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing RSS, falling back", e)
        }
        return updates
    }

    /**
     * Intelligent HTML scraper looking for list elements, block containers, and anchors.
     */
    private fun scrapeHtmlPage(
        document: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<ParsedUpdateItem> {
        val updates = mutableListOf<ParsedUpdateItem>()
        
        // Exclude standard navigational areas to reduce noise
        document.select("header, footer, nav, script, style, .sidebar, .menu, #menu, .navigation").remove()

        // 1. Try to scan typical layout structures for news/announcements
        // Select elements likely containing headlining news
        val elements = document.select("article, .post, .item, .news-item, .card, li, tr")
        
        var foundMainElements = false
        for (el in elements) {
            val titleEl = el.select("h1, h2, h3, h4, h5, h6, .title, a[href]").firstOrNull() ?: continue
            val title = titleEl.text().trim()
            val linkEl = el.select("a[href]").firstOrNull() ?: continue
            val href = linkEl.attr("href")
            
            if (title.length > 15 && href.isNotEmpty() && !isMenuLink(href, title)) {
                // Check if the element contains indicators of older date
                val elText = el.text()
                if (isClearlyOlderThan48Hours(elText)) {
                    Log.d(TAG, "Skipping HTML element because it has old date indicators: $title")
                    continue
                }

                val dateText = el.select(".date, .time, time, .published, .meta, .post-date, .post-meta").firstOrNull()?.text()?.trim() ?: ""
                val formattedPublishDate = if (dateText.isNotEmpty() && dateText.length < 35) {
                    dateText
                } else {
                    formatPublishTime(System.currentTimeMillis())
                }

                updates.add(
                    ParsedUpdateItem(
                        title = title,
                        type = "منشور",
                        briefDescription = "تم العثور على تحديث مرئي في صفحة الموقع.",
                        url = makeAbsoluteUrl(href, baseUrl),
                        publishDate = formattedPublishDate
                    )
                )
                foundMainElements = true
            }
            if (updates.size >= 8) break
        }

        // 2. Fallback to extracting robust links directly from body if layout is plain text
        if (!foundMainElements) {
            val anchors = document.select("a[href]")
            for (anchor in anchors) {
                val title = anchor.text().trim()
                val href = anchor.attr("href")
                
                if (title.length > 15 && href.isNotEmpty() && !isMenuLink(href, title)) {
                    // Let's check if the parent text contains older dates
                    val parentEl = anchor.parent()
                    val parentText = parentEl?.text() ?: ""
                    if (isClearlyOlderThan48Hours(parentText)) {
                        Log.d(TAG, "Skipping fallback HTML anchor because surrounding elements indicate old date: $title")
                        continue
                    }

                    val dateTextFallback = parentEl?.select(".date, .time, time, .published, .meta, .post-date, .post-meta")?.firstOrNull()?.text()?.trim() ?: ""
                    val formattedPublishDate = if (dateTextFallback.isNotEmpty() && dateTextFallback.length < 35) {
                        dateTextFallback
                    } else {
                        formatPublishTime(System.currentTimeMillis())
                    }

                    updates.add(
                        ParsedUpdateItem(
                            title = title,
                            type = "تحديث",
                            briefDescription = "تحديث جديد مرصود عبر الروابط المحدثة.",
                            url = makeAbsoluteUrl(href, baseUrl),
                            publishDate = formattedPublishDate
                        )
                    )
                }
                if (updates.size >= 8) break
            }
        }

        return updates.distinctBy { it.url }
    }

    private fun parseRssDate(dateStr: String): Date? {
        if (dateStr.isEmpty()) return null
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                return sdf.parse(dateStr)
            } catch (e: Exception) {
                // Continue trying other formats
            }
        }
        return null
    }

    private fun isClearlyOlderThan48Hours(text: String): Boolean {
        val clean = text.lowercase(Locale.getDefault())
        
        // Arabic old date indicators (relative and static)
        val arabicOldIndicators = listOf(
            "منذ 3 أيام", "منذ ٣ أيام", "قبل 3 أيام", "قبل ٣ أيام",
            "منذ 4 أيام", "منذ ٤ أيام", "قبل 4 أيام", "قبل ٤ أيام",
            "منذ 5 أيام", "منذ ٥ أيام", "قبل 5 أيام", "قبل ٥ أيام",
            "منذ 6 أيام", "منذ ٦ أيام", "قبل 6 أيام", "قبل ٦ أيام",
            "منذ 7 أيام", "منذ ٧ أيام", "قبل 7 أيام", "قبل ٧ أيام",
            "منذ أسبوع", "منذ اسبوع", "قبل أسبوع", "قبل اسبوع",
            "منذ أسبوعين", "منذ اسبوعين", "قبل أسبوعين", "قبل اسبوعين",
            "منذ شهر", "قبل شهر", "منذ شهرين", "قبل شهرين",
            "منذ عام", "قبل عام", "منذ سنة", "قبل سنة",
            "منذ سنوات", "قبل سنوات", "منذ أعوام", "قبل أعوام"
        )
        for (indicator in arabicOldIndicators) {
            if (clean.contains(indicator)) return true
        }
        
        // Find pattern like: "منذ 5 يوم" or "قبل 3 أيام"
        val arabicDaysRegex = Regex("(منذ|قبل)\\s+(\\d+|[0-9]+|\\d+)\\s+(أيام|ايام|يوم)")
        arabicDaysRegex.find(clean)?.let { match ->
            val numStr = match.groupValues[2]
            val num = numStr.toIntOrNull()
            if (num != null && num >= 3) {
                return true
            }
        }

        // English old date indicators
        val englishOldIndicators = listOf(
            "3 days ago", "4 days ago", "5 days ago", "6 days ago", "7 days ago",
            "a week ago", "1 week ago", "2 weeks ago", "3 weeks ago",
            "a month ago", "1 month ago", "2 months ago", "a year ago", "1 year ago"
        )
        for (indicator in englishOldIndicators) {
            if (clean.contains(indicator)) return true
        }
        
        val englishDaysRegex = Regex("(\\d+)\\s+days\\s+ago")
        englishDaysRegex.find(clean)?.let { match ->
            val numStr = match.groupValues[1]
            val num = numStr.toIntOrNull()
            if (num != null && num >= 3) {
                return true
            }
        }

        // Check for old years (since current year is 2026, check years 2000 to 2025 as being definitely > 48h old)
        val yearRegex = Regex("\\b(20[0-1][0-9]|202[0-5])\\b")
        if (yearRegex.containsMatchIn(clean)) {
            return true
        }

        return false
    }

    private fun isMenuLink(href: String, title: String): Boolean {
        val lowercaseHref = href.lowercase()
        val lowercaseTitle = title.lowercase()
        val ignoredKeywords = listOf(
            "اتصل", "تواصل", "من نحن", "الرئيسية", "سياسة", "خصوصية",
            "contact", "about", "privacy", "policy", "home", "terms", "shروط"
        )
        for (kw in ignoredKeywords) {
            if (lowercaseTitle.contains(kw) || lowercaseHref.contains(kw)) {
                return true
            }
        }
        return false
    }

    private fun makeAbsoluteUrl(url: String, baseUrl: String): String {
        return try {
            val base = URL(baseUrl)
            URL(base, url).toExternalForm()
        } catch (e: Exception) {
            url
        }
    }

    private fun md5(str: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(str.toByteArray())
            val messageDigest = digest.digest()
            val hexString = StringBuilder()
            for (aMessageDigest in messageDigest) {
                var h = Integer.toHexString(0xFF and aMessageDigest.toInt())
                while (h.length < 2) h = "0$h"
                hexString.append(h)
            }
            hexString.toString()
        } catch (e: Exception) {
            str.hashCode().toString()
        }
    }

    private fun formatPublishTime(timestamp: Long): String {
        val bTimeZone = TimeZone.getTimeZone("Asia/Bahrain")
        val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.forLanguageTag("ar-u-nu-latn"))
        sdf.timeZone = bTimeZone
        return sdf.format(Date(timestamp))
    }
}

data class ParseResult(
    val updates: List<ParsedUpdateItem>,
    val hash: String
)

data class ParsedUpdateItem(
    val title: String,
    val type: String,
    val briefDescription: String,
    val url: String,
    val publishDate: String = ""
)
