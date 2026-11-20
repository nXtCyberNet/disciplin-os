import sys
import os
import time
import json
import logging
import urllib.request
import urllib.error
import urllib.parse
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from matcher import resolve_mission_match, resolve_youtube_mission_match
from github_checker import get_pull_requests, is_recent
from url_resolver import resolve_and_cache_mission_target, load_cache
from youtube_checker import get_new_watched_videos, get_recent_history, fetch_youtube_activity, get_cookie_jar

logger = logging.getLogger("DisciplineDispatcher")

class MissionDispatcher:
    def __init__(self, config_path: str = None):
        if config_path is None:
            config_path = str(Path(__file__).parent / "config.json")
        self.config_path = config_path
        self.config = self.load_config()
        self.logs = []
        self.fulfilled_keys_today = set()
        self.last_sync_date = datetime.now().strftime("%Y-%m-%d")

    def load_config(self) -> dict:
        config = {}
        try:
            with open(self.config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
        except Exception as e:
            config = {
                "phone_url": "http://127.0.0.1:8080",
                "github_user": "nXtCyberNet",
                "poll_interval_seconds": 60,
                "rules": []
            }

        # Merge local overrides if config.local.json exists (kept gitignored)
        local_cfg_path = Path(self.config_path).parent / "config.local.json"
        if local_cfg_path.exists():
            try:
                with open(local_cfg_path, "r", encoding="utf-8") as lf:
                    local_cfg = json.load(lf)
                    if "llm" in local_cfg and isinstance(local_cfg["llm"], dict) and "llm" in config:
                        config["llm"].update(local_cfg["llm"])
                    config.update({k: v for k, v in local_cfg.items() if k != "llm"})
            except Exception as le:
                logger.warning(f"Could not load config.local.json: {le}")

        env_key = os.environ.get("LLM_API_KEY")
        if env_key:
            if "llm" not in config:
                config["llm"] = {}
            config["llm"]["api_key"] = env_key

        return config

    def save_config(self):
        try:
            cfg_to_save = dict(self.config)
            local_cfg_path = Path(self.config_path).parent / "config.local.json"
            if local_cfg_path.exists() and "llm" in cfg_to_save:
                # Keep API key in config.local.json, not in tracked config.json
                llm_copy = dict(cfg_to_save["llm"])
                llm_copy["api_key"] = ""
                cfg_to_save["llm"] = llm_copy
            with open(self.config_path, "w", encoding="utf-8") as f:
                json.dump(cfg_to_save, f, indent=2)
        except Exception as e:
            logger.error(f"Failed to save config: {e}")

    def add_log(self, category: str, message: str, success: bool = True):
        now_str = datetime.now().strftime("%H:%M:%S")
        entry = {
            "time": now_str,
            "category": category,
            "message": message,
            "success": success
        }
        self.logs.insert(0, entry)
        if len(self.logs) > 100:
            self.logs.pop()
        logger.info(f"[{category}] {message}")

    def check_daily_reset(self):
        today = datetime.now().strftime("%Y-%m-%d")
        if today != self.last_sync_date:
            self.fulfilled_keys_today.clear()
            self.last_sync_date = today
            self.add_log("RESET", f"New day rollover ({today}); cleared fulfilled cache.")

    def get_candidate_phone_urls(self) -> list[str]:
        urls = []
        configured = self.config.get("phone_url", "").strip().rstrip("/")
        if configured:
            urls.append(configured)
        for fallback in ["http://100.76.223.105:8080", "http://127.0.0.1:8080"]:
            if fallback not in urls:
                urls.append(fallback)
        return urls

    def fetch_phone_missions(self) -> list[dict]:
        for phone_url in self.get_candidate_phone_urls():
            url = f"{phone_url}/webhook/missions"
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "DisciplineDispatcher/1.0"})
                with urllib.request.urlopen(req, timeout=4) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    missions = data.get("missions", [])
                    cache = load_cache()
                    for m in missions:
                        t = m.get("title", "").strip()
                        if t in cache:
                            m["target"] = cache[t]
                    return missions
            except Exception:
                continue
        return []

    def fetch_phone_status(self) -> dict:
        last_err = "No endpoint responded"
        for phone_url in self.get_candidate_phone_urls():
            url = f"{phone_url}/webhook/status"
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "DisciplineDispatcher/1.0"})
                with urllib.request.urlopen(req, timeout=4) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    data["connected"] = True
                    data["phone_url"] = phone_url
                    return data
            except Exception as e:
                last_err = str(e)
                continue
        return {"connected": False, "error": last_err, "phone_url": self.config.get("phone_url")}

    def trigger_phone_mission(self, target: str, source: str = "Discipline PC Dispatcher") -> tuple[bool, str]:
        encoded_target = urllib.parse.quote(target.strip())
        last_err = ""
        for phone_url in self.get_candidate_phone_urls():
            url = f"{phone_url}/webhook/mission/{encoded_target}"
            try:
                req = urllib.request.Request(
                    url,
                    data=b"",
                    headers={"User-Agent": f"DisciplineDispatcher ({source})"},
                    method="POST"
                )
                with urllib.request.urlopen(req, timeout=5) as resp:
                    res_body = resp.read().decode("utf-8")
                    res_json = json.loads(res_body)
                    if res_json.get("status") == "success" or res_json.get("verified") is True:
                        matched = res_json.get("mission", target)
                        self.add_log("DISPATCH", f"Successfully verified mission '{matched}' via {phone_url}", True)
                        return True, matched
                    else:
                        msg = res_json.get("message", "No matching mission found on phone")
                        self.add_log("DISPATCH", f"Failed to verify '{target}': {msg}", False)
                        return False, msg
            except Exception as e:
                last_err = str(e)
                continue
        self.add_log("ERROR", f"Error dispatching webhook for '{target}': {last_err}", False)
        return False, last_err

    def add_phone_mission(self, title: str) -> tuple[bool, str]:
        payload = json.dumps({"title": title.strip()}).encode("utf-8")
        last_err = ""
        for phone_url in self.get_candidate_phone_urls():
            url = f"{phone_url}/webhook/mission/add"
            try:
                req = urllib.request.Request(
                    url,
                    data=payload,
                    headers={"Content-Type": "application/json", "User-Agent": "DisciplineDispatcher/1.0"},
                    method="POST"
                )
                with urllib.request.urlopen(req, timeout=5) as resp:
                    res_body = resp.read().decode("utf-8")
                    res_json = json.loads(res_body)
                    if res_json.get("status") == "success":
                        added_title = res_json.get("title", title)
                        self.add_log("MISSION_ADD", f"Created new required mission on phone: '{added_title}' via {phone_url}", True)
                        return True, added_title
                    else:
                        msg = res_json.get("message", "Failed to add mission on phone")
                        self.add_log("MISSION_ADD", f"Failed to add '{title}': {msg}", False)
                        return False, msg
            except Exception as e:
                last_err = str(e)
                continue
        self.add_log("ERROR", f"Error adding mission to phone: {last_err}", False)
        return False, last_err

    def evaluate_rules(self) -> list[dict]:
        self.check_daily_reset()
        missions = self.fetch_phone_missions()
        open_missions = [m for m in missions if not m.get("completed", False)]

        results = []
        llm_config = self.config.get("llm", {})

        # Pre-resolve and cache target repo/URL for any open missions.
        # Checks mission_cache.json first; only calls LLM once per mission ever!
        mission_targets = {}
        for m in open_missions:
            m_title = m.get("title", "")
            target_info = resolve_and_cache_mission_target(m_title, llm_config)
            if target_info:
                mission_targets[m_title] = target_info

        rules = self.config.get("rules", [])
        for rule in rules:
            if not rule.get("enabled", True):
                continue

            rule_type = rule.get("type")
            rule_id = rule.get("id", rule.get("name", "rule"))

            if rule_type == "github_pr":
                author = rule.get("author") or self.config.get("github_user", "nXtCyberNet")
                repo = rule.get("repo", "")
                state = rule.get("state", "all")
                must_be_today = rule.get("must_be_today", True)

                prs = get_pull_requests(author=author, repo=repo, state=state, limit=10)

                for pr in prs:
                    pr_num = pr.get("number")
                    pr_repo = pr.get("repo", repo)
                    pr_title = pr.get("title", "")
                    created_at = pr.get("created_at", "")
                    unique_key = f"github_pr_{pr_repo}_{pr_num}_{self.last_sync_date}"

                    if unique_key in self.fulfilled_keys_today:
                        continue

                    if must_be_today and not is_recent(created_at, max_age_hours=24):
                        continue

                    matched_mission = None

                    # If an explicit target mission is configured in the rule
                    target_config = rule.get("target_mission", "auto")

                    if target_config and target_config != "auto":
                        # Find by specific title or substring
                        for m in open_missions:
                            if target_config.lower() in m.get("title", "").lower():
                                matched_mission = m
                                break
                    else:
                        # 1. Match against cached mission targets (instant, 0 LLM calls)
                        for m in open_missions:
                            t_info = mission_targets.get(m.get("title", ""))
                            if t_info and t_info.get("repo"):
                                target_repo = t_info["repo"].lower()
                                pr_repo_lower = pr_repo.lower()
                                if pr_repo_lower == target_repo or pr_repo_lower.endswith(target_repo.split("/")[-1]):
                                    matched_mission = m
                                    logger.info(f"Instant match via cached URL ({t_info['repo']}) -> '{m.get('title')}'")
                                    break

                        # 2. Fallback to heuristic / smart matcher if not already resolved
                        if not matched_mission:
                            matched_mission = resolve_mission_match(
                                repo=pr_repo,
                                pr_title=pr_title,
                                pr_number=pr_num,
                                open_missions=open_missions,
                                llm_config=llm_config
                            )

                    if matched_mission:
                        mission_title = matched_mission.get("title", "")
                        self.add_log(
                            "MATCH",
                            f"Matched PR #{pr_num} ('{pr_title}' on {pr_repo}) -> Mission '{mission_title}'"
                        )
                        success, detail = self.trigger_phone_mission(
                            mission_title,
                            source=f"GitHub PR #{pr_num} ({pr_repo})"
                        )
                        if success:
                            self.fulfilled_keys_today.add(unique_key)
                            results.append({
                                "rule": rule.get("name"),
                                "event": f"GitHub PR #{pr_num} ({pr_repo})",
                                "mission": mission_title,
                                "status": "verified"
                            })
                            # Refresh open missions
                            open_missions = [m for m in open_missions if m.get("title") != mission_title]
                            break

            elif rule_type == "youtube_watch":
                cj, source = get_cookie_jar()
                if not cj:
                    continue

                new_videos, status_info = get_new_watched_videos(limit=15)
                if status_info.get("status") == "expired":
                    self.add_log("YOUTUBE", "Google session expired. Please re-export cookies.txt.", False)
                    continue

                for v in new_videos:
                    v_title = v.get("title", "")
                    v_author = v.get("author", "")
                    v_id = v.get("video_id", "")
                    unique_key = f"yt_{v_id}_{self.last_sync_date}"

                    if unique_key in self.fulfilled_keys_today:
                        continue

                    # Match watched video against open missions
                    matched_mission = resolve_youtube_mission_match(
                        video_title=v_title,
                        channel_name=v_author,
                        open_missions=open_missions,
                        llm_config=llm_config
                    )

                    if matched_mission:
                        mission_title = matched_mission.get("title", "")
                        self.add_log(
                            "YOUTUBE_MATCH",
                            f"Matched video '{v_title}' ({v_author}) -> Mission '{mission_title}'"
                        )
                        success, detail = self.trigger_phone_mission(
                            mission_title,
                            source=f"YouTube Watch: {v_title}"
                        )
                        if success:
                            self.fulfilled_keys_today.add(unique_key)
                            results.append({
                                "rule": rule.get("name"),
                                "event": f"YouTube: '{v_title}' ({v_author})",
                                "mission": mission_title,
                                "status": "verified"
                            })
                            open_missions = [m for m in open_missions if m.get("title") != mission_title]
                            break

        return results

    def poll_cycle(self):
        try:
            self.evaluate_rules()
        except Exception as e:
            self.add_log("ERROR", f"Error in poll cycle: {e}", False)
