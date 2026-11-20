import subprocess
import json
import urllib.request
import urllib.error
from datetime import datetime, timezone

def fetch_prs_via_gh(author: str = "nXtCyberNet", repo: str = "", state: str = "all", limit: int = 10) -> list[dict]:
    """Queries GitHub pull requests using the local gh CLI tool."""
    try:
        cmd = ["gh", "search", "prs", "--author", author, "--limit", str(limit), "--json", "number,title,url,createdAt,updatedAt,state,repository"]
        if repo.strip():
            cmd.extend(["--repo", repo.strip()])
        if state in ("open", "closed", "merged"):
            cmd.extend(["--state", state])

        result = subprocess.run(cmd, capture_output=True, text=True, timeout=12)
        if result.returncode != 0:
            return []

        data = json.loads(result.stdout)
        prs = []
        for item in data:
            repo_info = item.get("repository", {})
            repo_name = repo_info.get("nameWithOwner", repo_info.get("name", repo))
            prs.append({
                "number": item.get("number"),
                "title": item.get("title", ""),
                "repo": repo_name,
                "url": item.get("url", ""),
                "state": item.get("state", "").lower(),
                "created_at": item.get("createdAt", ""),
                "updated_at": item.get("updatedAt", "")
            })
        return prs
    except Exception as e:
        return []

def fetch_prs_via_api(author: str = "nXtCyberNet", repo: str = "", state: str = "all", limit: int = 10) -> list[dict]:
    """Fallback query via public GitHub REST API."""
    try:
        q_parts = [f"author:{author}", "type:pr"]
        if repo.strip():
            q_parts.append(f"repo:{repo.strip()}")
        if state in ("open", "closed"):
            q_parts.append(f"state:{state}")

        query = "+".join(q_parts)
        url = f"https://api.github.com/search/issues?q={query}&sort=updated&order=desc&per_page={limit}"

        req = urllib.request.Request(
            url,
            headers={"User-Agent": "Discipline-Mission-Dispatcher/1.0", "Accept": "application/vnd.github.v3+json"}
        )

        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            items = data.get("items", [])
            prs = []
            for item in items:
                # Extract repo name from repository_url
                repo_url = item.get("repository_url", "")
                repo_name = "/".join(repo_url.split("/")[-2:]) if repo_url else repo

                prs.append({
                    "number": item.get("number"),
                    "title": item.get("title", ""),
                    "repo": repo_name,
                    "url": item.get("html_url", ""),
                    "state": item.get("state", "").lower(),
                    "created_at": item.get("created_at", ""),
                    "updated_at": item.get("updated_at", "")
                })
            return prs
    except Exception as e:
        return []

def get_pull_requests(author: str = "nXtCyberNet", repo: str = "", state: str = "all", limit: int = 10) -> list[dict]:
    """Attempts gh CLI first, then falls back to GitHub API."""
    prs = fetch_prs_via_gh(author, repo, state, limit)
    if not prs:
        prs = fetch_prs_via_api(author, repo, state, limit)
    return prs

def is_recent(iso_timestamp: str, max_age_hours: int = 24) -> bool:
    """Checks whether an ISO timestamp is within max_age_hours."""
    if not iso_timestamp:
        return False
    try:
        # Normalize trailing Z
        cleaned = iso_timestamp.replace("Z", "+00:00")
        dt = datetime.fromisoformat(cleaned)
        now = datetime.now(timezone.utc)
        diff_hours = (now - dt).total_seconds() / 3600.0
        return 0 <= diff_hours <= max_age_hours
    except Exception:
        return True
