import urllib.request
import json
import re

def test_insta_api(shortcode):
    # Method 1: Instagram GraphQL Doc ID
    # Modern public Doc ID for Instagram posts
    doc_id = "8845758582119845"
    variables = json.dumps({"shortcode": shortcode, "child_comment_count": 3, "fetch_comment_count": 40, "parent_comment_count": 24, "has_threaded_comments": True})
    url = f"https://www.instagram.com/graphql/query/?doc_id={doc_id}&variables={urllib.parse.quote(variables)}"
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36',
        'X-IG-App-ID': '936619743392459',
        'Accept': '*/*',
    })
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            data = json.loads(r.read().decode())
            media = data.get('data', {}).get('xdt_shortcode_media', {})
            video_url = media.get('video_url')
            if video_url:
                print("SUCCESS via GraphQL Doc ID:", video_url[:100])
                return video_url
            else:
                print("No video_url in GraphQL response")
    except Exception as e:
        print("GraphQL error:", e)

import urllib.parse
test_insta_api("C_0t7nSvO0g")
