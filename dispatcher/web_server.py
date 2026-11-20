import re
import json
import logging
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from threading import Thread
import time
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from url_resolver import load_cache
from youtube_checker import (
    get_cookie_jar,
    save_cookies_txt,
    get_recent_history,
    enrich_video_metadata,
    fetch_youtube_activity
)
from matcher import resolve_youtube_mission_match

logger = logging.getLogger("DisciplineWeb")

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Discipline Mission Dispatcher</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #0d1117;
      --card-bg: #161b22;
      --card-border: #30363d;
      --text: #c9d1d9;
      --text-bold: #f0f6fc;
      --text-muted: #8b949e;
      --accent: #58a6ff;
      --success: #3fb950;
      --warning: #d29922;
      --danger: #f85149;
      --purple: #bc8cff;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: 'Plus Jakarta Sans', -apple-system, sans-serif;
      background: var(--bg);
      color: var(--text);
      line-height: 1.5;
      padding: 24px;
    }
    .container { max-width: 1040px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
    
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 16px;
      border-bottom: 1px solid var(--card-border);
    }
    .brand h1 { font-size: 22px; font-weight: 800; color: var(--text-bold); display: flex; align-items: center; gap: 10px; }
    .brand p { font-size: 13px; color: var(--text-muted); margin-top: 2px; }
    
    .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 6px 14px;
      border-radius: 9999px;
      font-size: 12px;
      font-weight: 600;
      border: 1px solid transparent;
    }
    .status-connected { background: rgba(63, 185, 80, 0.15); color: var(--success); border-color: rgba(63, 185, 80, 0.3); }
    .status-disconnected { background: rgba(248, 81, 73, 0.15); color: var(--danger); border-color: rgba(248, 81, 73, 0.3); }
    .status-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }

    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
    @media (max-width: 800px) { .grid-2 { grid-template-columns: 1fr; } }

    .card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .card-title {
      font-size: 15px;
      font-weight: 700;
      color: var(--text-bold);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .btn {
      font-family: inherit;
      font-size: 12px;
      font-weight: 600;
      padding: 6px 14px;
      border-radius: 6px;
      cursor: pointer;
      border: 1px solid var(--card-border);
      background: #21262d;
      color: var(--text-bold);
      transition: all 0.15s ease;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .btn:hover { background: #30363d; border-color: #8b949e; }
    .btn-primary { background: #238636; border-color: rgba(240, 246, 252, 0.1); color: #fff; }
    .btn-primary:hover { background: #2ea043; }
    .btn-accent { background: #1f6feb; border-color: transparent; color: #fff; }
    .btn-accent:hover { background: #388bfd; }

    .mission-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 14px;
      background: #0d1117;
      border: 1px solid var(--card-border);
      border-radius: 8px;
    }
    .mission-info { display: flex; align-items: center; gap: 10px; font-size: 13px; font-weight: 600; }
    .mission-status {
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 4px;
      text-transform: uppercase;
    }
    .status-done { background: rgba(63, 185, 80, 0.2); color: var(--success); }
    .status-pending { background: rgba(210, 153, 34, 0.2); color: var(--warning); }

    .rule-box {
      background: #0d1117;
      border: 1px solid var(--card-border);
      border-radius: 8px;
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .rule-header { display: flex; justify-content: space-between; align-items: center; }
    .rule-name { font-weight: 700; color: var(--text-bold); font-size: 13px; }
    .rule-details { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--text-muted); }

    .log-box {
      font-family: 'JetBrains Mono', monospace;
      font-size: 11px;
      background: #090d13;
      border: 1px solid var(--card-border);
      border-radius: 8px;
      padding: 12px;
      height: 200px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .log-item { display: flex; gap: 8px; }
    .log-time { color: var(--text-muted); }
    .log-cat { color: var(--accent); font-weight: 600; }
    .log-msg { color: var(--text); }
    .log-success { color: var(--success); }
    .log-error { color: var(--danger); }

    input, select {
      font-family: inherit;
      background: #0d1117;
      border: 1px solid var(--card-border);
      color: var(--text-bold);
      padding: 8px 12px;
      border-radius: 6px;
      font-size: 13px;
      outline: none;
      width: 100%;
    }
    input:focus, select:focus { border-color: var(--accent); }
    .form-group { display: flex; flex-direction: column; gap: 6px; }
    label { font-size: 12px; font-weight: 600; color: var(--text-muted); }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="brand">
        <h1>⚡ Discipline Mission Dispatcher</h1>
        <p>PC / Server automated webhook bridge for phone missions</p>
      </div>
        <a href="/download/DisciplineOS-Phone-Bundle.zip" class="btn btn-primary" style="text-decoration: none; padding: 6px 14px; font-size: 12px; background: #8B5CF6; color: #fff; border-color: #8B5CF6;">📦 Download Phone Bundle ZIP</a>
        <a href="/download/DisciplineOS-Suite-KernelSU.zip" class="btn btn-primary" style="text-decoration: none; padding: 6px 14px; font-size: 12px; background: #F59E0B; color: #fff; border-color: #F59E0B;">⚡ KernelSU Suite ZIP</a>
        <a href="/download/DisciplineLock.apk" class="btn btn-primary" style="text-decoration: none; padding: 6px 14px; font-size: 12px; background: var(--accent); color: #fff; border-color: var(--accent);">📥 Download Lock APK</a>
        <a href="/download/HealthHub.apk" class="btn btn-primary" style="text-decoration: none; padding: 6px 14px; font-size: 12px; background: #10B981; color: #fff; border-color: #10B981;">🏃 Download Health Hub APK</a>
        <div id="phone-status" class="status-badge status-disconnected">
          <span class="status-dot"></span> Checking Phone...
        </div>
      </div>
    </header>

    <div class="grid-2">
      <!-- Phone Missions Card -->
      <div class="card">
        <div class="card-title">
          <span>📱 Phone Daily Missions</span>
          <button class="btn" onclick="refreshMissions()">↻ Refresh</button>
        </div>
        <p style="font-size: 12px; color: var(--text-muted);">
          Live sync from phone's <code>/webhook/missions</code> endpoint.
        </p>
        <div id="missions-list" style="display: flex; flex-direction: column; gap: 8px;">
          <div style="font-size: 12px; color: var(--text-muted);">Loading missions...</div>
        </div>

        <div style="margin-top: 10px; padding-top: 12px; border-top: 1px solid var(--card-border); display: flex; flex-direction: column; gap: 8px;">
          <label style="font-size: 12px; font-weight: 700; color: var(--text-bold);">➕ Add Required Mission to Phone</label>
          <div style="display: flex; gap: 8px;">
            <input type="text" id="new-mission-title" placeholder="Enter mission title...">
            <button class="btn btn-primary" onclick="addMission()">Add Mission</button>
          </div>
          <p style="font-size: 11px; color: var(--warning); line-height: 1.4;">
            🔒 <b>Anti-Cheat Integrity</b>: Missions can be added anytime (syncs to phone). Deleting missions is strictly blocked to maintain discipline until daily reset.
          </p>
        </div>
      </div>

      <!-- GitHub PR Monitor Card -->
      <div class="card">
        <div class="card-title">
          <span>🐙 GitHub PR Automation</span>
          <button class="btn btn-accent" onclick="runCheckNow()">⚡ Check Now</button>
        </div>
        <p style="font-size: 12px; color: var(--text-muted);">
          Watches your GitHub activity. Automatically resolves & unlocks the corresponding mission on phone.
        </p>

        <div id="rules-container" style="display: flex; flex-direction: column; gap: 10px;">
          <!-- Loaded dynamically -->
        </div>

        <div style="margin-top: 10px; padding-top: 14px; border-top: 1px solid var(--card-border);">
          <div class="form-group">
            <label>Targeted GitHub Repo (Leave blank for all repos):</label>
            <div style="display: flex; gap: 8px;">
              <input type="text" id="target-repo-input" placeholder="e.g. k8gb-io/k8gb">
              <button class="btn btn-primary" onclick="updateRepo()">Save</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Manual Webhook Tester & LLM Setup Card -->
    <div class="grid-2">
      <div class="card">
        <div class="card-title">
          <span>🚀 Manual Webhook Dispatcher</span>
        </div>
        <p style="font-size: 12px; color: var(--text-muted);">
          Send a direct fulfillment webhook to test phone unlock or manually clear a task.
        </p>
        <div style="display: flex; gap: 8px;">
          <input type="text" id="manual-target" placeholder="Mission Name or ID (e.g. Read 20 Pages)">
          <button class="btn btn-primary" onclick="sendManualWebhook()">Dispatch</button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">
          <span>🤖 Smart Mission Matcher (LLM)</span>
          <span id="llm-status-pill" style="font-size: 11px; font-weight: 700; color: var(--success);">● Active</span>
        </div>
        <p style="font-size: 12px; color: var(--text-muted);">
          Matches informal mission titles (e.g. "PR for login", "submit repo code"). Uses OpenAI-compatible proxy with primary model <code>google/gemini-4-hydrogen</code>.
        </p>
        <div style="display: flex; flex-direction: column; gap: 8px;">
          <div style="font-family: 'JetBrains Mono', monospace; font-size: 11px; background: #090d13; border: 1px solid var(--card-border); border-radius: 6px; padding: 10px; display: flex; flex-direction: column; gap: 4px;">
            <div>Provider: <b style="color: var(--accent);">Hack Club AI Proxy</b></div>
            <div>Model: <b id="llm-model-label" style="color: var(--text-bold);">google/gemini-4-hydrogen</b></div>
            <div>Auth: <b style="color: var(--success);">Bearer sk-hc-v1-*** (Configured)</b></div>
          </div>
          <div style="display: flex; gap: 8px;">
            <input type="text" id="model-input" placeholder="Model (default: google/gemini-4-hydrogen)">
            <button class="btn" onclick="saveModel()">Update Model</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Cached Mission Target URLs (1-Time LLM Lookup, Stored on Disk) -->
    <div class="card">
      <div class="card-title">
        <span>📌 Resolved Mission Repositories (1-Time LLM Lookup & Permanent Cache)</span>
        <span style="font-size: 11px; font-weight: 600; color: var(--accent);">5-min cycle • 0 future LLM calls</span>
      </div>
      <p style="font-size: 12px; color: var(--text-muted); margin: 0;">
        When a new informal mission is created, the LLM analyzes it once to resolve the exact GitHub repo and PR URL. It is then saved to disk (<code>mission_cache.json</code>) and reused on all subsequent 5-minute ticks with zero API cost.
      </p>
      <div id="cache-container" style="display: flex; flex-direction: column; gap: 8px;"></div>
    </div>

    <!-- YouTube Cross-Device Watch Tracking Card -->
    <div class="card">
      <div class="card-title">
        <span>📺 YouTube Cross-Device Watch Tracking (My Activity)</span>
        <span id="yt-status-pill" style="font-size: 11px; font-weight: 700;">Checking...</span>
      </div>
      <p style="font-size: 12px; color: var(--text-muted); margin: 0;">
        Polls cross-device YouTube watch history (Desktop, Phone, TV) via Google My Activity. Fulfills learning & lecture missions automatically using quota-free oEmbed metadata and smart matching.
      </p>

      <div class="grid-2">
        <div style="background: #090d13; border: 1px solid var(--card-border); border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 8px;">
          <div style="font-weight: 600; font-size: 12px; color: var(--text-bold);">🔐 Google Session Cookies</div>
          <div style="font-size: 11px; color: var(--text-muted);">
            Export <code>cookies.txt</code> from Chrome/Firefox via <i>Get cookies.txt LOCALLY</i> extension or DevTools.
          </div>
          <textarea id="yt-cookies-input" rows="2" placeholder="Paste Netscape cookies.txt content here..." style="width: 100%; font-family: 'JetBrains Mono', monospace; font-size: 10px; background: #161b22; border: 1px solid var(--card-border); color: var(--text); border-radius: 6px; padding: 8px;"></textarea>
          <button class="btn btn-primary" onclick="saveYoutubeCookies()">Save Cookies</button>
        </div>

        <div style="background: #090d13; border: 1px solid var(--card-border); border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 8px;">
          <div style="font-weight: 600; font-size: 12px; color: var(--text-bold);">⚡ Test Watched Video Trigger</div>
          <div style="font-size: 11px; color: var(--text-muted);">
            Simulate watching a video by entering a YouTube URL, Video ID, or Title:
          </div>
          <input type="text" id="yt-test-input" placeholder="e.g. dQw4w9WgXcQ or 'MIT Distributed Systems Lecture'">
          <button class="btn" onclick="testYoutubeVideo()">Test Video Match & Unlock</button>
        </div>
      </div>

      <div style="margin-top: 6px;">
        <div style="font-size: 12px; font-weight: 600; color: var(--text-muted); margin-bottom: 6px;">Recently Logged YouTube Watch History:</div>
        <div id="yt-videos-container" style="display: flex; flex-direction: column; gap: 8px;"></div>
      </div>
    </div>

    <!-- Event Logs -->
    <div class="card">
      <div class="card-title">
        <span>📜 Live Dispatcher Logs</span>
        <button class="btn" onclick="refreshLogs()">Clear / Refresh</button>
      </div>
      <div id="log-box" class="log-box"></div>
    </div>
  </div>

  <script>
    async function loadData() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        
        // Status Badge
        const statusEl = document.getElementById('phone-status');
        if (data.phone_connected) {
          statusEl.className = 'status-badge status-connected';
          statusEl.innerHTML = '<span class="status-dot"></span> Phone Online (' + data.phone_url + ')';
        } else {
          statusEl.className = 'status-badge status-disconnected';
          statusEl.innerHTML = '<span class="status-dot"></span> Phone Offline (' + data.phone_url + ')';
        }

        // Rules
        renderRules(data.rules || []);
        
        // Config inputs
        if (data.rules && data.rules[0]) {
          document.getElementById('target-repo-input').value = data.rules[0].repo || '';
        }
        if (data.llm) {
          const lbl = document.getElementById('llm-model-label');
          if (lbl) lbl.innerText = data.llm.model || 'google/gemini-4-hydrogen';
          const inp = document.getElementById('model-input');
          if (inp && !inp.value) inp.value = data.llm.model || '';
          const pill = document.getElementById('llm-status-pill');
          if (pill) {
            pill.style.color = data.llm.is_configured ? 'var(--success)' : 'var(--warning)';
            pill.innerText = data.llm.is_configured ? '● Active' : '○ Missing Key';
          }
        }

        // Logs
        renderLogs(data.logs || []);
        
        // Missions
        renderMissions(data.missions || [], data.phone_connected);

        // Cached Targets
        renderCache(data.mission_cache || {});

        // YouTube Status & History
        if (data.youtube) {
          const pill = document.getElementById('yt-status-pill');
          if (pill) {
            if (data.youtube.has_cookies) {
              pill.style.color = 'var(--success)';
              pill.innerText = '● Cookies Active (' + (data.youtube.cookie_source || 'cookies.txt') + ')';
            } else {
              pill.style.color = 'var(--warning)';
              pill.innerText = '○ Needs cookies.txt';
            }
          }
          renderYoutubeHistory(data.youtube.recent_history || []);
        }
      } catch (e) {
        console.error('Error fetching status:', e);
      }
    }

    function renderMissions(missions, isConnected) {
      const container = document.getElementById('missions-list');
      if (!missions || missions.length === 0) {
        if (isConnected) {
          container.innerHTML = '<div style="font-size: 12px; color: var(--text-muted); padding: 8px 0;">⚡ Phone connected! No required missions currently active. Add a mission below.</div>';
        } else {
          container.innerHTML = '<div style="font-size: 12px; color: var(--warning); padding: 8px 0;">⚠️ Phone unreachable. Check Tailscale connection or phone IP.</div>';
        }
        return;
      }
      container.innerHTML = missions.map(m => `
        <div class="mission-item">
          <div class="mission-info">
            <span>${m.completed ? '✅' : '⏳'}</span>
            <span>${m.title}</span>
          </div>
          <div style="display: flex; align-items: center; gap: 8px;">
            <span class="mission-status ${m.completed ? 'status-done' : 'status-pending'}">
              ${m.completed ? 'Cleared' : 'Pending'}
            </span>
            ${!m.completed ? `<button class="btn" onclick="sendQuickWebhook('${m.title.replace(/'/g, "\\'")}')">⚡ Unlock</button>` : ''}
          </div>
        </div>
      `).join('');
    }

    function renderRules(rules) {
      const container = document.getElementById('rules-container');
      if (!rules || rules.length === 0) {
        container.innerHTML = '<div style="font-size: 12px; color: var(--text-muted);">No active rules configured.</div>';
        return;
      }
      container.innerHTML = rules.map(r => `
        <div class="rule-box">
          <div class="rule-header">
            <span class="rule-name">${r.name}</span>
            <span style="font-size: 11px; font-weight: 600; color: ${r.enabled ? 'var(--success)' : 'var(--text-muted)'};">
              ${r.enabled ? '● Active' : '○ Paused'}
            </span>
          </div>
          <div class="rule-details">
            Author: <b>${r.author || 'nXtCyberNet'}</b> | Repo: <b>${r.repo || 'All Repos'}</b> | State: <b>${r.state || 'open'}</b>
          </div>
          <div class="rule-details" style="color: var(--accent);">
            Mapping: <b>${r.target_mission === 'auto' ? 'Smart Auto-Match (Heuristic + LLM)' : r.target_mission}</b>
          </div>
        </div>
      `).join('');
    }

    function renderCache(cache) {
      const container = document.getElementById('cache-container');
      if (!cache || Object.keys(cache).length === 0) {
        container.innerHTML = '<div style="font-size: 12px; color: var(--text-muted);">No missions cached yet. When a GitHub mission is fetched, the LLM will resolve its URL once and save it here.</div>';
        return;
      }
      container.innerHTML = Object.entries(cache).map(([title, info]) => `
        <div style="background: #090d13; border: 1px solid var(--card-border); border-radius: 8px; padding: 12px; display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap;">
          <div style="display: flex; flex-direction: column; gap: 3px;">
            <div style="font-weight: 700; font-size: 13px; color: var(--text-bold);">${title}</div>
            <div style="font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--accent);">
              📦 Repo: <b>${info.repo || 'Unknown'}</b>
            </div>
            <div style="font-size: 11px; color: var(--text-muted);">
              🔗 PR Link: <a href="${info.pr_url || info.url || '#'}" target="_blank" style="color: var(--accent); text-decoration: underline;">${info.pr_url || info.url || 'N/A'}</a>
            </div>
          </div>
          <div style="text-align: right;">
            <span style="display: inline-block; padding: 3px 8px; border-radius: 4px; font-size: 10px; font-weight: 700; background: rgba(63, 185, 80, 0.15); color: var(--success); border: 1px solid rgba(63, 185, 80, 0.3);">
              PERMANENTLY CACHED
            </span>
            <div style="font-size: 10px; color: var(--text-muted); margin-top: 4px;">Resolved: ${info.resolved_at ? new Date(info.resolved_at).toLocaleTimeString() : 'Earlier'}</div>
          </div>
        </div>
      `).join('');
    }

    function renderLogs(logs) {
      const container = document.getElementById('log-box');
      if (!logs || logs.length === 0) {
        container.innerHTML = '<div style="color: var(--text-muted);">No logs yet. Waiting for events...</div>';
        return;
      }
      container.innerHTML = logs.map(l => `
        <div class="log-item">
          <span class="log-time">[${l.time}]</span>
          <span class="log-cat">[${l.category}]</span>
          <span class="log-msg ${l.success ? 'log-success' : 'log-error'}">${l.message}</span>
        </div>
      `).join('');
    }

    async function runCheckNow() {
      try {
        const res = await fetch('/api/check-now', { method: 'POST' });
        const data = await res.json();
        alert(data.message || 'Check completed');
        loadData();
      } catch (e) {
        alert('Error triggering check: ' + e);
      }
    }

    async function sendQuickWebhook(title) {
      if (!confirm("Fulfill mission '" + title + "' via webhook now?")) return;
      try {
        const res = await fetch('/api/trigger', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ target: title })
        });
        const data = await res.json();
        loadData();
      } catch (e) {
        alert('Error: ' + e);
      }
    }

    async function sendManualWebhook() {
      const target = document.getElementById('manual-target').value.trim();
      if (!target) return alert('Enter a mission title or ID');
      sendQuickWebhook(target);
      document.getElementById('manual-target').value = '';
    }

    async function addMission() {
      const input = document.getElementById('new-mission-title');
      const title = input.value.trim();
      if (!title) return alert('Please enter a mission title');
      try {
        const res = await fetch('/api/add-mission', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title: title })
        });
        const data = await res.json();
        if (data.status === 'ok') {
          input.value = '';
          alert("Mission added to phone: '" + title + "'");
          loadData();
        } else {
          alert('Failed to add mission: ' + data.message);
        }
      } catch (e) {
        alert('Network error: ' + e);
      }
    }

    async function updateRepo() {
      const repo = document.getElementById('target-repo-input').value.trim();
      try {
        await fetch('/api/update-config', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ repo: repo })
        });
        alert('Updated target repo to: ' + (repo || 'All Repositories'));
        loadData();
      } catch (e) {
        alert('Error saving repo: ' + e);
      }
    }

    async function saveModel() {
      const model = document.getElementById('model-input').value.trim();
      if (!model) return alert('Enter model name');
      try {
        await fetch('/api/update-config', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ llm_model: model })
        });
        alert('Updated LLM model to: ' + model);
        loadData();
      } catch (e) {
        alert('Error saving model: ' + e);
      }
    function renderYoutubeHistory(videos) {
      const container = document.getElementById('yt-videos-container');
      if (!container) return;
      if (!videos || videos.length === 0) {
        container.innerHTML = '<div style="font-size: 11px; color: var(--text-muted); padding: 8px; background: #090d13; border-radius: 6px;">No YouTube watch history recorded yet. Export cookies.txt to begin capturing cross-device watch activity.</div>';
        return;
      }
      container.innerHTML = videos.map(v => `
        <div style="background: #090d13; border: 1px solid var(--card-border); border-radius: 8px; padding: 10px; display: flex; align-items: center; gap: 12px;">
          <img src="${v.thumbnail || 'https://i.ytimg.com/vi/' + v.video_id + '/hqdefault.jpg'}" alt="thumb" style="width: 80px; height: 45px; object-fit: cover; border-radius: 4px; border: 1px solid var(--card-border); flex-shrink: 0;">
          <div style="flex: 1; min-width: 0;">
            <div style="font-weight: 600; font-size: 12px; color: var(--text-bold); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">
              <a href="${v.url || '#'}" target="_blank" style="color: inherit; text-decoration: none;">${v.title}</a>
            </div>
            <div style="font-size: 11px; color: var(--accent); margin-top: 2px;">
              📺 ${v.author || 'YouTube'}
            </div>
            <div style="font-size: 10px; color: var(--text-muted); margin-top: 2px;">
              Logged: ${v.logged_at ? new Date(v.logged_at).toLocaleTimeString() : 'Recent'}
            </div>
          </div>
          <button class="btn" style="flex-shrink: 0; font-size: 11px;" onclick="testYoutubeVideo('${v.title.replace(/'/g, "\\'")}')">⚡ Test Match</button>
        </div>
      `).join('');
    }

    async function saveYoutubeCookies() {
      const val = document.getElementById('yt-cookies-input').value.trim();
      if (!val) return alert('Paste your Netscape cookies.txt content first');
      try {
        const res = await fetch('/api/youtube/cookies', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ cookies: val })
        });
        const data = await res.json();
        if (data.status === 'ok') {
          alert('Cookies saved! ' + (data.details || ''));
          document.getElementById('yt-cookies-input').value = '';
          loadData();
        } else {
          alert('Error: ' + data.message);
        }
      } catch (e) {
        alert('Failed: ' + e);
      }
    }

    async function testYoutubeVideo(titleOverride) {
      const inputVal = titleOverride || document.getElementById('yt-test-input').value.trim();
      if (!inputVal) return alert('Enter a video ID, URL, or Title');
      try {
        const res = await fetch('/api/youtube/test-video', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ video_input: inputVal })
        });
        const data = await res.json();
        if (data.matched) {
          alert(`🎯 MATCH SUCCESSFUL!\n\nVideo: ${data.video_title}\nMission: ${data.mission}\nUnlock Webhook: ${data.unlocked ? 'Dispatched Successfully' : 'Failed'}`);
        } else {
          alert(`No active mission matched:\nVideo: ${data.video_title}\nChannel: ${data.channel}\n\nAdd an open mission matching this topic (e.g. "Watch tutorial on ${data.video_title.substring(0, 20)}")`);
        }
        loadData();
      } catch (e) {
        alert('Test failed: ' + e);
      }
    }

    function refreshMissions() { loadData(); }
    function refreshLogs() { loadData(); }

    // Auto-refresh every 5 seconds
    loadData();
    setInterval(loadData, 5000);
  </script>
</body>
</html>
"""

class DispatcherHttpHandler(BaseHTTPRequestHandler):
    dispatcher = None  # set on start

    def do_HEAD(self):
        url_path = urllib.parse.urlparse(self.path).path
        if url_path == "/" or url_path == "/index.html":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            content = DASHBOARD_HTML.encode("utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            return

        if url_path == "/download/app.apk" or url_path == "/download/DisciplineLock.apk":
            apk_path = Path(__file__).resolve().parent.parent / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            if apk_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", 'attachment; filename="DisciplineLock-Test.apk"')
                self.send_header("Content-Length", str(apk_path.stat().st_size))
                self.end_headers()
                return
            else:
                self.send_error(404, "APK not found")
                return

        if url_path in ("/download/HealthHub.apk", "/download/gpslogger.apk"):
            apk_path = Path(__file__).resolve().parent.parent / "gpslogger" / "gpslogger" / "build" / "outputs" / "apk" / "debug" / "gpslogger-debug.apk"
            if apk_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", 'attachment; filename="GPSLogger-HealthHub.apk"')
                self.send_header("Content-Length", str(apk_path.stat().st_size))
                self.end_headers()
                return
            else:
                self.send_error(404, "Health Hub APK not found")
        if url_path.startswith("/download/") and url_path.endswith(".zip"):
            filename = url_path.split("/")[-1]
            zip_path = Path(__file__).resolve().parent.parent / "build" / "outputs" / filename
            if not zip_path.exists():
                zip_path = Path(__file__).resolve().parent.parent / "apk_distribution" / filename
            if zip_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
                self.send_header("Content-Length", str(zip_path.stat().st_size))
                self.end_headers()
                return
            else:
                self.send_error(404, "Zip file not found")
                return

        self.send_error(404, "Not Found")

    def do_GET(self):
        url_path = urllib.parse.urlparse(self.path).path

        if url_path == "/" or url_path == "/index.html":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            content = DASHBOARD_HTML.encode("utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return

        if url_path == "/download/app.apk" or url_path == "/download/DisciplineLock.apk":
            apk_path = Path(__file__).resolve().parent.parent / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            if apk_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", 'attachment; filename="DisciplineLock-Test.apk"')
                content = apk_path.read_bytes()
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
                return
            else:
                self.send_error(404, "APK not found")
                return

        if url_path in ("/download/HealthHub.apk", "/download/gpslogger.apk"):
            apk_path = Path(__file__).resolve().parent.parent / "gpslogger" / "gpslogger" / "build" / "outputs" / "apk" / "debug" / "gpslogger-debug.apk"
            if apk_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", 'attachment; filename="GPSLogger-HealthHub.apk"')
                content = apk_path.read_bytes()
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
                return
            else:
                self.send_error(404, "Health Hub APK not found")
                return

        if url_path.startswith("/download/") and url_path.endswith(".zip"):
            filename = url_path.split("/")[-1]
            zip_path = Path(__file__).resolve().parent.parent / "build" / "outputs" / filename
            if not zip_path.exists():
                zip_path = Path(__file__).resolve().parent.parent / "apk_distribution" / filename
            if zip_path.exists():
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
                self.send_header("Content-Length", str(zip_path.stat().st_size))
                self.end_headers()
                with open(zip_path, "rb") as zf:
                    while chunk := zf.read(65536):
                        self.wfile.write(chunk)
                return
            else:
                self.send_error(404, "Zip file not found")
                return

        if url_path == "/api/status":
            missions = self.dispatcher.fetch_phone_missions()
            phone_status = self.dispatcher.fetch_phone_status()
            llm_cfg = self.dispatcher.config.get("llm", {})
            cj, cj_source = get_cookie_jar()
            res_data = {
                "phone_connected": phone_status.get("connected", False),
                "phone_url": phone_status.get("phone_url") or self.dispatcher.config.get("phone_url"),
                "poll_interval_seconds": self.dispatcher.config.get("poll_interval_seconds", 300),
                "rules": self.dispatcher.config.get("rules", []),
                "missions": missions,
                "mission_cache": load_cache(),
                "youtube": {
                    "has_cookies": bool(cj),
                    "cookie_source": cj_source,
                    "recent_history": get_recent_history(10)
                },
                "logs": self.dispatcher.logs,
                "llm": {
                    "enabled": llm_cfg.get("enabled", True),
                    "model": llm_cfg.get("model", "google/gemini-4-hydrogen"),
                    "endpoint": llm_cfg.get("endpoint", "https://ai.hackclub.com/proxy/v1/chat/completions"),
                    "is_configured": bool(llm_cfg.get("api_key"))
                }
            }
            self.send_json(res_data)
            return

        if url_path == "/api/missions":
            missions = self.dispatcher.fetch_phone_missions()
            self.send_json({"missions": missions, "status": "ok"})
            return

        self.send_error(404, "Not Found")

    def do_POST(self):
        url_path = urllib.parse.urlparse(self.path).path
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else ""
        data = {}
        if body:
            try:
                data = json.loads(body)
            except Exception:
                pass

        if url_path == "/api/check-now":
            results = self.dispatcher.evaluate_rules()
            if results:
                summary = f"Evaluated rules: {len(results)} mission(s) matched and verified!"
            else:
                summary = "Check completed. No new matching PRs or unfulfilled missions found."
            self.send_json({"status": "ok", "message": summary, "results": results})
            return

        if url_path == "/api/trigger":
            target = data.get("target", "").strip()
            if not target:
                self.send_json({"status": "error", "message": "Target is required"}, 400)
                return
            success, msg = self.dispatcher.trigger_phone_mission(target, source="Web Dashboard Manual Trigger")
            self.send_json({"status": "ok" if success else "error", "message": msg, "verified": success})
            return

        if url_path == "/api/add-mission":
            title = data.get("title", "").strip()
            if not title:
                self.send_json({"status": "error", "message": "Mission title cannot be empty"}, 400)
                return
            success, msg = self.dispatcher.add_phone_mission(title)
            self.send_json({"status": "ok" if success else "error", "message": msg, "title": title})
            return

        if url_path == "/api/youtube/cookies":
            raw_cookies = data.get("cookies", "").strip()
            if not raw_cookies:
                self.send_json({"status": "error", "message": "Cookie content cannot be empty"}, 400)
                return
            saved = save_cookies_txt(raw_cookies)
            if saved:
                self.dispatcher.add_log("YOUTUBE", "Saved cookies.txt. Verifying Google session...")
                act = fetch_youtube_activity(limit=5)
                self.send_json({
                    "status": "ok",
                    "message": "Cookies saved successfully!",
                    "youtube_status": act.get("status"),
                    "details": act.get("message")
                })
            else:
                self.send_json({"status": "error", "message": "Failed to save cookies on disk"}, 500)
            return

        if url_path == "/api/youtube/test-video":
            raw_input = data.get("video_input", "").strip()
            if not raw_input:
                self.send_json({"status": "error", "message": "Video ID, URL, or Title is required"}, 400)
                return

            # Check if input is a video ID or URL
            vid_match = re.search(r'(?:watch\?v=|/shorts/|youtu\.be/|^)([a-zA-Z0-9_-]{11})(?:$|[&?])', raw_input)
            if vid_match:
                vid = vid_match.group(1)
                meta = enrich_video_metadata(vid)
                v_title = meta.get("title", raw_input)
                v_author = meta.get("author", "Unknown Channel")
            else:
                v_title = raw_input
                v_author = "YouTube"

            missions = self.dispatcher.fetch_phone_missions()
            open_missions = [m for m in missions if not m.get("completed", False)]
            matched = resolve_youtube_mission_match(
                video_title=v_title,
                channel_name=v_author,
                open_missions=open_missions,
                llm_config=self.dispatcher.config.get("llm")
            )
            if matched:
                m_title = matched.get("title")
                ok, msg = self.dispatcher.trigger_phone_mission(m_title, source=f"Test YouTube: {v_title}")
                self.dispatcher.add_log("YOUTUBE_TEST", f"Test video '{v_title}' matched mission '{m_title}' -> Webhook triggered")
                self.send_json({
                    "status": "ok",
                    "matched": True,
                    "mission": m_title,
                    "video_title": v_title,
                    "channel": v_author,
                    "unlocked": ok,
                    "detail": msg
                })
            else:
                self.send_json({
                    "status": "ok",
                    "matched": False,
                    "message": f"No open mission matched video '{v_title}'",
                    "video_title": v_title,
                    "channel": v_author
                })
            return

        if url_path == "/api/update-config":
            if "repo" in data:
                if self.dispatcher.config.get("rules"):
                    self.dispatcher.config["rules"][0]["repo"] = data["repo"].strip()
            if "llm_model" in data:
                if "llm" not in self.dispatcher.config:
                    self.dispatcher.config["llm"] = {}
                self.dispatcher.config["llm"]["model"] = data["llm_model"].strip()
            self.dispatcher.save_config()
            self.dispatcher.add_log("CONFIG", "Configuration updated via Web Dashboard")
            self.send_json({"status": "ok", "config": self.dispatcher.config})
            return

        self.send_error(404, "Not Found")

    def send_json(self, data: dict, code: int = 200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # Suppress default noisy console logs
        return

def run_server(dispatcher, port: int = 5050):
    DispatcherHttpHandler.dispatcher = dispatcher
    server = HTTPServer(("0.0.0.0", port), DispatcherHttpHandler)
    logger.info(f"Discipline Mission Dispatcher Dashboard running at http://localhost:{port}")
    server.serve_forever()
