# Changelog

All notable changes to this library are listed here. Versions follow
[semantic versioning](https://semver.org); until 1.0.0 a minor version may
change the API.

## 0.1.0

The first release.

- Downloads a project's published translations from the localize.work CDN:
  resolves `latest.json` once per refresh and reads every file from that one
  release, or reads a pinned release directly.
- Switches to a downloaded release only when all of its files have arrived,
  either at once or at the next launch of the app.
- Keeps the last complete release on the device, so texts are available at
  start and without a connection; a cache that cannot be read is discarded.
- Looks a text up in the locale, its base language and a fallback locale, with
  i18next's key rules, plural forms for whole numbers (CLDR 49, every language
  CLDR covers) and `{{name}}` placeholders.
- Falls back to the app's own string resources, named the way localize.work's
  Android export names them.
- A listener reports when texts change; `setLocale` switches the locale while
  the app runs.
- No dependencies beyond the Kotlin standard library. Requires Android 5.0
  (API level 21).
