// Run before the stylesheet so a saved dark theme is applied before first paint.
(() => {
  const storageKey = 'aws-emulator-docs-theme';
  const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');
  const root = document.documentElement;
  const validTheme = value => value === 'light' || value === 'dark';
  let preference;
  try {
    const saved = localStorage.getItem(storageKey);
    if (validTheme(saved)) preference = saved;
  } catch {
    // The toggle still works when the browser disallows persistent storage.
  }

  const applyTheme = () => {
    const theme = preference || (systemTheme.matches ? 'dark' : 'light');
    root.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute(
      'content', theme === 'dark' ? '#111827' : '#ffffff');
    const next = theme === 'dark' ? 'light' : 'dark';
    document.querySelectorAll('.theme-toggle').forEach(button => {
      button.setAttribute('aria-checked', String(theme === 'dark'));
      button.title = `Switch to ${next} theme`;
    });
  };

  applyTheme();
  systemTheme.addEventListener('change', () => {
    if (!preference) applyTheme();
  });
  window.addEventListener('storage', event => {
    if (event.key !== storageKey && event.key !== null) return;
    preference = validTheme(event.newValue) ? event.newValue : undefined;
    applyTheme();
  });
  document.addEventListener('DOMContentLoaded', () => {
    applyTheme();
    document.querySelectorAll('.theme-toggle').forEach(button => {
      button.hidden = false;
      button.addEventListener('click', () => {
        preference = root.dataset.theme === 'dark' ? 'light' : 'dark';
        try {
          localStorage.setItem(storageKey, preference);
        } catch {
          // Keep the selected theme for this page even when storage is unavailable.
        }
        applyTheme();
      });
    });
  });
})();
