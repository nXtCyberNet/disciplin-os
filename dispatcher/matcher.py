import re
import json
import logging
import urllib.request
import urllib.error

logger = logging.getLogger("Matcher")

def clean_title(title: str) -> str:
    """Strips emojis, punctuation and normalizes whitespace."""
    cleaned = re.sub(r'[^\w\s-]', ' ', title)
    return ' '.join(cleaned.lower().split())

def match_mission_heuristic(repo: str, pr_title: str, pr_number: int, open_missions: list[dict]) -> dict | None:
    """
    Intelligently matches a GitHub PR event to one of the uncompleted missions on the phone.
    Works offline with zero external dependencies.
    """
    if not open_missions:
        return None

    repo_lower = repo.lower()
    repo_parts = [p for p in re.split(r'[/_-]', repo_lower) if len(p) >= 3]

    pr_keywords = {"pr", "pull", "request", "pullrequest", "github", "git", "gh", "commit", "push", "code"}

    best_match = None
    highest_score = 0

    for mission in open_missions:
        title = mission.get("title", "")
        if not title:
            continue

        title_cleaned = clean_title(title)
        words = set(title_cleaned.split())

        score = 0

        # Direct tag check: e.g. "[pr]" or "[github]" or "[repo:k8gb]"
        if re.search(r'\[.*(pr|github|pull).*\]', title, re.IGNORECASE):
            score += 50
        if any(f"[{p}]" in title.lower() or f"[repo:{p}]" in title.lower() for p in repo_parts):
            score += 60

        # Keyword match: contains 'pr' or 'pull request' or 'github'
        has_pr_kw = bool(words & pr_keywords) or "pull request" in title_cleaned
        if has_pr_kw:
            score += 30

        # Repository name match: does mission title mention the repo name?
        repo_matches = [p for p in repo_parts if p in words or p in title_cleaned]
        if repo_matches:
            score += 40

        # PR title overlap: does mission mention words from the PR title?
        pr_words = set(clean_title(pr_title).split()) - {"add", "fix", "update", "the", "a", "an", "to", "for", "in", "ci"}
        overlap = words & pr_words
        if overlap:
            score += len(overlap) * 15

        if score > highest_score and score >= 30:
            highest_score = score
            best_match = mission

    return best_match

def extract_json_block(text: str) -> dict | None:
    """Extracts JSON object from plain text or markdown fences."""
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        return json.loads(text.strip())
    except Exception:
        # Try finding outermost { ... }
        match = re.search(r'\{.*\}', text, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(0))
            except Exception:
                pass
    return None

def match_mission_llm(
    repo: str,
    pr_title: str,
    pr_number: int,
    open_missions: list[dict],
    llm_config: dict
) -> dict | None:
    """
    Uses OpenAI-compatible proxy (Hack Club AI) to understand informal mission names
    and match them to the GitHub PR event.
    Tries primary model (e.g. google/gemini-4-hydrogen), with automatic fallback if spending limit hit.
    """
    if not llm_config or not llm_config.get("enabled", True):
        return None

    api_key = llm_config.get("api_key", "").strip()
    if not api_key or not open_missions:
        return None

    endpoint = llm_config.get("endpoint", "https://ai.hackclub.com/proxy/v1/chat/completions")
    primary_model = llm_config.get("model", "google/gemini-4-hydrogen")
    fallback_models = llm_config.get("fallback_models", ["google/gemini-3.5-flash-lite", "openai/gpt-4o-mini"])

    models_to_try = [primary_model] + [m for m in fallback_models if m != primary_model]

    mission_list_str = "\n".join([f"- {m.get('title')}" for m in open_missions])
    prompt = f"""You are an automated task matcher for a personal discipline app.
A user just opened or updated a GitHub Pull Request:
- Repository: {repo}
- PR #{pr_number}: "{pr_title}"

The user currently has these UNCOMPLETED daily required missions on their phone:
{mission_list_str}

Determine if any of the above missions corresponds to creating, working on, or submitting this GitHub Pull Request.
The user might have written something informal like "submit PR today", "work on {repo.split('/')[-1]}", "code review", or "open pull request".

Respond ONLY with valid JSON in this exact structure:
{{
  "matched": true or false,
  "mission_title": "exact title of matching mission or null",
  "reason": "brief rationale"
}}
"""

    for model in models_to_try:
        try:
            payload = json.dumps({
                "model": model,
                "messages": [
                    {"role": "system", "content": "You are an automated task matcher. Respond strictly with JSON."},
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

            with urllib.request.urlopen(req, timeout=10) as response:
                res_body = response.read().decode("utf-8")
                data = json.loads(res_body)
                content = data["choices"][0]["message"]["content"]
                parsed = extract_json_block(content)

                if parsed and parsed.get("matched") and parsed.get("mission_title"):
                    target_title = parsed["mission_title"].strip()
                    logger.info(f"LLM ({model}) successfully matched mission '{target_title}': {parsed.get('reason')}")
                    for m in open_missions:
                        if m.get("title", "").strip().lower() == target_title.lower() or target_title.lower() in m.get("title", "").lower():
                            return m

            # If response received but didn't match
            logger.info(f"LLM ({model}) evaluated missions: no match detected.")
            return None

        except urllib.error.HTTPError as e:
            err_msg = ""
            try:
                err_msg = e.read().decode()
            except Exception:
                pass
            logger.warning(f"LLM model '{model}' failed (HTTP {e.code}): {err_msg[:120]}. Trying fallback model...")
            continue
        except Exception as e:
            logger.warning(f"LLM model '{model}' error: {e}. Trying fallback model...")
            continue

    return None

def resolve_mission_match(
    repo: str,
    pr_title: str,
    pr_number: int,
    open_missions: list[dict],
    llm_config: dict = None
) -> dict | None:
    """
    Tries LLM match first if configured, otherwise falls back to smart heuristics.
    """
    if llm_config:
        llm_result = match_mission_llm(repo, pr_title, pr_number, open_missions, llm_config)
        if llm_result:
            return llm_result

    return match_mission_heuristic(repo, pr_title, pr_number, open_missions)

def match_youtube_video_heuristic(video_title: str, channel_name: str, open_missions: list[dict]) -> dict | None:
    """
    Intelligently matches a watched YouTube video to an uncompleted mission on the phone.
    """
    if not open_missions:
        return None

    video_words = set(clean_title(video_title).split()) - {"a", "an", "the", "to", "for", "in", "on", "of", "and", "video", "official"}
    channel_words = set(clean_title(channel_name).split()) - {"channel", "official"}

    best_match = None
    highest_score = 0

    for mission in open_missions:
        title = mission.get("title", "")
        if not title:
            continue
        title_cleaned = clean_title(title)
        words = set(title_cleaned.split())

        score = 0
        # Check tags: [youtube], [video], [lecture], [study], [yt]
        if re.search(r'\[.*(youtube|yt|video|lecture|course).*\]', title, re.IGNORECASE):
            score += 50

        # General video keywords in mission
        yt_keywords = {"watch", "video", "lecture", "tutorial", "course", "youtube", "yt", "study"}
        if words & yt_keywords:
            score += 30

        # Overlap with video title
        overlap = words & video_words
        if overlap:
            score += len(overlap) * 20

        # Channel mention
        if (channel_words and channel_words & words) or (channel_name.lower() in title.lower() and len(channel_name) > 3):
            score += 35

        if score > highest_score and score >= 40:
            highest_score = score
            best_match = mission

    return best_match

def resolve_youtube_mission_match(
    video_title: str,
    channel_name: str,
    open_missions: list[dict],
    llm_config: dict = None
) -> dict | None:
    """
    Matches watched YouTube video to open missions using heuristics and LLM fallback.
    """
    heuristic_match = match_youtube_video_heuristic(video_title, channel_name, open_missions)
    if heuristic_match:
        return heuristic_match

    # If heuristics didn't find high confidence match and LLM is enabled
    if llm_config and llm_config.get("enabled", True) and llm_config.get("api_key"):
        endpoint = llm_config.get("endpoint", "https://ai.hackclub.com/proxy/v1/chat/completions")
        primary_model = llm_config.get("model", "google/gemini-4-hydrogen")
        fallback_models = llm_config.get("fallback_models", ["google/gemini-3.5-flash-lite", "openai/gpt-4o-mini"])
        models = [primary_model] + [m for m in fallback_models if m != primary_model]

        mission_list_str = "\n".join([f"- {m.get('title')}" for m in open_missions])
        prompt = f"""A user just watched this YouTube video:
Title: "{video_title}"
Channel: "{channel_name}"

Here are their active unfulfilled discipline missions:
{mission_list_str}

Did watching this video fulfill or count towards any of the missions?
Return ONLY valid JSON:
{{
  "matched": true or false,
  "mission_title": "exact title of matched mission or null",
  "reason": "brief explanation"
}}
"""
        for model in models:
            try:
                payload = json.dumps({
                    "model": model,
                    "messages": [
                        {"role": "system", "content": "You match watched YouTube videos to user discipline missions. Return strictly JSON."},
                        {"role": "user", "content": prompt}
                    ],
                    "temperature": 0.1
                }).encode("utf-8")

                req = urllib.request.Request(
                    endpoint,
                    data=payload,
                    headers={
                        "Authorization": f"Bearer {llm_config.get('api_key')}",
                        "Content-Type": "application/json",
                        "User-Agent": "DisciplineDispatcher/1.0"
                    },
                    method="POST"
                )

                with urllib.request.urlopen(req, timeout=8) as response:
                    data = json.loads(response.read().decode("utf-8"))
                    content = data["choices"][0]["message"]["content"]
                    parsed = extract_json_block(content)
                    if parsed and parsed.get("matched") and parsed.get("mission_title"):
                        target_title = parsed["mission_title"].strip()
                        for m in open_missions:
                            if m.get("title", "").strip().lower() == target_title.lower() or target_title.lower() in m.get("title", "").lower():
                                logger.info(f"LLM ({model}) matched YouTube video '{video_title}' -> Mission '{m.get('title')}'")
                                return m
                break
            except Exception:
                continue

    return None
