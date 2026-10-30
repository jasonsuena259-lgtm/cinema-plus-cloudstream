package com.cimanow

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CimaNowPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = CimaNowSettings(context)
        val transport = CimaNowTransport(context.applicationContext, prefs)
        registerMainAPI(CimaNowProvider(transport))
        openSettings = { ctx -> showSettings(ctx, prefs, transport) }
    }

    private fun bg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#1E202B"))
        cornerRadius = 14f
    }

    private fun showSettings(ctx: Context, prefs: CimaNowSettings, transport: CimaNowTransport) {
        val scroll = ScrollView(ctx).apply { setBackgroundColor(Color.parseColor("#121218")) }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(ctx).apply {
            text = "إعدادات سيما ناو العادي (CimaNow)"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        }
        val active = TextView(ctx).apply {
            text = "الدومين الحالي: ${prefs.visibleBase}\nإضافة مجانية مستقلة عن CimaNow VIP. الدعم التلقائي يعتمد على توفر الموقع، ولا يتجاوز CAPTCHA."
            setTextColor(Color.parseColor("#ABB1BF"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        }
        val domain = EditText(ctx).apply {
            hint = "اتركه فارغاً للاكتشاف (مثال: https://cimanow.cc)"
            setHintTextColor(Color.parseColor("#8791A3"))
            setTextColor(Color.WHITE)
            setSingleLine(true)
            textSize = 13f
            background = bg()
            setPadding(16, 16, 16, 16)
            setText(prefs.customDomain.orEmpty())
        }
        val switch = Switch(ctx).apply {
            text = "تفعيل البروكسي المخصص"
            isChecked = prefs.useProxy
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
        }
        val proxy = EditText(ctx).apply {
            hint = "socks5://127.0.0.1:9050 أو http://host:8080"
            setHintTextColor(Color.parseColor("#8791A3"))
            setTextColor(Color.WHITE)
            setSingleLine(true)
            textSize = 13f
            background = bg()
            setPadding(16, 16, 16, 16)
            setText(prefs.customProxy)
        }
        val check = Button(ctx).apply {
            text = "فتح الموقع للتحقق من Cloudflare يدوياً"
            setOnClickListener { openWebsite(ctx, prefs.visibleBase) }
        }
        column.addView(title)
        column.addView(active)
        column.addView(domain)
        column.addView(switch)
        column.addView(proxy)
        column.addView(check)
        scroll.addView(column)
        val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("إعادة الضبط التلقائي", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawDomain = domain.text.toString().trim()
                if (rawDomain.isNotEmpty() && CimaNowSettings.normalizeDomain(rawDomain) == null) {
                    domain.error = "ضع رابط HTTPS صحيحاً، بلا كلمة مرور أو مسار غير ضروري"
                    return@setOnClickListener
                }
                val rawProxy = proxy.text.toString().trim()
                if (switch.isChecked && CimaNowSettings.parseProxy(rawProxy) == null) {
                    proxy.error = "ضع بروكسي صحيحاً SOCKS5 أو HTTP مع المنفذ"
                    return@setOnClickListener
                }
                prefs.customDomain = rawDomain.ifEmpty { null }
                prefs.customProxy = rawProxy
                prefs.useProxy = switch.isChecked
                prefs.discoveredDomain = null
                transport.resetCache()
                Toast.makeText(ctx, "حُفظت إعدادات CimaNow العادية", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                prefs.reset()
                transport.resetCache()
                Toast.makeText(ctx, "تمت إعادة الإعدادات للوضع التلقائي", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Only a visible user-operated browser can solve an interactive website challenge. */
    private fun openWebsite(ctx: Context, base: String) {
        val dialog = Dialog(ctx)
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(ctx).apply {
            text = "إذا ظهر تحقق Cloudflare، أكمله بنفسك، ثم ارجع للإضافة. لا تشارك الكوكيز مع أي شخص."
            setPadding(18, 14, 18, 14)
        }
        val browser = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return request.isForMainFrame && request.url.scheme !in listOf("http", "https")
                }
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.cancel()
                }
            }
        }
        val close = Button(ctx).apply {
            text = "إغلاق والعودة للإضافة"
            setOnClickListener { CookieManager.getInstance().flush(); dialog.dismiss() }
        }
        layout.addView(hint)
        layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(close)
        dialog.setContentView(layout)
        dialog.setOnDismissListener { browser.stopLoading(); browser.destroy() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        browser.loadUrl(base.trimEnd('/') + "/home/")
    }
}
