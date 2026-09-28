with open('tools/ig_embed_dump.html', encoding='utf-8') as f:
    text = f.read()

import re
print("Has video tag:", "<video" in text)
print("Has mp4:", ".mp4" in text)
matches = re.findall(r'https[^\'"\s<>]+\.mp4[^\'"\s<>]*', text)
print("mp4 links count:", len(matches))
for m in matches[:5]:
    print("Found MP4 link:", m[:100])
