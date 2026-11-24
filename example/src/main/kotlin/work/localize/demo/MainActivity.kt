package work.localize.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import work.localize.sdk.Localize
import work.localize.sdk.LocalizeConfig
import work.localize.sdk.LocalizeListener
import work.localize.sdk.RefreshResult
import work.localize.sdk.UpdatePolicy

/**
 * One screen that shows a handful of texts through the SDK, with the release
 * and the locale they are read in.
 *
 * To try it with a project of your own, start it with the project's slug:
 *
 *     adb shell am start -n work.localize.demo/.MainActivity --es slug my-project --es locale et
 *
 * The extras are `slug`, `cdn` (another address than the default CDN),
 * `locale` and `policy` (`next_launch`). They are read once, when the app's
 * process starts.
 */
class MainActivity : Activity() {
    /** Called on the main thread when a release starts being read and when the locale changes. */
    private val listener = LocalizeListener { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        // An app of your own calls Localize.init in Application.onCreate, with
        // its slug written in the code. The demo takes its settings from the
        // intent that started it, so one build can be pointed at any project.
        // `locale` is null until init has run: this is done once per process.
        if (Localize.locale == null) Localize.init(applicationContext, configFrom(intent))

        for ((button, locale) in mapOf(R.id.lang_en to "en", R.id.lang_et to "et", R.id.lang_de to "de")) {
            // The listener redraws the screen, at once when the device has the
            // locale's files and otherwise when they have been downloaded.
            findViewById<View>(button).setOnClickListener { Localize.setLocale(locale) }
        }
        findViewById<View>(R.id.refresh).setOnClickListener {
            show(R.id.status, "…")
            Localize.refresh { result -> show(R.id.status, describe(result)) }
        }
    }

    override fun onStart() {
        super.onStart()
        Localize.addListener(listener)
        render()
    }

    override fun onStop() {
        Localize.removeListener(listener)
        super.onStop()
    }

    private fun configFrom(intent: Intent): LocalizeConfig =
        LocalizeConfig(intent.getStringExtra("slug") ?: "my-project") {
            intent.getStringExtra("cdn")?.let { cdnBaseUrl(it) }
            // Without a locale the SDK uses the device's language.
            intent.getStringExtra("locale")?.let { locale(it) }
            // The project's source language: tried when a text is missing in the locale.
            fallbackLocale("en")
            if (intent.getStringExtra("policy") == "next_launch") updatePolicy(UpdatePolicy.NEXT_LAUNCH)
        }

    private fun render() {
        show(R.id.version, Localize.version ?: "none")
        show(R.id.locale, Localize.locale.orEmpty())
        show(R.id.title, Localize.string("title"))
        show(R.id.greeting, Localize.string("greeting", mapOf("name" to "Ann")))
        show(R.id.items_one, Localize.string("items", count = 1))
        show(R.id.items_five, Localize.string("items", count = 5))
        // Published in the source language only.
        show(R.id.only_source, Localize.string("only_source"))
        // Not published: it comes from the app's own string resources.
        show(R.id.only_bundled, Localize.string("only_bundled"))
        // Neither bundled nor, at first, published: the fallback of the call is shown.
        show(R.id.added_later, Localize.string("added_later", fallback = "Not published yet"))
        // Found nowhere and given no fallback: the key itself comes back.
        show(R.id.missing, Localize.string("missing"))
    }

    private fun describe(result: RefreshResult): String = when (result.status) {
        RefreshResult.Status.FAILED ->
            "FAILED ${result.error?.reason}" + if (result.error?.isRetryable == true) ", can be retried" else ""
        else -> "${result.status} ${result.version.orEmpty()}".trim()
    }

    private fun show(view: Int, text: String) {
        findViewById<TextView>(view).text = text
    }
}
