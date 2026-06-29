package com.example.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.MonitoredSite
import com.example.data.UpdateRecord
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object ReportExporter {
    private const val TAG = "ReportExporter"

    /**
     * Exports a list of updates into an Excel-friendly CSV file and launches a Share Intent.
     */
    fun exportToCsv(context: Context, updates: List<UpdateRecord>, sites: List<MonitoredSite>) {
        try {
            val csvFile = File(context.cacheDirs(), "تقرير_رادار_المواقع_${System.currentTimeMillis()}.csv")
            val writer = FileWriter(csvFile)
            
            // Write UTF-8 BOM so Excel opens Arabic correctly
            writer.write('\ufeff'.toInt())
            
            // CSV Headers
            writer.write("الموقع,عنوان التحديث,النوع,تاريخ الاكتشاف,الوصف المختصر,الرابط\n")

            val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.forLanguageTag("ar-u-nu-latn"))
            sdf.timeZone = TimeZone.getTimeZone("Asia/Bahrain")

            for (update in updates) {
                val siteName = escapeCsvField(update.siteName)
                val title = escapeCsvField(update.title)
                val type = escapeCsvField(update.type)
                val time = escapeCsvField(sdf.format(Date(update.discoveryTime)))
                val desc = escapeCsvField(update.briefDescription)
                val url = escapeCsvField(update.url)
                
                writer.write("$siteName,$title,$type,$time,$desc,$url\n")
            }

            writer.flush()
            writer.close()

            shareFile(context, csvFile, "text/csv", "تصدير تقرير Excel")
        } catch (e: Exception) {
            Log.e(TAG, "CSV Export Failed", e)
        }
    }

    /**
     * Exports a list of updates into a clean PDF-Printable HTML document and launches a Share Intent.
     */
    fun exportToHtmlPdf(context: Context, updates: List<UpdateRecord>, sites: List<MonitoredSite>) {
        try {
            val htmlFile = File(context.cacheDirs(), "تقرير_رادار_المواقع_${System.currentTimeMillis()}.html")
            val writer = FileWriter(htmlFile)

            val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.forLanguageTag("ar-u-nu-latn"))
            sdf.timeZone = TimeZone.getTimeZone("Asia/Bahrain")
            val currentTimeStr = sdf.format(Date())

            val htmlContent = """
                <!DOCTYPE html>
                <html lang="ar" dir="rtl">
                <head>
                    <meta charset="UTF-8">
                    <title>تقرير رادار المواقع</title>
                    <style>
                        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 30px; background-color: #f9f9f9; color: #333; }
                        h1 { color: #1a237e; border-bottom: 2px solid #1a237e; padding-bottom: 10px; }
                        .meta-info { margin-bottom: 20px; font-size: 14px; color: #555; }
                        table { width: 100%; border-collapse: collapse; margin-top: 20px; background-color: white; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                        th, td { border: 1px solid #ddd; padding: 12px; text-align: right; }
                        th { background-color: #1a237e; color: white; }
                        tr:nth-child(even) { background-color: #f2f2f2; }
                        .badge { display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }
                        .badge-news { background-color: #e3f2fd; color: #0d47a1; }
                        .badge-report { background-color: #f3e5f5; color: #4a148c; }
                        .badge-alert { background-color: #fff3e0; color: #e65100; }
                    </style>
                </head>
                <body>
                    <h1>📊 تقرير التحديثات والمستجدات المقروءة</h1>
                    <div class="meta-info">
                        <strong>تاريخ استخراج التقرير:</strong> $currentTimeStr بتوقيت البحرين<br>
                        <strong>إجمالي المواقع المتابعة:</strong> ${sites.size} موقعاً | 
                        <strong>إجمالي التحديثات المرصودة:</strong> ${updates.size} تحديثاً
                    </div>
                    <table>
                        <thead>
                            <tr>
                                <th>الموقع</th>
                                <th>عنوان التحديث</th>
                                <th>النوع</th>
                                <th>تاريخ الاكتشاف</th>
                                <th>الوصف الملخص</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${generateHtmlTableRows(updates, sdf)}
                        </tbody>
                    </table>
                </body>
                </html>
            """.trimIndent()

            writer.write(htmlContent)
            writer.flush()
            writer.close()

            shareFile(context, htmlFile, "text/html", "تصدير تقرير PDF/HTML")
        } catch (e: Exception) {
            Log.e(TAG, "HTML Export Failed", e)
        }
    }

    private fun generateHtmlTableRows(updates: List<UpdateRecord>, sdf: SimpleDateFormat): String {
        val rows = StringBuilder()
        for (update in updates) {
            val badgeClass = when (update.type) {
                "خبر" -> "badge badge-news"
                "تقرير" -> "badge badge-report"
                else -> "badge badge-alert"
            }
            rows.append("""
                <tr>
                    <td><strong>${update.siteName}</strong></td>
                    <td><a href="${update.url}" target="_blank">${update.title}</a></td>
                    <td><span class="$badgeClass">${update.type}</span></td>
                    <td>${sdf.format(Date(update.discoveryTime))}</td>
                    <td>${update.briefDescription}</td>
                </tr>
            """.trimIndent())
        }
        return rows.toString()
    }

    private fun escapeCsvField(field: String): String {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\""
        }
        return field
    }

    private fun Context.cacheDirs(): File {
        val cacheDir = File(this.cacheDir, "reports")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    private fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val authority = "${context.packageName}.fileprovider"
        val fileUri: Uri = FileProvider.getUriForFile(context, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
