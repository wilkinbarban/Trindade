# Offline translation

`translateText(text, fromLang)` keeps its asynchronous ES/PT interface and trims input.
Known phrases use the local dictionary; other text uses local word substitutions,
preserving unknown words. Translation never sends text to external providers;
`GOOGLE_TRANSLATE_API_KEY` has no effect. Empty input returns an empty string.
