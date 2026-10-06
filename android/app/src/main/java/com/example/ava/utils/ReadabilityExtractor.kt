package com.example.ava.utils

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

data class ExtractedContent(
    val title: String,
    val content: String,
    val textContent: String,
    val excerpt: String,
    val byline: String?,
    val siteName: String?,
    val lang: String?,
    val length: Int,
    val url: String
)

interface ContentProcessor {
    suspend fun process(content: ExtractedContent): String
}

class ReadabilityExtractor(
    private val webView: WebView
) {
    companion object {
        private const val TAG = "ReadabilityExtractor"
        
        private const val READABILITY_JS = """
            (function() {
                function getTextContent(element) {
                    var text = '';
                    var children = element.childNodes;
                    for (var i = 0; i < children.length; i++) {
                        var node = children[i];
                        if (node.nodeType === 3) {
                            text += node.textContent;
                        } else if (node.nodeType === 1) {
                            var tag = node.tagName.toLowerCase();
                            if (['script', 'style', 'noscript', 'iframe', 'nav', 'header', 'footer', 'aside'].indexOf(tag) === -1) {
                                text += getTextContent(node);
                            }
                        }
                    }
                    return text;
                }
                
                function getMainContent() {
                    var candidates = [];
                    var selectors = ['article', 'main', '[role="main"]', '.post-content', '.article-content', '.entry-content', '.content', '#content', '.post', '.article'];
                    
                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) {
                            candidates.push({el: el, score: 100 - i * 10});
                        }
                    }
                    
                    var paragraphs = document.querySelectorAll('p');
                    var parentScores = {};
                    
                    for (var i = 0; i < paragraphs.length; i++) {
                        var p = paragraphs[i];
                        var text = p.textContent.trim();
                        if (text.length < 25) continue;
                        
                        var parent = p.parentElement;
                        if (!parent) continue;
                        
                        var key = parent.tagName + '_' + (parent.className || '') + '_' + (parent.id || '');
                        if (!parentScores[key]) {
                            parentScores[key] = {el: parent, score: 0, textLen: 0};
                        }
                        parentScores[key].score += 1 + Math.min(Math.floor(text.length / 100), 3);
                        parentScores[key].textLen += text.length;
                    }
                    
                    var bestParent = null;
                    var bestScore = 0;
                    for (var key in parentScores) {
                        var item = parentScores[key];
                        if (item.score > bestScore && item.textLen > 200) {
                            bestScore = item.score;
                            bestParent = item.el;
                        }
                    }
                    
                    if (bestParent && bestScore > 3) {
                        candidates.push({el: bestParent, score: bestScore * 10});
                    }
                    
                    candidates.sort(function(a, b) { return b.score - a.score; });
                    
                    return candidates.length > 0 ? candidates[0].el : document.body;
                }
                
                function extractContent() {
                    var mainEl = getMainContent();
                    var textContent = getTextContent(mainEl).replace(/\s+/g, ' ').trim();
                    
                    var title = '';
                    var h1 = document.querySelector('h1');
                    if (h1) {
                        title = h1.textContent.trim();
                    } else {
                        title = document.title || '';
                    }
                    
                    var excerpt = textContent.substring(0, 200);
                    
                    var byline = '';
                    var authorMeta = document.querySelector('meta[name="author"]');
                    if (authorMeta) {
                        byline = authorMeta.getAttribute('content') || '';
                    }
                    
                    var siteName = '';
                    var siteNameMeta = document.querySelector('meta[property="og:site_name"]');
                    if (siteNameMeta) {
                        siteName = siteNameMeta.getAttribute('content') || '';
                    }
                    
                    var lang = document.documentElement.lang || '';
                    
                    return JSON.stringify({
                        title: title,
                        content: mainEl.innerHTML,
                        textContent: textContent,
                        excerpt: excerpt,
                        byline: byline,
                        siteName: siteName,
                        lang: lang,
                        length: textContent.length,
                        url: window.location.href
                    });
                }
                
                return extractContent();
            })();
        """
    }
    
    private var contentProcessor: ContentProcessor? = null
    
    fun setContentProcessor(processor: ContentProcessor) {
        this.contentProcessor = processor
    }
    
    suspend fun extract(): ExtractedContent? = withContext(Dispatchers.Main) {
        try {
            val jsonStr = evaluateJavascript(READABILITY_JS)
            if (jsonStr.isNullOrBlank() || jsonStr == "null") {
                Log.w(TAG, "Failed to extract content: empty result")
                return@withContext null
            }
            
            val cleanJson = org.json.JSONTokener(jsonStr).nextValue() as? String ?: jsonStr

            val json = JSONObject(cleanJson)
            ExtractedContent(
                title = json.optString("title", ""),
                content = json.optString("content", ""),
                textContent = json.optString("textContent", ""),
                excerpt = json.optString("excerpt", ""),
                byline = json.optString("byline").takeIf { it.isNotEmpty() },
                siteName = json.optString("siteName").takeIf { it.isNotEmpty() },
                lang = json.optString("lang").takeIf { it.isNotEmpty() },
                length = json.optInt("length", 0),
                url = json.optString("url", "")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract content", e)
            null
        }
    }
    
    suspend fun extractAndProcess(): String? {
        val content = extract() ?: return null
        return contentProcessor?.process(content) ?: content.textContent
    }
    
    suspend fun extractForOpenClaw(): OpenClawInput? {
        val content = extract() ?: return null
        return OpenClawInput(
            text = content.textContent,
            title = content.title,
            url = content.url,
            lang = content.lang
        )
    }
    
    private suspend fun evaluateJavascript(script: String): String? = 
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        }
}

data class OpenClawInput(
    val text: String,
    val title: String,
    val url: String,
    val lang: String?
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("text", text)
            put("title", title)
            put("url", url)
            lang?.let { put("lang", it) }
        }.toString()
    }
    
    fun toCommandArgs(): List<String> {
        return listOf(
            "--text", text.take(MAX_TEXT_LENGTH),
            "--title", title,
            "--url", url
        ).let { args ->
            if (lang != null) args + listOf("--lang", lang) else args
        }
    }
    
    companion object {
        const val MAX_TEXT_LENGTH = 100000
    }
}

interface OpenClawBridge {
    suspend fun summarize(input: OpenClawInput): String?
    suspend fun extractEntities(input: OpenClawInput): List<String>?
    suspend fun analyze(input: OpenClawInput, prompt: String): String?
    fun isAvailable(): Boolean
}

class OpenClawStub : OpenClawBridge {
    override suspend fun summarize(input: OpenClawInput): String? = null
    override suspend fun extractEntities(input: OpenClawInput): List<String>? = null
    override suspend fun analyze(input: OpenClawInput, prompt: String): String? = null
    override fun isAvailable(): Boolean = false
}
