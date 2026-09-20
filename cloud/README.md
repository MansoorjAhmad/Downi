# DOWNI Cloud Boost ☁️

**Cloud Boost** is DOWNI's built-in extraction relay. When local on-phone extraction
is blocked, DOWNI automatically retries through `https://downi-booster.vercel.app`.
The video file itself still downloads straight to the phone from the platform CDN.

## Deploy (5 minutes, free)

1. Push this repository to GitHub.
2. Deploy it through [vercel.com/new](https://vercel.com/new) using the repository root.
3. Keep the production domain `https://downi-booster.vercel.app` connected to that project.

That's it. The function lives in `api/extract.py` and runs yt-dlp server-side.

## Verify

Open `https://downi-booster.vercel.app/api/extract?health=1` in a browser —
it should reply `{"ok":true,"service":"downi-cloud-boost"}`.

## Notes

- Vercel Hobby (free) is enough for light personal testing; production usage needs monitoring of Vercel's quotas.
- The relay only RESOLVES links (a few seconds of CPU); video bytes flow
  directly from the platform CDN to the phone.
- This relay is a public app dependency, so protect it with sensible Vercel quota monitoring and abuse controls.
