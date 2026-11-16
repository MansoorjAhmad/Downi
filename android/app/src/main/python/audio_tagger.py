"""Pure-Python audio metadata & cover-art tagger (zero subprocesses, zero C deps).

Supports:
- .mp3: ID3v2.3 header with TIT2 (title), TPE1 (artist), APIC (cover art)
- .m4a: ISO BMFF moov/udta/meta/ilst atoms (©nam, ©ART, covr) with stco/co64
        chunk-offset adjustment whenever moov precedes mdat.

All operations are non-fatal: on any error, the original file is preserved
intact and False is returned.
"""

import os
import struct
import tempfile
import urllib.request


_USER_AGENT = (
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
    'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
)


def _fetch_single_thumbnail(url_str, ssl_context=None, timeout=15):
    if not url_str or not str(url_str).startswith(('http://', 'https://')):
        return None, None, None
    try:
        u = str(url_str).strip()
        headers = {'User-Agent': _USER_AGENT}
        if 'tiktok' in u.lower() or 'tikwm' in u.lower():
            headers['Referer'] = 'https://www.tiktok.com/'
        req = urllib.request.Request(u, headers=headers)
        kwargs = {'timeout': timeout}
        if ssl_context is not None:
            kwargs['context'] = ssl_context
        with urllib.request.urlopen(req, **kwargs) as resp:
            ctype = str(getattr(resp, 'headers', {}).get('Content-Type', '') or '').lower() if hasattr(resp, 'headers') else ''
            data = resp.read(5 * 1024 * 1024)  # cap at 5 MB
        if not data:
            return None, None, None
        head = data[:16].lstrip()
        if 'text/html' in ctype or 'application/json' in ctype or head.startswith((b'<', b'{')):
            return None, None, None
        if data[:8] == b'\x89PNG\r\n\x1a\n':
            return data, 'image/png', 14
        if data[:3] == b'\xff\xd8\xff':
            return data, 'image/jpeg', 13
        # Fallback: treat unknown binary image bytes as JPEG if non-empty
        return data, 'image/jpeg', 13
    except Exception:
        return None, None, None


def _fetch_thumbnail(thumb_url, ssl_context=None, timeout=15):
    """Fetch cover-art bytes from a URL or candidate URL list. Returns (bytes, mime, mp4_type) or (None, None, None)."""
    if not thumb_url:
        return None, None, None
    candidates = [thumb_url] if isinstance(thumb_url, str) else list(thumb_url)
    seen = set()
    for cand in candidates:
        u = str(cand or '').strip()
        if not u or u in seen:
            continue
        seen.add(u)
        img, mime, mp4_type = _fetch_single_thumbnail(u, ssl_context=ssl_context, timeout=timeout)
        if img:
            return img, mime, mp4_type
    return None, None, None


def _atomic_write_bytes(path, data_chunks):
    """Write chunks atomically to a sibling temp file and replace path."""
    parent = os.path.dirname(os.path.abspath(path))
    fd, tmp_path = tempfile.mkstemp(prefix='.tag_', dir=parent)
    try:
        with os.fdopen(fd, 'wb') as out:
            for chunk in data_chunks:
                out.write(chunk)
        os.replace(tmp_path, path)
    except Exception:
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except OSError:
            pass
        raise


# ---------------------------------------------------------------------------
# MP3 (ID3v2.3)
# ---------------------------------------------------------------------------

def _syncsafe_encode(val):
    return bytes([
        (val >> 21) & 0x7F,
        (val >> 14) & 0x7F,
        (val >> 7) & 0x7F,
        val & 0x7F,
    ])


def _syncsafe_decode(b):
    return ((b[0] & 0x7F) << 21) | ((b[1] & 0x7F) << 14) | ((b[2] & 0x7F) << 7) | (b[3] & 0x7F)


def _id3_text_frame(frame_id, text):
    # ID3v2.3 UTF-16 with BOM (encoding byte 0x01)
    payload = b'\x01' + str(text).encode('utf-16')
    return frame_id.encode('ascii') + struct.pack('>IH', len(payload), 0) + payload


def _id3_apic_frame(img_bytes, mime):
    payload = b'\x00' + mime.encode('ascii') + b'\x00\x03\x00' + img_bytes
    return b'APIC' + struct.pack('>IH', len(payload), 0) + payload


def _tag_mp3(path, title, artist, img_bytes, mime):
    with open(path, 'rb') as f:
        raw = f.read()

    audio_offset = 0
    if len(raw) >= 10 and raw[:3] == b'ID3':
        flags = raw[5]
        tag_size = _syncsafe_decode(raw[6:10])
        audio_offset = 10 + tag_size + (10 if (flags & 0x10) else 0)
        if audio_offset > len(raw):
            audio_offset = 0

    frames = []
    if title:
        frames.append(_id3_text_frame('TIT2', title))
    if artist:
        frames.append(_id3_text_frame('TPE1', artist))
    if img_bytes and mime:
        frames.append(_id3_apic_frame(img_bytes, mime))

    if not frames:
        return False

    body = b''.join(frames)
    header = b'ID3\x03\x00\x00' + _syncsafe_encode(len(body))
    _atomic_write_bytes(path, (header, body, raw[audio_offset:]))
    return True


# ---------------------------------------------------------------------------
# M4A / MP4 (ISO BMFF moov/udta/meta/ilst + stco/co64 offset adjustment)
# ---------------------------------------------------------------------------

def _mp4_box(box_type, payload):
    size = 8 + len(payload)
    return struct.pack('>I4s', size, box_type) + payload


def _mp4_ilst_item(tag_type, data_type, value_bytes):
    # 'data' atom: size + 'data' + 4-byte type + 4-byte locale (0) + value
    data_payload = struct.pack('>II', data_type, 0) + value_bytes
    return _mp4_box(tag_type, _mp4_box(b'data', data_payload))


def _iter_boxes(buf, start, end):
    """Yield (box_offset, header_size, box_size, box_type) for boxes in buf[start:end]."""
    pos = start
    while pos + 8 <= end:
        size32, btype = struct.unpack_from('>I4s', buf, pos)
        if size32 == 1:
            if pos + 16 > end:
                break
            size = struct.unpack_from('>Q', buf, pos + 8)[0]
            hdr = 16
        elif size32 == 0:
            size = end - pos
            hdr = 8
        else:
            size = size32
            hdr = 8
        if size < hdr or pos + size > end:
            break
        yield pos, hdr, size, btype
        pos += size


def _build_meta_box(title, artist, img_bytes, mp4_img_type):
    ilst_items = []
    if title:
        ilst_items.append(_mp4_ilst_item(b'\xa9nam', 1, str(title).encode('utf-8')))
    if artist:
        ilst_items.append(_mp4_ilst_item(b'\xa9ART', 1, str(artist).encode('utf-8')))
    if img_bytes:
        ilst_items.append(_mp4_ilst_item(b'covr', mp4_img_type or 13, img_bytes))
    if not ilst_items:
        return None

    # Standard 'hdlr' box inside 'meta' (mdir / appl)
    hdlr_payload = (
        b'\x00\x00\x00\x00'  # version + flags
        + b'\x00\x00\x00\x00'  # pre_defined
        + b'mdir'              # handler_type
        + b'appl'              # manufacturer
        + b'\x00' * 8          # reserved
        + b'\x00'              # empty name
    )
    hdlr = _mp4_box(b'hdlr', hdlr_payload)
    ilst = _mp4_box(b'ilst', b''.join(ilst_items))
    # 'meta' is a FullBox (4-byte version/flags before children)
    return _mp4_box(b'meta', b'\x00\x00\x00\x00' + hdlr + ilst)


def _rebuild_moov_with_meta(moov_bytes, meta_box):
    """Replace or insert udta/meta inside moov, preserving all other moov/udta children."""
    moov_hdr = 16 if struct.unpack_from('>I', moov_bytes, 0)[0] == 1 else 8
    children_out = []
    found_udta = False

    for pos, hdr, size, btype in _iter_boxes(moov_bytes, moov_hdr, len(moov_bytes)):
        raw_child = moov_bytes[pos:pos + size]
        if btype == b'udta':
            found_udta = True
            udta_children = []
            for u_pos, u_hdr, u_size, u_type in _iter_boxes(raw_child, hdr, len(raw_child)):
                if u_type != b'meta':
                    udta_children.append(raw_child[u_pos:u_pos + u_size])
            udta_children.append(meta_box)
            children_out.append(_mp4_box(b'udta', b''.join(udta_children)))
        else:
            children_out.append(raw_child)

    if not found_udta:
        children_out.append(_mp4_box(b'udta', meta_box))

    return _mp4_box(b'moov', b''.join(children_out))


_CONTAINER_BOXES = {b'moov', b'trak', b'mdia', b'minf', b'stbl'}


def _shift_chunk_offsets_inplace(buf, start, end, delta, mdat_offset):
    """Recursively shift stco/co64 entries >= mdat_offset by delta inside moov."""
    for pos, hdr, size, btype in _iter_boxes(buf, start, end):
        payload_start = pos + hdr
        box_end = pos + size
        if btype in _CONTAINER_BOXES:
            _shift_chunk_offsets_inplace(buf, payload_start, box_end, delta, mdat_offset)
        elif btype == b'stco':
            if payload_start + 8 <= box_end:
                count = struct.unpack_from('>I', buf, payload_start + 4)[0]
                table_start = payload_start + 8
                for i in range(count):
                    off_pos = table_start + i * 4
                    if off_pos + 4 > box_end:
                        break
                    val = struct.unpack_from('>I', buf, off_pos)[0]
                    if val >= mdat_offset:
                        struct.pack_into('>I', buf, off_pos, val + delta)
        elif btype == b'co64':
            if payload_start + 8 <= box_end:
                count = struct.unpack_from('>I', buf, payload_start + 4)[0]
                table_start = payload_start + 8
                for i in range(count):
                    off_pos = table_start + i * 8
                    if off_pos + 8 > box_end:
                        break
                    val = struct.unpack_from('>Q', buf, off_pos)[0]
                    if val >= mdat_offset:
                        struct.pack_into('>Q', buf, off_pos, val + delta)


def _tag_m4a(path, title, artist, img_bytes, mp4_img_type):
    meta_box = _build_meta_box(title, artist, img_bytes, mp4_img_type)
    if meta_box is None:
        return False

    with open(path, 'rb') as f:
        raw = f.read()

    top_boxes = list(_iter_boxes(raw, 0, len(raw)))
    moov_box = next((b for b in top_boxes if b[3] == b'moov'), None)
    mdat_box = next((b for b in top_boxes if b[3] == b'mdat'), None)
    if not moov_box:
        return False

    moov_pos, _, moov_size, _ = moov_box
    old_moov = raw[moov_pos:moov_pos + moov_size]
    new_moov = bytearray(_rebuild_moov_with_meta(old_moov, meta_box))
    delta = len(new_moov) - len(old_moov)

    if mdat_box is not None and moov_pos < mdat_box[0] and delta != 0:
        new_moov_hdr = 16 if struct.unpack_from('>I', new_moov, 0)[0] == 1 else 8
        _shift_chunk_offsets_inplace(new_moov, new_moov_hdr, len(new_moov), delta, mdat_box[0])

    _atomic_write_bytes(path, (raw[:moov_pos], bytes(new_moov), raw[moov_pos + moov_size:]))
    return True


def tag_audio_file(path, title, artist, thumb_url, ssl_context=None):
    """Tag an .mp3 or .m4a file in-place with title, artist, and cover art.

    Non-fatal: preserves the original file intact and returns False on any error.
    """
    if not path or not os.path.isfile(path):
        return False
    ext = os.path.splitext(path)[1].lower()
    if ext not in ('.mp3', '.m4a'):
        return False

    img_bytes, mime, mp4_type = _fetch_thumbnail(thumb_url, ssl_context=ssl_context)
    try:
        if ext == '.mp3':
            return _tag_mp3(path, title or '', artist or '', img_bytes, mime)
        return _tag_m4a(path, title or '', artist or '', img_bytes, mp4_type)
    except Exception:
        return False
