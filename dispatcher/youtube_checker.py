import os
import re
import json
import logging
import urllib.request
import urllib.parse
from http.cookiejar import MozillaCookieJar
from pathlib import Path
from datetime import datetime
import requests

logger = logging.getLogger("YouTubeChecker")

BASE_DIR = Path(__file__).resolve().parent
COOKIES_FILE = BASE_DIR / "cookies.txt"
HISTORY_FILE = BASE_DIR / "youtube_history.json"
METADATA_CACHE_FILE = BASE_DIR / "youtube_metadata_cache.json"

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

def load_json_file(file_path: Path, default=None):
    if file_path.exists():
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.warning(f"Error loading {file_path.name}: {e}")
    return default if default is not None else {}

def save_json_file(file_path: Path, data):
    try:
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        logger.error(f"Error saving {file_path.name}: {e}")

def get_cookie_jar() -> tuple[MozillaCookieJar | None, str | None]:
    """Loads cookies from cookies.txt or returns None."""
    if COOKIES_FILE.exists() and COOKIES_FILE.stat().st_size > 10:
        try:
            cj = MozillaCookieJar(str(COOKIES_FILE))
            cj.load(ignore_discard=True, ignore_expires=True)
            return cj, "cookies.txt"
        except Exception as e:
            logger.warning(f"Failed to parse cookies.txt: {e}")

    # Fallback: check config.json for raw cookie string
    config_file = BASE_DIR / "config.json"
    if config_file.exists():
        cfg = load_json_file(config_file, {})
        raw_cookie = cfg.get("youtube_cookie_string", "").strip()
        if raw_cookie:
            cj = MozillaCookieJar()
            # Parse key=val pairs
            for pair in raw_cookie.split(";"):
                pair = pair.strip()
                if "=" in pair:
                    k, v = pair.split("=", 1)
                    cj.set_cookie(requests.cookies.create_cookie(
                        name=k.strip(),
                        value=v.strip(),
                        domain=".google.com"
                    ))
            return cj, "config_string"

    return None, None

def save_cookies_txt(content: str) -> bool:
    """Saves user-provided cookies.txt content."""
    try:
        with open(COOKIES_FILE, "w", encoding="utf-8") as f:
            f.write(content.strip() + "\n")
        logger.info(f"Saved cookies to {COOKIES_FILE}")
        return True
    except Exception as e:
        logger.error(f"Failed to save cookies: {e}")
        return False

def enrich_video_metadata(video_id: str) -> dict:
    """
    Fetches video metadata (title, author/channel, thumbnail) using oEmbed.
    Zero API key required, quota-free. Caches permanently on disk.
    """
    metadata_cache = load_json_file(METADATA_CACHE_FILE, {})
    if video_id in metadata_cache:
        return metadata_cache[video_id]

    video_url = f"https://www.youtube.com/watch?v={video_id}"
    oembed_url = f"https://www.youtube.com/oembed?url={urllib.parse.quote(video_url)}&format=json"

    meta = {
        "video_id": video_id,
        "title": f"YouTube Video ({video_id})",
        "author": "Unknown Channel",
        "thumbnail": f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
        "url": video_url,
        "fetched_at": datetime.now().isoformat()
    }

    try:
        req = urllib.request.Request(
            oembed_url,
            headers={"User-Agent": USER_AGENT}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            meta["title"] = data.get("title", meta["title"])
            meta["author"] = data.get("author_name", meta["author"])
            meta["thumbnail"] = data.get("thumbnail_url", meta["thumbnail"])
    except Exception as e:
        logger.debug(f"oEmbed fetch for {video_id} failed: {e}")

    metadata_cache[video_id] = meta
    save_json_file(METADATA_CACHE_FILE, metadata_cache)
    return meta

def fetch_youtube_activity(limit: int = 20) -> dict:
    """
    Scrapes https://myactivity.google.com/product/youtube?hl=en
    Returns status dict with:
      - status: 'ok', 'needs_cookies', 'expired', or 'error'
      - message: string explanation
      - videos: list of video dicts
    """
    cj, source = get_cookie_jar()
    if not cj:
        return {
            "status": "needs_cookies",
            "message": "cookies.txt not found. Export your Google session cookies to enable watch tracking.",
            "videos": []
        }

    url = "https://myactivity.google.com/product/youtube?hl=en"
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "DNT": "1"
    }

    try:
        session = requests.Session()
        session.cookies = cj
        resp = session.get(url, headers=headers, timeout=12, allow_redirects=True)

        # Check if redirected to Google sign-in
        if "accounts.google.com" in resp.url or "signin" in resp.url.lower():
            logger.warning("Google session cookies expired (redirected to accounts.google.com)")
            return {
                "status": "expired",
                "message": "Google session expired. Please re-export cookies.txt.",
                "videos": []
            }

        if resp.status_code != 200:
            return {
                "status": "error",
                "message": f"HTTP {resp.status_code} from My Activity",
                "videos": []
            }

        html = resp.text

        # Extract YouTube watch and shorts IDs in order of appearance
        # watch?v=... or shorts/...
        found_ids = []
        pattern = re.compile(r'(?:watch\?v=|/shorts/)([a-zA-Z0-9_-]{11})')
        for match in pattern.finditer(html):
            vid = match.group(1)
            if vid not in found_ids:
                found_ids.append(vid)

        if not found_ids:
            # Check if page returned 200 but is actually a login challenge
            if "Sign in" in html or "ServiceLogin" in html:
                return {
                    "status": "expired",
                    "message": "Cookies invalid or expired (Sign in prompt detected).",
                    "videos": []
                }
            return {
                "status": "ok",
                "message": "No recent YouTube watch activity found on page.",
                "videos": []
            }

        # Truncate to limit and enrich with title & channel
        video_items = []
        for vid in found_ids[:limit]:
            meta = enrich_video_metadata(vid)
            video_items.append(meta)

        return {
            "status": "ok",
            "message": f"Successfully parsed {len(video_items)} watched videos from My Activity",
            "videos": video_items
        }

    except Exception as e:
        logger.error(f"Error fetching YouTube activity: {e}")
        return {
            "status": "error",
            "message": str(e),
            "videos": []
        }

def get_new_watched_videos(limit: int = 15) -> tuple[list[dict], dict]:
    """
    Polls YouTube activity, compares against history, returns:
      - newly_watched_videos: list of videos seen for the FIRST time in this poll
      - status_info: full status from fetch_youtube_activity
    """
    status_info = fetch_youtube_activity(limit=limit)
    if status_info.get("status") != "ok":
        return [], status_info

    current_videos = status_info.get("videos", [])
    history = load_json_file(HISTORY_FILE, {"seen_ids": [], "items": []})
    seen_ids = set(history.get("seen_ids", []))

    new_items = []
    now_iso = datetime.now().isoformat()

    for v in current_videos:
        vid = v.get("video_id")
        if vid and vid not in seen_ids:
            item = dict(v)
            item["logged_at"] = now_iso
            new_items.append(item)
            seen_ids.add(vid)
            history.setdefault("items", []).insert(0, item)

    # Keep history bounded (last 500 items)
    history["seen_ids"] = list(seen_ids)[-500:]
    history["items"] = history.get("items", [])[:300]
    history["last_poll"] = now_iso
    save_json_file(HISTORY_FILE, history)

    if new_items:
        logger.info(f"Discovered {len(new_items)} new watched YouTube videos!")

    return new_items, status_info

def get_recent_history(limit: int = 20) -> list[dict]:
    """Returns the most recent videos logged from history."""
    history = load_json_file(HISTORY_FILE, {"items": []})
    return history.get("items", [])[:limit]
