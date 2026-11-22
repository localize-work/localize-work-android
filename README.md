# localize.work Android SDK

Loads the translations you publish with [localize.work](https://localize.work)
into an Android app while it runs. You change a text in the editor, publish,
and the app shows it without a new build in the store.

The SDK downloads your project's published release, keeps it on the device, and
answers `Localize.string("nav.home")`. The string resources bundled in your app
stay as the fallback, so the app has every text on first launch and without a
connection.

- Kotlin, usable from Java
- Android 5.0 (API level 21) and newer; your app may compile against any
  platform version from 21 up
- No dependencies beyond the Kotlin standard library
- Sends nothing about the device or the user

## Install

The library is published through [JitPack](https://jitpack.io). Add the JitPack
repository to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.localize-work:localize-work-android:0.1.0")
}
```

<details>
<summary>Groovy build files</summary>

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}

// app/build.gradle
dependencies {
    implementation 'com.github.localize-work:localize-work-android:0.1.0'
}
```

</details>

The library's manifest asks for the `INTERNET` permission and nothing else.
Apps written in Kotlin need Kotlin 2.1 or newer.

## Quick start

Set the SDK up once, in your `Application` class. The only required setting is
your project slug, the name in your project's CDN address.

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Localize.init(
            this,
            LocalizeConfig("my-project") {
                fallbackLocale("en") // usually your project's source language
            },
        )
    }
}
```

`init` reads the release stored on the device, so texts are available at once,
and starts a download of the current release in the background.

Ask for texts anywhere, on any thread:

```kotlin
Localize.string("nav.home")                           // "Home"
Localize.string("greeting", mapOf("name" to "Ann"))   // "Hello, Ann!"
Localize.string("items", count = 3)                   // "3 items"
Localize.string("title", namespace = "checkout")
Localize.string("new.key", fallback = "Not translated yet")
```

To redraw a screen when a download brings new texts:

```kotlin
private val listener = LocalizeListener { version -> render() }

override fun onStart() {
    super.onStart()
    Localize.addListener(listener)
    render()
}

override fun onStop() {
    Localize.removeListener(listener)
    super.onStop()
}
```

The SDK does not check for new releases on a timer. Call `Localize.refresh()`
when it suits your app, for example when it comes back to the foreground:

```kotlin
Localize.refresh { result ->
    when (result.status) {
        RefreshResult.Status.APPLIED -> { /* new texts are in use */ }
        RefreshResult.Status.DOWNLOADED -> { /* stored for the next launch */ }
        RefreshResult.Status.UP_TO_DATE -> { /* nothing new */ }
        RefreshResult.Status.FAILED -> Log.w("App", "refresh failed", result.error)
    }
}
```

Listeners and callbacks are called on the main thread.

### Java

```java
LocalizeConfig config = new LocalizeConfig.Builder("my-project")
        .fallbackLocale("en")
        .build();
Localize.init(this, config);

String home = Localize.string("nav.home");
String greeting = Localize.string("greeting", Collections.singletonMap("name", "Ann"));
String items = Localize.plural("items", 3);

Localize.refresh(result -> {
    if (!result.isSuccess()) Log.w("App", "refresh failed", result.getError());
});
```

## Configuration

| Setting | Default | What it does |
|---|---|---|
| project slug | required | The project to load, as in `https://cdn.localize.work/{slug}/…`. |
| `cdnBaseUrl` | `https://cdn.localize.work` | Where published files are served from. |
| `namespaces` | `translation` | The namespaces to download. |
| `defaultNamespace` | the first namespace | The namespace a lookup uses when it names none. |
| `locale` | the device's first preferred locale | The locale to show, such as `et` or `en-FI`. Spell it the way your project does (see below). |
| `fallbackLocale` | none | The locale tried when a text is missing in `locale`. Usually your source language. |
| `version` | the latest release | Pins one release, such as `"v12"`. A stored release of another version is then never used. |
| `updatePolicy` | `IMMEDIATE` | When a downloaded release takes effect: `IMMEDIATE`, or `NEXT_LAUNCH` so texts never change while the app is open. |
| `refreshOnInit` | `true` | Whether `init` starts a download. |
| `connectTimeoutMillis` | 10 000 | How long to wait for a connection. |
| `readTimeoutMillis` | 15 000 | How long to wait for more data. One download may take two minutes in all. |
| `transport` | built in (`HttpURLConnection`) | Replace the HTTP client, for example with canned answers in a test. |
| `cacheDirectory` | a folder in the app's private storage | Where releases are kept. |

```kotlin
LocalizeConfig("my-project") {
    namespaces("common", "checkout")
    defaultNamespace("common")
    fallbackLocale("en")
    updatePolicy(UpdatePolicy.NEXT_LAUNCH)
}
```

`build()` throws `IllegalArgumentException` for a setting that cannot work, such
as an empty slug or a version that is not `v` followed by a number.

### Choosing the locale

Without a `locale`, the SDK uses the device's first preferred locale as it is
when `init` runs. If your app has its own language picker, pass the choice as
`locale` at start, and call `Localize.setLocale("et")` when the user changes it.
A change of the system language while the app is running is not picked up by
itself; call `setLocale` for it too.

A release is read whole or not at all. When the device already has every file
of the new locale, `setLocale` shows them at once. Otherwise the app shows its
bundled strings, which are in the user's language, until the missing files
have been downloaded; the same holds at start when the language has changed
since the release on the device was stored.

A locale is requested from the CDN as it is written, with only its letter case
corrected (`en_fi` becomes `en-FI`), anything a device adds after the region
dropped, and the three codes older Android versions still report replaced
(`iw`, `in`, `ji` become `he`, `id`, `yi`). So spell `locale` and
`fallbackLocale` the way your project's languages are spelled: a project with
`sr-Latn-RS` does not answer to `sh-RS`.

## How a text is found

### 1. The locale chain

For the locale `en-FI` with the fallback locale `et`, a key is looked up in
`en-FI`, then `en`, then `et`. Files are downloaded for every locale in the
chain and every namespace.

A regional variant such as `en-FI` usually publishes only the texts it words
differently, and no file at all for a namespace it does not touch. A missing
file (HTTP 404) is therefore treated as an empty file, and the base language
supplies the text.

### 2. The key

Published files nest keys on their dots, so `nav.home` is read from
`{ "nav": { "home": "Home" } }`. The lookup follows i18next:

- The key is split on `.` and the nested objects are walked.
- When that does not lead to a text, the remaining parts are tried joined into
  one name at each level. This finds keys that localize.work writes under their
  full dotted name: when a project has both `a` and `a.b`, the file is
  `{ "a": "…", "a.b": "…" }`.
- An empty text in a file is a value and is returned as `""`. Only a key that is
  missing falls through to the next locale.
- A key that names a group of keys, not a text, counts as missing.

### 3. Plural forms

With a `count`, the SDK looks in each file for, in this order:

1. `key_zero`, when the count is 0 and the file has that form
2. `key_<category>`, the plural category of the count in the file's language
   (`one`, `two`, `few`, `many`, `other`)
3. `key`

That is i18next's order. A file that has none of them leaves the key to the
next locale, so a regional locale that overrides only `items_other` still gets
`items_one` from its base language. Only when no locale has the form or the
plain key is a locale's `key_other` used, as a last resort.

The category comes from the language of the file being read, so a Russian text
uses Russian rules and an English fallback uses English rules.

The rules follow Unicode CLDR 47 for whole numbers and are built into the
library, so every Android version picks the same form. CLDR 47 is the release
localize.work itself uses to decide which plural forms a language has, and the
iOS SDK uses the same one, so a count takes the same form everywhere. They
cover every language that release has plural rules for:

| Forms | Languages |
|---|---|
| `other` only | bm bo dz hnj id ig ii ja jbo jv jw kde kea km ko lkt lo ms my nqo osa sah ses sg su th to tpi vi wo yo yue zh |
| `one` for 1 | af an asa ast az bal bem bez bg brx ce cgg chr ckb da de dv ee el en eo et eu fi fo fur fy gl gsw ha haw hu ia io jgo jmc ka kaj kcg kk kkj kl ks ksb ku ky lb lg lij mas mgo ml mn mr nah nb nd ne nl nn nnh no nr ny nyn om or os pap ps rm rof rwk saq sc sd sdh seh sn so sq ss ssy st sv sw syr ta te teo tig tk tn tr ts ug ur uz ve vo vun wae xh xog yi |
| `one` for 0 and 1 | ak am as bho bn csw doi fa ff gu guw hi hy kab kn ln mg nso pa pcm si ti wa zu |
| `one` for 1, `many` for whole millions | ca es it lld pt-PT scn vec |
| `one` for 0 and 1, `many` for whole millions | fr pt |
| `one`, `few`, `many` | be ru uk, pl |
| `one`, `few` | bs hr sh sr, cs sk, lt, mo ro, shi |
| `one`, `two`, `few` | dsb hsb sl, gd |
| `one`, `two` | he iu naq sat se sma smi smj smn sms |
| `zero`, `one` | blo ksh lag, lv prg |
| `one`, by a rule of their own | ceb fil tl, is mk, tzm |
| up to six forms | ar ars, br, cy, ga, gv, kw, mt |

Every language also has the `other` form. A language that is not in the table uses `one` for exactly 1 and `other` for
everything else.

Later CLDR releases changed a few languages (Vietnamese gained a `one` form,
Galician a `many` form). The SDK stays on the release the product publishes
with, because a project's files hold the forms that release names.

### 4. Placeholders

| In the text | Result |
|---|---|
| `{{name}}`, `{{ name }}` | The value passed under `name`. |
| `{{count}}` | The count of a plural lookup. |
| `{{price, currency}}` | The value of `price`. The format name is ignored; format the value in your code. |
| `{{- name}}` | The same as `{{name}}`. |
| `{{unknown}}` | Left exactly as written. |

Values are inserted as they are. There is no HTML escaping, and `$t(…)` nesting
is not expanded.

### 5. When no file has the text

The SDK then returns, in this order:

1. **Your app's own string resource**, if it has one of the matching name.
2. The `fallback` you passed to the call.
3. The key itself.

The resource name is the one localize.work's Android export writes: the
namespace and the key joined by `_`, with every character other than a letter,
digit or underscore replaced by `_`.

| Namespace | Key | Resource name |
|---|---|---|
| `translation` | `nav.home` | `translation_nav_home` |
| `checkout` | `pay-now` | `checkout_pay_now` |
| `strings` | `nav.home` | `nav_home` (a namespace called `strings` gets no prefix) |
| `strings` | `404.title` | `_404_title` (a name may not start with a digit) |

So the simplest way to bundle a fallback is to export your project from
localize.work in the Android format and put the files into `res/`. With a
count, a `<plurals>` resource of that name is used.

The export writes placeholders as numbered arguments (`%1$s`, `%2$s`), numbered
in the order the variables first appear in the source text. The SDK fills them
by position: the first entry of the map you pass is argument 1, the second is
argument 2. `mapOf` and `LinkedHashMap` keep the order you write. The count of a
plural takes the position of the text's `%1$d`; in a plural form that does not
show the count, it takes the position that form leaves unused.

Bundled strings come in the language Android picks for your app. If you set
`locale` yourself, or call `Localize.setLocale`, on a system where that does
not change the app's own resources, the bundled fallback can be in another
language than the downloaded texts.

If your release build removes unused resources, keep your strings, because the
SDK looks them up by name while the app runs. Add `res/raw/keep.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@string/*,@plurals/*" />
```

## Downloads, updates and working offline

**One release at a time.** A refresh reads `latest.json` once to learn the
current release (for example `v12`), then downloads every file from that
release. If you publish while a download is running, the app still gets all of
the old release; the next refresh gets all of the new one. Texts from two
releases are never mixed.

**All or nothing.** A release takes effect only when every file it needs has
arrived. If one download fails, the refresh fails and the release already in
use stays in use.

**Stored on the device.** The last complete release is kept in the app's
private storage (`no_backup/localize-work/`), and `init` loads it before it
returns. The app therefore starts with its texts and works offline. Older
releases are deleted. A stored release that cannot be read is thrown away and
downloaded again; it never crashes the app. Releases are stored per project,
and a change of `cdnBaseUrl` discards them.

**Immediately or at the next launch.** With `UpdatePolicy.IMMEDIATE` a new
release is used as soon as it is complete, and listeners are told. With
`UpdatePolicy.NEXT_LAUNCH` it is stored and used from the next start, so no text
changes while the user is looking at it. On the very first launch that means
the app shows its bundled strings until it is started again.

**Failures.** A request that gets no answer, or a server error (5xx), is tried
up to three times. Anything else is final for that refresh:

| What happened | Result |
|---|---|
| `latest.json` returns 404 | Failed. Nothing has been published for the project yet, or the slug is wrong. |
| `latest.json` does not name a release like `v12` | Failed, as a configuration problem. Not retried. |
| A translation file returns 404 | Treated as an empty file. Normal for regional variants. |
| A translation file returns another 4xx, or is not JSON | Failed. |
| A pinned `version` has no files at all | Failed, so a release that is not published yet is picked up once it is. |

`result.error.isRetryable` tells you whether trying again later can help.

**Rollbacks.** If you roll a project back to an earlier release, the app follows
at its next refresh.

## Privacy

The SDK makes plain `GET` requests for public files and nothing else. It sends
no device identifier, no user identifier and no analytics, and it sets its own
`User-Agent` (`localize-work-android/0.1.0`) in place of the system default,
which names the device model. Requests are not authenticated. As with any
download, the server sees the IP address the request comes from.

The built-in transport uses Android's own HTTP stack. If your app installs a
process-wide `CookieHandler`, that handler also applies to these requests, as
it does to every `HttpURLConnection` in the app: cookies it holds for the CDN's
host are sent along. The localize.work CDN sets no cookies. If you serve the
files from a host of your own and do not want your app's cookies sent to it,
pass your own `transport`.

## Limits of 0.1.0

- Texts are looked up through `Localize.string()`. The SDK does not replace
  `getString()`, texts in layout XML, or Compose's `stringResource()`.
- Plural counts are whole numbers. Decimal counts and ordinals ("1st", "2nd")
  are not supported.
- From i18next's features, `$t(…)` nesting, value formatting, context
  (`key_male`) and returning a whole group of keys are not supported.
- One locale at a time. A device locale is matched by dropping parts from the
  right (`zh-Hant-TW`, `zh-Hant`, `zh`); `zh-TW` does not find `zh-Hant`.
- New releases are downloaded only when `init` or `refresh()` runs.
- Files are downloaded one after another.
- Missing keys are not reported back to localize.work.

## Building from source

You need JDK 17 and the Android SDK (platform 36, build-tools 36.0.0). Point
`local.properties` at the SDK (`sdk.dir=/path/to/android-sdk`) or set
`ANDROID_HOME`.

```sh
./gradlew test                 # unit tests, on the JVM, no device needed
./gradlew assembleRelease      # build/outputs/aar/localize-work-android-release.aar
./gradlew publishToMavenLocal  # install into ~/.m2 for a local app build
```

The plural rules are tested against vectors generated from the CLDR data:

```sh
node tools/generate-plural-vectors.mjs
```

## License

[MIT](LICENSE)
