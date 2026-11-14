/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Optional enhancement: the repository links work without a network or scripts.
(async () => {
  const badges = [...document.querySelectorAll('a.github-stars')];
  if (!badges.length) return;
  const repository = new URL(badges[0].href).pathname.replace(/^\/|\/$/g, '');
  const cacheKey = `github-stars:${repository}`;
  const validCount = count => Number.isSafeInteger(count) && count >= 0;
  const format = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });
  const renderCount = count => badges.forEach(badge => {
    badge.querySelector('.github-stars-count').textContent = format.format(count).toLowerCase();
    const label = `Star on GitHub (${count.toLocaleString('en')} ${count === 1 ? 'star' : 'stars'})`;
    badge.setAttribute('aria-label', label);
    badge.title = label;
  });
  const now = Date.now();
  try {
    const cached = JSON.parse(sessionStorage.getItem(cacheKey));
    const age = now - cached?.timestamp;
    if (age >= 0 && age < 15 * 60 * 1000) {
      if (validCount(cached.count)) renderCount(cached.count);
      return;
    }
    // Cache attempts too, so navigation does not repeat failed or rate-limited requests.
    sessionStorage.setItem(cacheKey, JSON.stringify({ timestamp: now }));
  } catch {
    // Storage may be unavailable for local files or restricted browser settings.
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(`https://api.github.com/repos/${repository}`, {
      headers: { Accept: 'application/vnd.github+json' },
      credentials: 'omit',
      signal: controller.signal
    });
    if (!response.ok) return;
    const { stargazers_count: count } = await response.json();
    if (!validCount(count)) return;
    renderCount(count);
    try {
      sessionStorage.setItem(cacheKey, JSON.stringify({ timestamp: Date.now(), count }));
    } catch {
      // A count can still be displayed when storage is unavailable.
    }
  } catch {
    // Keep the static Star label when offline, timed out, or blocked by GitHub.
  } finally {
    clearTimeout(timeout);
  }
})();
