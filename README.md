# AI Keyboard (Android 8.0+)

English + Bangla keyboard with AI writing help, voice typing, clipboard, emoji, GIFs and stickers.

## Build the APK
**GitHub:** upload everything in this folder to a repository (keep the folder structure, including `.github`),
open Actions > "Build APK", wait for the green tick, download the `AiKeyboard-APK` artifact, install `app-debug.apk`.
**Android Studio:** File > Open this folder, then Build > Build APK(s).

## Set up
Open the app: 1) Enable, 2) Choose, 3) Allow microphone, then paste your keys and tap "Save and test AI connection".
- AI key: free Gemini key from aistudio.google.com/apikey (or any OpenAI-compatible API).
- GIF/sticker key: free GIPHY key from developers.giphy.com.

## Toolbar
✨ Improve (one tap, mode button picks Fix / Polish / Formal / Casual / Short) · 💡 three versions (Fix, Better, Best) ·
✍ tell the AI what to do (chips: Polite, → Bangla, Reply ...; "Use copied text" adds the copied message as context) ·
🎤 voice · 📋 clipboard · 😊 emoji / GIF / sticker · ↩ undo · ⚙ settings.
Select text first to change only that part, otherwise the whole text box is used.

## Bangla phonetic cheat sheet
ami = আমি, bangla = বাংলা, small o is silent, capital O = ো (bhalO = ভালো), z = য, y = য়,
T D N S R = ট ড ণ শ ড়, kkh = ক্ষ, Gg = জ্ঞ, rri = ঋ, ng = ং, ^ = ঁ, : = ঃ, the "." key types ।.
Consecutive consonants join automatically (ostitto, bakzo = বাক্য).

## Privacy
Text is sent only when you tap an AI button. Password and incognito fields are never sent.
Clipboard history stays in memory only (never saved to disk) and clips marked sensitive are skipped.
