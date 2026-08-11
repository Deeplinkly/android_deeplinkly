package com.deeplinkly.sample

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.deeplinkly.android_deeplinkly.Deeplinkly
import com.deeplinkly.android_deeplinkly.DeeplinklyContent
import com.deeplinkly.android_deeplinkly.DeeplinklyLinkOptions

/**
 * Views built in code rather than XML so the whole sample is readable in two
 * files. What matters here is [onNewIntent], not the layout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val app get() = application as SampleApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        app.onLinksChanged = { runOnUiThread { render() } }
        render()
    }

    /**
     * The one thing a native integration must not forget.
     *
     * `ActivityLifecycleCallbacks` has no `onNewIntent` hook, so without this a
     * link arriving at an already-running activity - which is every link tapped
     * while the app is open, given `launchMode="singleTop"` - never reaches the
     * SDK. Cold starts are captured automatically and need nothing here.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Deeplinkly.onNewIntent(this, intent)
    }

    override fun onDestroy() {
        app.onLinksChanged = null
        super.onDestroy()
    }

    private fun render() {
        val id = Deeplinkly.getDeeplinklyId()
        val attribution = Deeplinkly.getInstallAttribution()
        output.text = buildString {
            appendLine("SDK enabled:  ${Deeplinkly.isEnabled}")
            appendLine("SDK version:  ${Deeplinkly.version}")
            appendLine("Install id:   $id")
            appendLine("Attribution:  ${attribution.ifEmpty { "(none yet)" }}")
            appendLine()
            if (app.links.isEmpty()) {
                appendLine("No deep links yet.")
                appendLine()
                appendLine("adb shell am start -a android.intent.action.VIEW \\")
                appendLine("  -d \"deeplinkly-sample://open?click_id=abc123\"")
            } else {
                appendLine("Deep links (${app.links.size}), newest first:")
                appendLine()
                app.links.forEachIndexed { i, l ->
                    appendLine("[${app.links.size - i}]")
                    appendLine(l)
                    appendLine()
                }
            }
        }
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "Deeplinkly — native sample"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        column.addView(button("Log a test event") {
            Deeplinkly.logEvent(
                "sample_button_tap",
                mapOf("screen" to "main", "amount" to 49.99),
            ) { accepted -> toast(if (accepted) "event accepted" else "event rejected") }
        })

        column.addView(button("Generate a link") {
            Deeplinkly.generateLink(
                content = DeeplinklyContent(
                    canonicalIdentifier = "sample/item_1",
                    title = "Sample item",
                    metadata = mapOf("from" to "native_sample"),
                ),
                options = DeeplinklyLinkOptions(channel = "sample", feature = "share"),
            ) { result ->
                toast(if (result.success) result.url ?: "no url" else "failed: ${result.errorCode}")
            }
        })

        column.addView(button("Set user id") {
            Deeplinkly.setUserId("sample_user_1")
            toast("user id set")
        })

        output = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        column.addView(output)

        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(column)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) = runOnUiThread {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        render()
    }
}
