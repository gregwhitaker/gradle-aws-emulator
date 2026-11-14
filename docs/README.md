# Documentation site

This Gradle subproject generates a static, offline-capable HTML guide with the plugins' and standalone libraries' Java API references. It uses [AsciidoctorJ](https://docs.asciidoctor.org/asciidoctorj/latest/) through a pinned JVM CLI dependency. Java 17+ and the repository's Gradle wrapper are sufficient; no Node, Python, Ruby installation, Docker daemon, or AWS account is required to build the site.

Run these commands from the repository root:

```sh
./gradlew :docs:site
./gradlew :docs:check
./gradlew :docs:siteZip
```

- `site` generates `docs/build/site/index.html`, local assets, `api/` from `:plugin:javadoc`, and `spring-boot-api/` from `:spring-boot-plugin:javadoc`.
- `check` verifies every generated HTML page's local links, fragment targets, assets, shared theme controls, and guide search controls. External URLs are not fetched.
- `siteZip` validates and packages the site at `docs/build/distributions/aws-emulator-docs-<version>.zip`.
- `build` generates, verifies, and packages the site. The root `build` includes this project, so the existing CI build checks documentation too.
- `clean` removes generated documentation. The root `clean` includes this project.

The tasks support `--configuration-cache`. Open `docs/build/site/index.html` directly in a browser. Optionally serve the directory with any static HTTP server. For example, if Python is installed:

```sh
python3 -m http.server 8000 --bind 127.0.0.1 --directory docs/build/site
```

Then visit <http://127.0.0.1:8000>. Upload the contents of `docs/build/site/` (or the unzipped archive) to a static host. Links are relative and also work under a path prefix. Local builds do not publish or require a hosting account.

The guide and Java API reference include the same light/dark theme toggle on desktop and mobile. They follow the system theme until a reader chooses one, then share that preference through browser storage. The light theme uses white and neutral gray; the dark theme uses charcoal surfaces and white headings. Blue accents identify links, active navigation, and focus states. Both use dark gray code panels with blue and violet syntax colors. Printing uses a light palette.

The overview pairs an introduction with a real build example on wide screens and stacks them on smaller screens. It provides a runnable command, lifecycle choices, and links to service guides. The source remains an ordinary AsciiDoc article, readable without JavaScript.

The guide has grouped navigation, a page outline that stays beside the article on wide screens and collapses on smaller screens, and copyable code examples. Search opens from the header or with **⌘ K / Ctrl K**, matches guide text and code, and links directly to sections. Use the arrow keys to move through results, Enter to open one, and Escape to close. The generated index is a local script, so search also works when opening the site directly from disk. The Java API retains its own Javadoc search.

## GitHub Pages

The [Deploy docs to GitHub Pages workflow](../.github/workflows/pages.yml) publishes the guide and Java API reference on pushes to `main`. It runs `./gradlew :docs:check --no-daemon` with Java 17, uploads `docs/build/site`, and deploys only after the build and link checks succeed. The existing CI workflow also checks the documentation on pull requests.

To enable deployment:

1. Open the repository's **Settings > Pages** and set **Build and deployment > Source** to **GitHub Actions**, as described in [GitHub's publishing instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site#publishing-with-a-custom-github-actions-workflow).
2. Commit and push the workflow and documentation changes to `main`. Later pushes to `main` automatically update the site.
3. To redeploy manually, open **Actions > Deploy docs to GitHub Pages > Run workflow** and select `main`. Runs on other branches can build the site but do not deploy it.

After a successful deployment, the site is available at <https://gregwhitaker.github.io/gradle-aws-emulator/>. The deployment job also reports the site URL through the `github-pages` environment. The workflow uses GitHub's built-in token; no personal access token or additional repository secret is required. Deployment permissions are scoped to the deployment job, and deployments run one at a time without canceling an active run.

## Authoring

- Add top-level pages in `src/docs/asciidoc/`. Each page starts with a title followed by `include::_partials/attributes.adoc[]`.
- Use `xref:page.adoc#section-id[Label]` for internal links. Top-level pages are discovered automatically at configuration time. Add each new page to the navigation in `_partials/docinfo-header.html`.
- Keep reusable partials under `_partials/`; they are included, not rendered as standalone pages.
- The getting-started guide includes the actual build scripts, SDK test, and message fixtures from `examples/basic`. `prepareDocs` stages those files so source changes invalidate generation.
- The Micronaut and Ktor guide includes build scripts and application code from `examples/micronaut` and `examples/ktor`; `prepareDocs` stages these examples too.
- Update styles and browser behavior in `src/site/assets/`. Assets are local; the guide has no CDN or web-font dependency. JavaScript enhances navigation, section search, copy buttons, JVM/JSON syntax colors, and tables; content and links remain usable without it.
- `indexGuide` runs `src/tools/IndexGuide.java` after rendering and indexes the current guide pages by heading. It excludes site navigation and the table of contents. `site` includes the resulting `assets/search-index.js`; adding or removing a guide page updates the index automatically. No separate search service or package installation is needed.
- `theme.css` and `theme.js` define the shared palette, toggle, and preference. `api.css` adapts Javadoc's tables, navigation, search results, and method filters to that palette.
- `site` injects `src/templates/api-head.html` and `api-header.html` into copied Javadoc pages, resolving asset and guide links relative to each page's depth. The plugin's standalone Javadoc output and Javadoc JAR are unchanged. The link checker requires shared theme assets on every page and a toggle on every page with content; Javadoc's redirect pages do not need a header.
- `pluginVersion` in the root `gradle.properties` is shared with the plugin artifact and injected as the AsciiDoc `revnumber` attribute. Update publication-status text when the plugin is released.
- Asciidoctor warnings, missing includes, and unresolved attributes fail generation. `src/tools/CheckSite.java` checks the output using only the JDK.

The output is ignored by Git. `site` uses Gradle `Sync` and copies only current source pages, removing obsolete pages from the final site. The ZIP uses stable file ordering and omits file timestamps. Javadoc output follows the JDK used by Gradle; for byte-identical builds, keep the JDK and dependency versions fixed.
