import yt_dlp

url = 'https://www.instagram.com/reels/C_0t7nSvO0g/'

agents = [
    'Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 289.0.0.25.49',
    'Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
]

for ua in agents:
    print('Testing UA:', ua[:40])
    opts = {
        'quiet': True,
        'http_headers': {
            'User-Agent': ua,
            'Accept-Language': 'en-US,en;q=0.9',
        }
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            print('SUCCESS for UA! Title:', info.get('title'))
            break
    except Exception as e:
        print('Failed:', type(e).__name__)
