import os
import re
import json
import logging
import urllib.request
import urllib.parse
import urllib.error
import subprocess
from datetime import datetime
from pathlib import Path

logger = logging.getLogger("UrlResolver")

CACHE_FILE = Path(__file__).parent / "mission_cache.json"

def load_cache() -> dict:
    if CACHE_FILE.exists():
        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.warning(f"Failed loading mission cache: {e}")
    return {}

def save_cache(cache: dict):
    try:
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(cache, f, indent=2)
    except Exception as e:
        logger.error(f"Failed saving mission cache: {e}")

def search_github_api(query: str) -> dict | None:
    """Searches GitHub directly for the best matching repository."""
    # 1. Try gh CLI
    try:
        cmd = ["gh", "search", "repos", query, "--limit", "1", "--json", "fullName,url,description"]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
        if res.returncode == 0:
            data = json.loads(res.stdout)
            if data and len(data) > 0:
                first = data[0]
                repo = first.get("fullName")
                url = first.get("url")
                return {
                    "repo": repo,
                    "url": url,
                    "pr_url": f"{url}/pulls" if url else f"https://github.com/{repo}/pulls"
                }
    except Exception:
        pass

    # 2. Try GitHub REST API
    try:
        api_url = "https://api.github.com/search/repositories?q=" + urllib.parse.quote(query) + "&sort=stars&order=desc&per_page=1"
        req = urllib.request.Request(api_url, headers={"User-Agent": "DisciplineDispatcher/1.0", "Accept": "application/vnd.github.v3+json"})
        with urllib.request.urlopen(req, timeout=6) as resp:
            data = json.loads(resp.read().decode())
            items = data.get("items", [])
            if items:
                repo = items[0].get("full_name")
                url = items[0].get("html_url")
                return {
                    "repo": repo,
                    "url": url,
                    "pr_url": f"{url}/pulls" if url else f"https://github.com/{repo}/pulls"
                }
    except Exception:
        pass

    return None

def query_llm_for_repo(mission_title: str, llm_config: dict) -> dict | None:
    """
    Calls LLM ONCE to analyze the mission title, search its knowledge / internet references,
    and return the exact GitHub owner/repo and PR page URL.
    """
    if not llm_config or not llm_config.get("enabled", True):
        return None

    api_key = llm_config.get("api_key", "").strip()
    if not api_key:
        return None

    endpoint = llm_config.get("endpoint", "https://ai.hackclub.com/proxy/v1/chat/completions")
    primary_model = llm_config.get("model", "google/gemini-4-hydrogen")
    fallback_models = llm_config.get("fallback_models", ["google/gemini-3.5-flash-lite", "openai/gpt-4o-mini"])
    models = [primary_model] + [m for m in fallback_models if m != primary_model]

    prompt = f"""The user wrote an informal personal discipline mission: "{mission_title}".
Identify which GitHub repository (owner/repo) and PR URL this mission is referring to.
Search your knowledge and public open source repositories.
Return ONLY valid JSON in this exact structure:
{{
  "identified": true or false,
  "repo": "owner/repo" or null,
  "url": "https://github.com/owner/repo" or null,
  "pr_url": "https://github.com/owner/repo/pulls" or null,
  "search_query": "best keywords to search on GitHub"
}}
"""

    for model in models:
        try:
            payload = json.dumps({
                "model": model,
                "messages": [
                    {"role": "system", "content": "You are a software developer assistant that identifies GitHub repositories from informal mission titles. Return strictly JSON."},
                    {"role": "user", "content": prompt}
                ],
                "temperature": 0.1
            }).encode("utf-8")

            req = urllib.request.Request(
                endpoint,
                data=payload,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                    "User-Agent": "DisciplineDispatcher/1.0"
                },
                method="POST"
            )

            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode())
                content = data["choices"][0]["message"]["content"].strip()
                # Clean markdown fences if any
                if content.startswith("```"):
                    content = re.sub(r"^```(?:json)?\s*", "", content)
                    content = re.sub(r"\s*```$", "", content)
                parsed = json.loads(content.strip())
                if parsed and parsed.get("identified") and parsed.get("repo"):
                    logger.info(f"LLM ({model}) successfully identified target repo for '{mission_title}': {parsed.get('repo')}")
                    return parsed
        except urllib.error.HTTPError as e:
            logger.warning(f"LLM model '{model}' error (HTTP {e.code}) while resolving repo for '{mission_title}'. Trying fallback...")
            continue
        except Exception as e:
            logger.warning(f"LLM model '{model}' failed: {e}. Trying fallback...")
            continue

    return None

def resolve_and_cache_mission_target(mission_title: str, llm_config: dict) -> dict | None:
    """
    Checks cache first. If already resolved, returns cached entry (0 API cost).
    If not cached, calls LLM ONCE, validates via GitHub search, stores in cache, and returns it.
    """
    clean_key = mission_title.strip()
    cache = load_cache()

    # 1. Cache Hit - Reuse stored URL!
    if clean_key in cache:
        logger.debug(f"Cache hit for mission '{clean_key}': {cache[clean_key].get('repo')}")
        return cache[clean_key]

    # Only attempt to resolve if mission appears to be code/git/pr related
    title_lower = clean_key.lower()
    git_keywords = ["pr", "pull", "github", "git", "repo", "issue", "commit", "review"]
    is_git_related = any(kw in title_lower for kw in git_keywords) or "[" in title_lower

    if not is_git_related:
        return None

    logger.info(f"Resolving target GitHub URL for new mission: '{clean_key}' (calling LLM once)...")

    # 2. Call LLM Once to identify repo & PR URL
    llm_result = query_llm_for_repo(clean_key, llm_config)
    repo = None
    url = None
    pr_url = None

    if llm_result and llm_result.get("repo"):
        repo = llm_result["repo"]
        url = llm_result.get("url") or f"https://github.com/{repo}"
        pr_url = llm_result.get("pr_url") or f"{url}/pulls"
    elif llm_result and llm_result.get("search_query"):
        # Fallback to search query
        search_res = search_github_api(llm_result["search_query"])
        if search_res:
            repo = search_res["repo"]
            url = search_res["url"]
            pr_url = search_res["pr_url"]
    else:
        # Fallback to direct search of mission title
        clean_search = re.sub(r'\[.*?\]', '', clean_key)
        for kw in ["submit", "pr", "to", "for", "open", "pull", "request", "review", "daily"]:
            clean_search = re.sub(rf'\b{kw}\b', '', clean_search, flags=re.IGNORECASE)
        clean_search = clean_search.strip()
        if clean_search:
            search_res = search_github_api(clean_search)
            if search_res:
                repo = search_res["repo"]
                url = search_res["url"]
                pr_url = search_res["pr_url"]

    if repo and url:
        stored_entry = {
            "identified": True,
            "repo": repo,
            "url": url,
            "pr_url": pr_url or f"{url}/pulls",
            "resolved_at": datetime.now().isoformat()
        }
        cache[clean_key] = stored_entry
        save_cache(cache)
        logger.info(f"PERMANENTLY STORED TARGET URL for '{clean_key}' -> {url} (PRs: {pr_url})")
        return stored_entry

    return None
