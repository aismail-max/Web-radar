package com.example.api

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val MODEL = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Checks if the Gemini API key is configured.
     */
    fun isApiKeyConfigured(): Boolean {
        // BuildConfig.GEMINI_API_KEY is injected via the Secrets Gradle Plugin
        val key = BuildConfig.GEMINI_API_KEY
        return !key.isNullOrEmpty() && key != "MY_GEMINI_API_KEY"
    }

    /**
     * Sends plain text extracted from a webpage to Gemini to parse and obtain 3-5 structured latest announcements or news in JSON.
     */
    suspend fun analyzeWebpageContent(webpageText: String, siteUrl: String): List<ParsedUpdate> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            Log.w(TAG, "Gemini API Key is not configured.")
            return@withContext emptyList()
        }

        // Limit input size to stay healthy and low latency
        val rawContent = if (webpageText.length > 8000) {
            webpageText.take(8000) + "... [محتوى مقتطع لتوفير الحجم]"
        } else {
            webpageText
        }

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val currentDateStr = sdf.format(java.util.Date())
        val fortyEightHoursAgoStr = sdf.format(java.util.Date(System.currentTimeMillis() - 48L * 60 * 60 * 1000))

        val prompt = """
            أنت خبير كشط وتحليل صفحات المواقع العربية.
            مهمتك هي تحليل النص المستخرج التالي من موقع إلكتروني رابطة الأساسي هو ($siteUrl) واستخراج الأخبار أو المقالات المضافة مؤخراً.
            
            التاريخ الحالي للمستخدم هو: $currentDateStr.
            
            شروط فلترة وتحديد المحتويات (هام جداً):
            يجب عليك رصد واستخراج الأخبار أو المنشورات أو التحديثات التي تم نشرها أو تحديثها خلال الـ 48 ساعة الأخيرة فقط (أي منذ تاريخ $fortyEightHoursAgoStr وحتى تاريخ اليوم $currentDateStr فصاعداً).
            استبعد تماماً وبشكل قاطع أي خبر أو منشور أو تحديث يرجع لشرح قديم أو تاريخ أقدم من 48 ساعة (مثل أي تاريخ يقع قبل $fortyEightHoursAgoStr، أو يشير لمدد قديمة مثل "منذ 3 أيام"، "منذ أسبوع"، "منذ سنة"، أو يحمل تاريخاً في سنوات سابقة كـ 2025 وما قبلها).
            
            النص المستخرج من الموقع:
            ---
            $rawContent
            ---
            
            المتطلبات:
            1. ابحث عن المحتوى الفعلي مثل العناوين الرئيسية، التواريخ، والملخصات. تجاهل القوائم الجانبية، الفوتر، وعناصر التنقل الروتينية (مثل: اتصل بنا، من نحن، سياسة الخصوصية).
            2. صنف نوع كل محتوى إلى أحد الأنواع التالية باللغة العربية بالضبط: "خبر" أو "تقرير" أو "منشور" أو "تحديث" أو "إعلان".
            3. قم بصياغة وصف مختصر ومفيد من سطر واحد (ملخص) لكل منشور باللغة العربية.
            4. تأكد من أن الروابط المستخرجة هي روابط صحيحة وكاملة (ابدأ بـ http أو https). إذا كان الرابط نسبياً، ادمجه مع رابط الموقع الأساسي ($siteUrl) لجعله كاملاً.
            5. استخرج تاريخ وساعة النشر الموضحة للمنشور على الموقع كما هي بالتفصيل (مثل: اليوم الساعة 03:30 مساءً، أو 14 يونيو 2026 الساعة 10:30 صباحاً، أو منذ ساعتين). إذا لم تجد تاريخاً واضحاً، فقم بتقدير وتوليد تاريخ وساعة مناسبين ضمن الـ 48 ساعة الأخيرة متوافقة مع هذا التوقيت الحالي.
            6. أرجع النتيجة على شكل مصفوفة JSON صالحة للتجزئة ومطابقة للنموذج التالي تماماً دون أي أحرف زائدة أو شرح خارجي:
            [
              {
                "title": "عنوان الخبر أو المنشور هنا",
                "type": "خبر",
                "publishDate": "تاريخ وساعة النشر المذكورة على الموقع بالتفصيل",
                "briefDescription": "وصف مختصر باللغة العربية للخبر هنا.",
                "url": "https://example.com/news/1"
              }
            ]
            
            أرجع مصفوفة JSON فارغة [] إذا لم تجد أي أخبار أو تحديثات حديثة (أقل من 48 ساعة) في النص.
        """.trimIndent()

        try {
            val endpoint = "$BASE_URL?key=${BuildConfig.GEMINI_API_KEY}"
            
            // Build request JSON
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                })
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "Gemini call failed with code: ${response.code}, body: $errBody")
                    return@withContext emptyList()
                }

                val responseBody = response.body?.string() ?: return@withContext emptyList()
                val responseJson = JSONObject(responseBody)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) return@withContext emptyList()

                val text = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .optString("text")

                if (text.isNullOrBlank()) return@withContext emptyList()

                val parsedList = mutableListOf<ParsedUpdate>()
                val jsonArray = JSONArray(text.trim())
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    parsedList.add(
                        ParsedUpdate(
                            title = obj.optString("title", "تحديث جديد"),
                            type = obj.optString("type", "تحديث"),
                            briefDescription = obj.optString("briefDescription", "تم العثور على محتوى جديد في الموقع."),
                            url = obj.optString("url", siteUrl),
                            publishDate = obj.optString("publishDate", "")
                        )
                    )
                }
                return@withContext parsedList
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error compiling AI analysis with Gemini: ", e)
            return@withContext emptyList()
        }
    }
}

data class ParsedUpdate(
    val title: String,
    val type: String,
    val briefDescription: String,
    val url: String,
    val publishDate: String = ""
)
