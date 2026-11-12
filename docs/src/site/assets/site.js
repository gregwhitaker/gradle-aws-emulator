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
  const links = [...nav.querySelectorAll(':scope > a')];
  const current = links.find(link => link.getAttribute('href') === page);
  current?.setAttribute('aria-current', 'page');

  menu.hidden = false;
  const closeMenu = () => {
    nav.classList.remove('is-open');
    menu.setAttribute('aria-expanded', 'false');
    menu.textContent = 'Menu';
  };
  menu.addEventListener('click', () => {
    const open = nav.classList.toggle('is-open');
    menu.setAttribute('aria-expanded', String(open));
    menu.textContent = open ? 'Close' : 'Menu';
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && nav.classList.contains('is-open')) {
      closeMenu();
      menu.focus();
    }
  });
  nav.addEventListener('click', event => {
    if (event.target.closest('a')) closeMenu();
  });

  document.querySelectorAll('.listingblock pre').forEach(pre => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-button';
    button.textContent = 'Copy';
    button.setAttribute('aria-label', 'Copy code example');
    button.setAttribute('aria-live', 'polite');
    button.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(pre.textContent);
        button.textContent = 'Copied';
      } catch {
        const range = document.createRange();
        range.selectNodeContents(pre);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        button.textContent = 'Selected — copy with keyboard';
      }
      setTimeout(() => { button.textContent = 'Copy'; }, 2500);
    });
    pre.parentElement.append(button);
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
      const caption = document.createElement('span');
      caption.textContent = label;
      link.append(caption, source.textContent.trim());
      pagination.append(link);
    }
    document.querySelector('#content').append(pagination);
  }
})();
