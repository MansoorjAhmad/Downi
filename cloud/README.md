# DOWNI Cloud Boost ☁️

**Cloud Boost** is DOWNI's optional extraction relay — the same architecture the top
downloader apps use. When local on-phone extraction is blocked (carrier IP flagged,
Instagram walls, etc.), DOWNI automatically retries the extraction through YOUR tiny
free server, which has a clean IP. The video file itself still downloads straight to
the phone from the platform CDN.

## Deploy (5 minutes, free)

1. Push this repository to GitHub (already done — you're here).
2. Go to [vercel.com/new](https://vercel.com/new) → log in → **Import** this repository.
3. Framework Preset: **Other**. Leave everything else default → **Deploy**.
4. When it finishes, copy your URL, e.g. `https://your-project.vercel.app`.
5. In DOWNI: **Settings → Cloud Boost** → paste `https://your-project.vercel.app` → Save.

That's it. The function lives in `api/extract.py` and runs yt-dlp server-side.

## Verify

Open `https://your-project.vercel.app/api/extract?health=1` in a browser —
it should reply `{"ok":true,"service":"downi-cloud-boost"}`.

## Notes

- Vercel Hobby (free) is enough for personal use.
- The relay only RESOLVES links (a few seconds of CPU); video bytes flow
  directly from the platform CDN to the phone.
- Keep this URL private — anyone with it can use your server's quota.
