# Workspace Rules & Instructions

## Git & Deployment Directives
- **NEVER push to GitHub without explicit permission:** Under no circumstances should `git push` or automated remote synchronization be executed unless the user explicitly requests or confirms it. All git operations must remain strictly local until permission is given.

## Project Guidelines (DOWNI)
- **Permanent product rulings (do not re-open):** see `DECISIONS.md`. In short: **pause/resume is permanently removed and banned** (no `.part` continuation, no resumable job state, no Pause/Resume control in the app, notification, or engine). **Fonts are permanently frozen.** The Anti-Repost Shield stays and may only be strengthened.
- **Pre-Build Asset Sync:** Always ensure `npx cap sync android` is run whenever web assets in `www/` are modified so that `android/app/src/main/assets/public/` stays synchronized.
- **Truth Layer Integrity:** Preserve and run `python tools/test_truth.py` and `node tools/_tabcheck.js` before finalizing builds.
- **Dedicated Desktop Directory:** Never dump loose APKs, downloaded media, test scripts, or logs onto the root Desktop (`C:\Users\Manso\Desktop`). All build outputs, APK releases, and temporary test directories must strictly be placed inside `C:\Users\Manso\Desktop\DOWNI\`.
