import urllib.request
import json
import re

def test_instagram_reel(url):
    print("Testing Instagram Reel:", url)
    m = re.search(r'/(?:p|reel|reels)/([A-Za-z0-9_-]+)', url)
    if not m:
        print("No shortcode")
        return
    code = m.group(1)
    
    # Method: Instagram /?__a=1 or embed GraphQL
    embed_url = f"https://www.instagram.com/reel/{code}/embed/captioned/"
    req = urllib.request.Request(embed_url, headers={
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
    })
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            content = resp.read().decode('utf-8', errors='ignore')
            print("Response length:", len(content))
            with open("tools/ig_embed_dump.html", "w", encoding="utf-8") as f:
                f.write(content)
            # Find video URLs
            matches = re.findall(r'https://[^"\'\s]+\.mp4[^"\'\s]*', content)
            print("Found MP4 links count:", len(matches))
            if matches:
                for match in matches[:3]:
                    clean = match.replace('\\u0026', '&').replace('&amp;', '&')
                    print("Match:", clean[:80])
    except Exception as e:
        print("Error:", e)

test_instagram_reel("https://www.instagram.com/reels/C_0t7nSvO0g/")
