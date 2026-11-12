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

(() => {
  document.body.classList.add('js');
  const content = document.querySelector('#content');
  content.setAttribute('role', 'main');
  content.tabIndex = -1;
  const page = location.pathname.split('/').pop() || 'index.html';
  const nav = document.querySelector('.site-nav');
  const menu = document.querySelector('.menu-toggle');
  const links = [...nav.querySelectorAll('.nav-group a')];
  const current = links.find(link => link.getAttribute('href') === page);
  current?.setAttribute('aria-current', 'page');

  const context = document.createElement('div');
  context.className = 'page-context';
  const home = document.createElement('a');
  home.href = 'index.html';
  home.textContent = 'Docs';
  const separator = document.createElement('span');
  separator.textContent = '/';
  separator.setAttribute('aria-hidden', 'true');
  const group = document.createElement('span');
  group.textContent = current?.closest('.nav-group').querySelector('.nav-label').textContent || 'User guide';
  context.append(home, separator, group);
  document.querySelector('#header').prepend(context);

  menu.hidden = false;
  const mobile = window.matchMedia('(max-width: 760px)');
  const pageRegions = [...document.querySelectorAll('#header, #content, .site-footer')];
  const setMenu = open => {
    nav.classList.toggle('is-open', open);
    menu.setAttribute('aria-expanded', String(open));
    menu.textContent = open ? 'Close' : 'Menu';
    pageRegions.forEach(region => { region.inert = open; });
  };
  menu.addEventListener('click', () => setMenu(!nav.classList.contains('is-open')));
  mobile.addEventListener('change', () => setMenu(false));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && nav.classList.contains('is-open')) {
      setMenu(false);
      menu.focus();
    }
  });
  nav.addEventListener('click', event => {
    if (event.target.closest('a')) setMenu(false);
  });
  // Keep the current page in view within long desktop navigation lists.
  if (current && !mobile.matches) nav.scrollTop = Math.max(0, current.offsetTop - nav.clientHeight / 2);

  const toc = document.querySelector('#toc');
  if (toc) {
    const disclosure = document.createElement('details');
    disclosure.id = 'toc';
    const summary = document.createElement('summary');
    summary.id = 'toctitle';
    summary.textContent = 'On this page';
    disclosure.append(summary, toc.querySelector('ul'));
    toc.replaceWith(disclosure);
    const wide = window.matchMedia('(min-width: 1280px)');
    const updateToc = () => {
      disclosure.open = wide.matches;
      summary.tabIndex = wide.matches ? -1 : 0;
    };
    updateToc();
    wide.addEventListener('change', updateToc);
    const tocLinks = [...disclosure.querySelectorAll('a')];
    const sections = tocLinks.map(link => document.getElementById(decodeURIComponent(link.hash.slice(1))));
    let scheduled = false;
    const markSection = () => {
      const offset = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--header')) + 40;
      let active = -1;
      sections.forEach((section, index) => {
        if (section && section.getBoundingClientRect().top <= offset) active = index;
      });
      tocLinks.forEach((link, index) => {
        if (index === active) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
      });
      scheduled = false;
    };
    window.addEventListener('scroll', () => {
      if (!scheduled) {
        scheduled = true;
        requestAnimationFrame(markSection);
      }
    }, { passive: true });
    markSection();
  }

  // A small, local highlighter for the guide's JVM and JSON examples. Build the
  // spans with text nodes so code stays inert and copying preserves the source.
  const highlight = code => {
    if (!['kotlin', 'groovy', 'java', 'json'].includes(code.dataset.lang)) return;
    const tokens = /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b(?:import|package|class|public|private|protected|static|final|void|new|return|val|var|fun|if|else|try|catch|throw|throws|true|false|null)\b|\b\d+(?:\.\d+)?\b/g;
    const source = code.textContent;
    const fragment = document.createDocumentFragment();
    let cursor = 0;
    for (const match of source.matchAll(tokens)) {
      fragment.append(source.slice(cursor, match.index));
      const token = document.createElement('span');
      const value = match[0];
      const type = value.startsWith('/') ? 'comment' : /^["']/.test(value) ? 'string' : /^\d/.test(value) ? 'number' : 'keyword';
      token.className = `token-${type}`;
      token.textContent = value;
      fragment.append(token);
      cursor = match.index + value.length;
    }
    fragment.append(source.slice(cursor));
    code.replaceChildren(fragment);
  };

  document.querySelectorAll('.listingblock pre').forEach(pre => {
    const block = pre.closest('.listingblock');
    const code = pre.querySelector('code');
    const source = pre.textContent;
    const toolbar = document.createElement('div');
    toolbar.className = 'code-toolbar';
    const title = block.querySelector(':scope > .title') || document.createElement('span');
    if (!title.textContent) title.textContent = code?.dataset.lang || 'Code';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-button';
    button.textContent = 'Copy';
    button.setAttribute('aria-label', `Copy ${title.textContent.trim()} example`);
    button.setAttribute('aria-live', 'polite');
    button.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(source);
        button.textContent = 'Copied';
      } catch {
        const range = document.createRange();
        range.selectNodeContents(pre);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        button.textContent = 'Selected';
        button.setAttribute('aria-label', 'Code selected. Use your keyboard to copy.');
      }
      setTimeout(() => {
        button.textContent = 'Copy';
        button.setAttribute('aria-label', `Copy ${title.textContent.trim()} example`);
      }, 2500);
    });
    toolbar.append(title, button);
    block.prepend(toolbar);
    if (code) highlight(code);
  });

  document.querySelectorAll('table.tableblock').forEach(table => {
    const wrapper = document.createElement('div');
    wrapper.className = 'table-scroll';
    wrapper.tabIndex = 0;
    wrapper.setAttribute('role', 'region');
    wrapper.setAttribute('aria-label', table.caption?.textContent || 'Reference table, scroll horizontally if needed');
    table.before(wrapper);
    wrapper.append(table);
  });

  const index = links.indexOf(current);
  if (index >= 0) {
    const pagination = document.createElement('nav');
    pagination.className = 'page-pagination';
    pagination.setAttribute('aria-label', 'Previous and next pages');
    for (const [offset, label] of [[-1, 'Previous'], [1, 'Next']]) {
      const source = links[index + offset];
      if (!source) continue;
      const link = document.createElement('a');
      link.href = source.getAttribute('href');
      link.className = label.toLowerCase();
      const caption = document.createElement('span');
      caption.textContent = label === 'Next' ? 'Next →' : '← Previous';
      link.append(caption, source.textContent.trim());
      pagination.append(link);
    }
    content.append(pagination);
  }

  const dialog = document.querySelector('#docs-search');
  const searchToggle = document.querySelector('.search-toggle');
  const input = document.querySelector('#docs-search-input');
  const results = document.querySelector('#search-results');
  const status = document.querySelector('.search-status');
  const searchIndex = (window.awsEmulatorSearchIndex || []).map(entry => ({
    ...entry,
    label: `${entry.title} ${entry.page}`.toLowerCase(),
    body: entry.text.toLowerCase(),
    words: new Set(entry.text.toLowerCase().match(/[a-z0-9_]+/g) || [])
  }));
  if (!searchIndex.length || typeof dialog.showModal !== 'function') return;
  const guidePages = links.map(link => searchIndex.find(entry => entry.url === link.getAttribute('href'))).filter(Boolean);
  searchToggle.hidden = false;
  searchToggle.setAttribute('aria-label', 'Search documentation');
  searchToggle.querySelector('kbd').textContent = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘ K' : 'Ctrl K';

  const appendHighlight = (element, text, terms) => {
    // Highlight literal matches; never interpret a query as HTML or a regexp.
    const lower = text.toLowerCase();
    let cursor = 0;
    while (cursor < text.length) {
      const matches = terms.map(term => ({ at: lower.indexOf(term, cursor), term })).filter(match => match.at >= 0);
      matches.sort((a, b) => a.at - b.at || b.term.length - a.term.length);
      const next = matches[0];
      if (!next) break;
      element.append(text.slice(cursor, next.at));
      const mark = document.createElement('mark');
      mark.textContent = text.slice(next.at, next.at + next.term.length);
      element.append(mark);
      cursor = next.at + next.term.length;
    }
    element.append(text.slice(cursor));
  };
  const renderResults = () => {
    const query = input.value.trim().toLowerCase();
    const terms = [...new Set(query.split(/\s+/).filter(Boolean))];
    const matches = terms.length ? searchIndex.filter(entry => terms.every(term => `${entry.label} ${entry.body}`.includes(term)))
      .map(entry => ({ ...entry, score: (entry.title.toLowerCase().includes(query) ? 20 : 0)
        + terms.reduce((score, term) => score + (entry.label.includes(term) ? 5 : 0) + (entry.words.has(term) ? 2 : 0), 0)
        - (entry.url.startsWith('index.html') ? 3 : 0) }))
      .sort((a, b) => b.score - a.score) : guidePages;
    const visible = matches.slice(0, 12);
    status.textContent = terms.length
      ? matches.length ? `${matches.length} result${matches.length === 1 ? '' : 's'}${matches.length > 12 ? ' · showing the first 12' : ''}` : 'No results. Try a service name, task, or setting.'
      : 'Browse the guide, or search for a task, service, or setting.';
    results.replaceChildren();
    for (const entry of visible) {
      const item = document.createElement('li');
      const link = document.createElement('a');
      link.href = entry.url;
      if (entry.page !== entry.title) {
        const label = document.createElement('span');
        label.className = 'search-page';
        label.textContent = entry.page;
        link.append(label);
      }
      const title = document.createElement('strong');
      appendHighlight(title, entry.title, terms);
      const snippet = document.createElement('p');
      const found = terms.map(term => entry.body.indexOf(term)).filter(at => at >= 0);
      let start = found.length ? Math.max(0, Math.min(...found) - 45) : 0;
      const wordBoundary = entry.text.indexOf(' ', start);
      if (start > 0 && wordBoundary >= start && wordBoundary < start + 20) start = wordBoundary + 1;
      const text = (start ? '…' : '') + entry.text.slice(start, start + 160) + (entry.text.length > start + 160 ? '…' : '');
      appendHighlight(snippet, text, terms);
      link.append(title, snippet);
      item.append(link);
      results.append(item);
    }
    results.scrollTop = 0;
  };
  const openSearch = () => {
    setMenu(false);
    if (!dialog.open) dialog.showModal();
    renderResults();
    input.focus();
  };
  searchToggle.addEventListener('click', openSearch);
  input.addEventListener('input', renderResults);
  dialog.querySelector('.search-close').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => searchToggle.focus({ preventScroll: true }));
  dialog.addEventListener('click', event => {
    const bounds = dialog.getBoundingClientRect();
    if (event.target === dialog && (event.clientX < bounds.left || event.clientX > bounds.right
      || event.clientY < bounds.top || event.clientY > bounds.bottom)) dialog.close();
    if (event.target.closest('.search-results a')) dialog.close();
  });
  document.addEventListener('keydown', event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      if (dialog.open) dialog.close();
      else openSearch();
    }
  });
  dialog.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      // Search inputs otherwise consume the first Escape to clear their value.
      event.preventDefault();
      event.stopPropagation();
      dialog.close();
      return;
    }
    const matches = [...results.querySelectorAll('a')];
    if (event.key === 'Enter' && event.target === input && matches.length) {
      event.preventDefault();
      matches[0].click();
    }
    if (!['ArrowDown', 'ArrowUp'].includes(event.key) || !matches.length) return;
    event.preventDefault();
    const index = matches.indexOf(document.activeElement);
    const next = index + (event.key === 'ArrowDown' ? 1 : -1);
    if (next < 0) input.focus();
    else matches[Math.min(next, matches.length - 1)].focus();
  });
})();
