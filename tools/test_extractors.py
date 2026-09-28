import urllib.request
import json
import re

def test_instagram(url):
    print("Testing Instagram:", url)
    # Extract shortcode
    m = re.search(r'/(?:p|reel|reels)/([A-Za-z0-9_-]+)', url)
    if not m:
        print("No shortcode")
        return
    code = m.group(1)
    embed_url = f"https://www.instagram.com/reel/{code}/embed/captioned/"
    req = urllib.request.Request(embed_url, headers={
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    })
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            content = resp.read().decode('utf-8', errors='ignore')
            # Look for video URL in embed
            matches = re.findall(r'"video_url"\s*:\s*"([^"]+)"', content)
            if matches:
                clean_video_url = matches[0].replace('\\u0026', '&')
                print("SUCCESS: Found Instagram Direct Video URL:", clean_video_url[:120])
                return clean_video_url
            else:
                print("No video_url in embed")
    except Exception as e:
        print("Instagram embed error:", e)

def test_tiktok(url):
    print("Testing TikTok:", url)
    api = f"https://www.tikwm.com/api/?url={url}"
    req = urllib.request.Request(api, headers={'User-Agent': 'Mozilla/5.0'})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
            if data.get('code') == 0:
                play_url = data['data'].get('play')
                title = data['data'].get('title', 'TikTok Video')
                print("SUCCESS: Found TikTok Direct Video URL:", play_url[:100])
                return play_url, title
    except Exception as e:
        print("TikTok error:", e)

test_instagram("https://www.instagram.com/reels/C_0t7nSvO0g/")
test_tiktok("https://www.tiktok.com/@tiktok/video/7106594312292453675")
