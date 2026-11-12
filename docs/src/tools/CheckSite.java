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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Checks local references only; documentation builds never depend on external sites. */
class CheckSite {
    record Page(Set<String> ids, List<String> references, boolean redirect) {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Map<Path, Page> pages = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
                pages.put(path, read(path));
            }
        }
        List<String> failures = new ArrayList<>();
        if (!pages.containsKey(root.resolve("index.html"))) failures.add("Missing index.html");
        for (var entry : pages.entrySet()) {
            Path source = entry.getKey();
            // Also detect doclet markup changes that would silently omit the API header.
            if ((!entry.getValue().redirect() && !entry.getValue().ids().contains("docs-theme-toggle"))
                    || entry.getValue().references().stream().noneMatch(ref -> ref.endsWith("assets/theme.js"))
                    || entry.getValue().references().stream().noneMatch(ref -> ref.endsWith("assets/theme.css"))) {
                failures.add(root.relativize(source) + " -> missing shared theme assets or toggle");
            }
            for (String reference : entry.getValue().references()) {
                try {
                    URI uri = URI.create(reference);
                    if (uri.isAbsolute() || uri.getRawAuthority() != null) continue;
                    String path = uri.getPath();
                    Path target = path == null || path.isEmpty() ? source
                        : (path.startsWith("/") ? root.resolve(path.substring(1)) : source.getParent().resolve(path));
                    target = target.normalize();
                    if (Files.isDirectory(target)) target = target.resolve("index.html");
                    if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                        failures.add(root.relativize(source) + " -> missing file: " + reference);
                    } else if (uri.getFragment() != null && !uri.getFragment().isEmpty()
                            && pages.containsKey(target) && !pages.get(target).ids().contains(uri.getFragment())) {
                        failures.add(root.relativize(source) + " -> missing anchor: " + reference);
                    }
                } catch (IllegalArgumentException ex) {
                    failures.add(root.relativize(source) + " -> invalid reference: " + reference);
                }
            }
        }
        if (!failures.isEmpty()) {
            failures.stream().sorted().forEach(System.err::println);
            throw new IllegalStateException(failures.size() + " broken documentation references");
        }
        System.out.println("Verified local links, anchors, assets, and theme controls in " + pages.size() + " HTML pages.");
    }

    private static Page read(Path path) throws IOException {
        Set<String> ids = new HashSet<>();
        List<String> references = new ArrayList<>();
        var callback = new HTMLEditorKit.ParserCallback() {
            boolean redirect;
            private void collect(MutableAttributeSet attributes) {
                Object id = attributes.getAttribute(HTML.Attribute.ID);
                if (id != null) ids.add(id.toString());
                for (var attribute : List.of(HTML.Attribute.HREF, HTML.Attribute.SRC)) {
                    Object reference = attributes.getAttribute(attribute);
                    if (reference != null) references.add(reference.toString());
                }
            }
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                if (tag == HTML.Tag.BODY) {
                    redirect = "index-redirect-page".equals(attributes.getAttribute(HTML.Attribute.CLASS));
                }
                collect(attributes);
            }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                collect(attributes);
            }
        };
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            new ParserDelegator().parse(reader, callback, true);
        }
        return new Page(ids, references, callback.redirect);
    }
}
