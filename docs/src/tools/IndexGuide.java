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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Builds a local script so guide search also works when opened through file://. */
class IndexGuide {
    record Section(String page, String title, String url, String text) {
        String json() {
            return "{\"page\":" + quote(page) + ",\"title\":" + quote(title)
                + ",\"url\":" + quote(url) + ",\"text\":" + quote(text) + "}";
        }
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        Path output = Path.of(args[1]);
        List<Section> sections = new ArrayList<>();
        // Explicit current page names prevent deleted guides left in render output
        // from entering the index. The site Sync task uses the same list.
        for (int i = 2; i < args.length; i++) {
            sections.addAll(read(root.resolve(args[i])));
        }
        if (sections.isEmpty()) throw new IllegalStateException("No guide sections to index");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "window.awsEmulatorSearchIndex = [\n"
            + String.join(",\n", sections.stream().map(Section::json).toList()) + "\n];\n",
            StandardCharsets.UTF_8);
        System.out.println("Indexed " + sections.size() + " sections from " + (args.length - 2) + " guide pages.");
    }

    private static List<Section> read(Path path) throws IOException {
        List<Section> sections = new ArrayList<>();
        var parser = new HTMLEditorKit.ParserCallback() {
            final StringBuilder title = new StringBuilder();
            final StringBuilder heading = new StringBuilder();
            final StringBuilder body = new StringBuilder();
            String sectionTitle;
            String anchor = "";
            boolean inTitle;
            boolean inHeading;
            int contentDepth;
            int ignoredDepth;

            private void finishSection() {
                if (sectionTitle == null) return;
                sections.add(new Section(normalize(title), sectionTitle,
                    path.getFileName() + (anchor.isEmpty() ? "" : "#" + anchor), normalize(body)));
                body.setLength(0);
            }

            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                if (tag == HTML.Tag.TITLE) inTitle = true;
                if (tag == HTML.Tag.DIV) {
                    if (contentDepth > 0) contentDepth++;
                    else if ("content".equals(attributes.getAttribute(HTML.Attribute.ID))) {
                        contentDepth = 1;
                        sectionTitle = normalize(title);
                    }
                    if (ignoredDepth > 0) ignoredDepth++;
                    else if ("toc".equals(attributes.getAttribute(HTML.Attribute.ID))) ignoredDepth = 1;
                }
                if (contentDepth == 0 || ignoredDepth > 0) return;
                if (tag == HTML.Tag.H2 || tag == HTML.Tag.H3 || tag == HTML.Tag.H4) {
                    finishSection();
                    Object id = attributes.getAttribute(HTML.Attribute.ID);
                    anchor = id == null ? "" : id.toString();
                    inHeading = true;
                    heading.setLength(0);
                }
                body.append(' ');
            }

            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.TITLE) inTitle = false;
                if (ignoredDepth > 0) {
                    if (tag == HTML.Tag.DIV) {
                        ignoredDepth--;
                        if (contentDepth > 0) contentDepth--;
                    }
                    return;
                }
                if (contentDepth == 0) return;
                if (tag == HTML.Tag.H2 || tag == HTML.Tag.H3 || tag == HTML.Tag.H4) {
                    sectionTitle = normalize(heading);
                    inHeading = false;
                }
                if (tag == HTML.Tag.DIV && --contentDepth == 0) finishSection();
                body.append(' ');
            }

            @Override public void handleText(char[] data, int pos) {
                if (inTitle) title.append(data);
                else if (contentDepth > 0 && ignoredDepth == 0) {
                    if (inHeading) heading.append(data);
                    else body.append(data);
                }
            }
        };
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            new ParserDelegator().parse(reader, parser, true);
        }
        if (sections.isEmpty()) throw new IllegalStateException("Missing guide content: " + path);
        return sections;
    }

    private static String normalize(StringBuilder text) {
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '<' -> result.append("\\u003c");
                case '>' -> result.append("\\u003e");
                case '&' -> result.append("\\u0026");
                default -> {
                    if (character < 0x20 || character == '\u2028' || character == '\u2029') {
                        result.append(String.format("\\u%04x", (int) character));
                    } else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
