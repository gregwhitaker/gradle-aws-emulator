# Documentation site

This Gradle subproject generates a static, offline-capable HTML guide with the plugin's Java API reference. It uses [AsciidoctorJ](https://docs.asciidoctor.org/asciidoctorj/latest/) through a pinned JVM CLI dependency. Java 17+ and the repository's Gradle wrapper are sufficient; no Node, Python, Ruby installation, Docker daemon, or AWS account is required to build the site.

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

The guide's header has a GitHub badge linking to the repository and showing its star count from GitHub's public API. Counts are cached in the browser tab for 15 minutes. Offline, without JavaScript, or when the API is unavailable, the badge remains a usable link labeled **Star**. This optional request does not affect documentation content or search.

## GitHub Pages

The [Deploy docs to GitHub Pages workflow](../.github/workflows/pages.yml) publishes the guide and Java API reference from the latest published stable release tag. The [publication workflow](../.github/workflows/publish.yml) calls it after both plugins have been uploaded successfully and the GitHub Release has been published. Prereleases do not update the public site. The existing CI workflow still builds, checks, and packages documentation on pushes to `main` and on pull requests.

Deployment verifies that the tag identifies the published commit, matches the release metadata, and is GitHub's latest stable release. Both plugin markers and their implementation POMs and JARs must be publicly available from the Plugin Portal. The workflow builds the tagged sources with `./gradlew :docs:check --no-daemon` using Java 17, then rechecks the release immediately before deployment. Rerunning an older release cannot replace the latest release's documentation.

To enable deployment:

1. Open the repository's **Settings > Pages** and set **Build and deployment > Source** to **GitHub Actions**, as described in [GitHub's publishing instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site#publishing-with-a-custom-github-actions-workflow).
2. In **Settings > Environments > github-pages**, allow release tags matching `v*` as deployment sources. If the environment uses selected branches and tags, retain `main` for manual redeployment and add a **Tag** rule for `v*`.
3. Commit and push the workflows to `main`. The next successful stable release automatically updates the site.
4. To deploy or redeploy the current stable release manually, open **Actions > Deploy docs to GitHub Pages > Run workflow**, select `main` for the workflow, and enter its published tag (for example, `v0.2.0`). This uses the deployment tools from `main` but builds all documentation, examples, and Javadoc from the tag, including releases that predate this workflow.

After a successful deployment, the site is available at <https://gregwhitaker.github.io/gradle-aws-emulator/>. The deployment job also reports the site URL through the `github-pages` environment. The workflow uses GitHub's built-in token; no personal access token or additional repository secret is required. Deployment permissions are scoped to the deployment job, and deployments run one at a time without canceling an active run.

For a plugin release, follow the [release guide](../RELEASING.md), including the
Plugin Portal installation examples and version updates. While `main` develops
a newer version, the public site stays on the latest published stable release.
Keep updating the guide alongside code on `main`; documentation corrections go
live with the next stable release. Manual redeployment rebuilds the existing
release without incorporating subsequent changes from `main`.

If Portal approval or propagation is pending, deployment fails without replacing
the existing site. Once both plugins are available, rerun only the failed docs
jobs or use manual redeployment; do not republish the plugins. A failed docs build
or deployment can be recovered the same way. The publication workflow calls Pages
directly because GitHub Releases created with `GITHUB_TOKEN` do not trigger a
separate `release` workflow; no additional token is needed.

## Authoring

- Add top-level pages in `src/docs/asciidoc/`. Each page starts with a title followed by `include::_partials/attributes.adoc[]`.
- Capitalize each word in page and section headings, preserving acronyms and official product names.
- Use `xref:page.adoc#section-id[Label]` for internal links. Top-level pages are discovered automatically at configuration time. Add each new page to the navigation in `_partials/docinfo-header.html`.
- The primary Resources and Integrations links open `resources.adoc` and `integrations.adoc`. Their sidebar groups use `data-section` to keep the matching primary link active on overview and detail pages.
- Give each supported AWS service its own guide page, linked directly from the alphabetized AWS resources navigation and `_partials/resource-links.adoc`, shared by the resources overview and homepage. Keep service task references on that service's page and cross-link related workflows. Preserve heading IDs when editing. The active entry and previous/next links follow page navigation, including browser back and forward.
- Give each framework its own guide page, linked directly from the Integrations navigation and `_partials/integration-links.adoc`, shared by the integrations overview and homepage. Keep shared task-configuration snippets in partials.
- Keep reusable partials under `_partials/`; they are included, not rendered as standalone pages.
- The getting-started guide includes the actual build scripts, SDK test, and message fixtures from `examples/basic`. `prepareDocs` stages those files so source changes invalidate generation.
- The separate Micronaut and Ktor guides include their own build scripts and application code from `examples/micronaut` and `examples/ktor`; `prepareDocs` stages these examples too.
- Update styles and browser behavior in `src/site/assets/`. Assets are local; the guide has no CDN or web-font dependency. JavaScript enhances navigation, section search, copy buttons, JVM/JSON syntax colors, and tables; content and links remain usable without it.
- `indexGuide` runs `src/tools/IndexGuide.java` after rendering and indexes the current guide pages by heading. It excludes site navigation and the table of contents. `site` includes the resulting `assets/search-index.js`; adding or removing a guide page updates the index automatically. No separate search service or package installation is needed.
- `theme.css` and `theme.js` define the shared palette, toggle, and preference. `api.css` adapts Javadoc's tables, navigation, search results, and method filters to that palette.
- `site` injects `src/templates/api-head.html` and `api-header.html` into copied Javadoc pages, resolving asset and guide links relative to each page's depth. The plugin's standalone Javadoc output and Javadoc JAR are unchanged. The link checker requires shared theme assets on every page and a toggle on every page with content; Javadoc's redirect pages do not need a header.
- `pluginVersion` in the root `gradle.properties` is shared with the plugin artifact and injected as the AsciiDoc `revnumber` attribute. Use `{revnumber}` in versioned Plugin Portal snippets with `subs="+attributes"` on their source blocks. Keep the README installation examples aligned with the released version.
- `emulatorVersion` in `gradle/emulator.properties` is shared with the runtime and injected as `{emulator-version}`. Use this attribute when describing the current default. Keep version-specific audit evidence explicit until it has been reviewed for a new release; see [AWS Emulator upgrades](../CONTRIBUTING.md#aws-emulator-upgrades).
- Asciidoctor warnings, missing includes, and unresolved attributes fail generation. `src/tools/CheckSite.java` checks the output using only the JDK.

The output is ignored by Git. `site` uses Gradle `Sync` and copies only current source pages, removing obsolete pages from the final site. The ZIP uses stable file ordering and omits file timestamps. Javadoc output follows the JDK used by Gradle; for byte-identical builds, keep the JDK and dependency versions fixed.
