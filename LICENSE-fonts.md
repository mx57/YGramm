# Bundled fonts

Both files under `app/src/main/res/font/` are subsets built with `fontTools` from the
[google/fonts](https://github.com/google/fonts) sources, instantiated at weight 400 and
subset to the codepoints the app uses plus the full Cyrillic range `U+0400–U+04FF`.

| File | Family | Copyright | License |
| --- | --- | --- | --- |
| `jetbrains_mono.ttf` | JetBrains Mono | Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono) | SIL Open Font License 1.1 |
| `golos_text.ttf` | Golos Text | Copyright 2019 The Golos Text Project Authors (https://github.com/googlefonts/golos-text) | SIL Open Font License 1.1 |

The OFL text and copyright notice travel inside each TTF's `name` table, so the APK ships
them with the font. The full license is also published at
https://openfontlicense.org.

Golos Text replaced Space Grotesk, which has no Cyrillic coverage at all: Russian strings
rendered in the system fallback font while Latin text and digits used the brand face.
