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

package io.github.gregwhitaker.awsemulator.aws.cloudformation;

import org.gradle.api.GradleException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Local, fail-closed validation of resource provisioning support before any emulator connection. */
final class CloudFormationTemplate {
    static final String EMULATOR_VERSION = "1.0.322";
    static final int MAX_BYTES = 51_200;
    private static final Set<String> PROVISIONED = provisionedTypes();
    private static final Set<Tag> SCALAR_TAGS = Set.of(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL, Tag.TIMESTAMP);
    private static final Set<String> INTRINSICS = Set.of(
        "!Ref", "!Condition", "!Base64", "!Cidr", "!FindInMap", "!GetAtt", "!GetAZs", "!ImportValue",
        "!Join", "!Select", "!Split", "!Sub", "!If", "!And", "!Equals", "!Not", "!Or");

    private CloudFormationTemplate() {}

    static String read(Path path, String emulatorVersion) {
        if (!EMULATOR_VERSION.equals(emulatorVersion)) {
            throw new GradleException("CloudFormation provisioning validation is audited only for AWS Emulator "
                + EMULATOR_VERSION + "; configured emulatorVersion is " + emulatorVersion + ".");
        }
        String body;
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new GradleException("CloudFormation templateFile must contain 1–51200 UTF-8 bytes.");
            }
            body = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (GradleException error) { throw error; }
        catch (Exception error) { throw new GradleException("Cannot read CloudFormation templateFile as UTF-8 (contents omitted)."); }
        validate(body);
        return body;
    }

    static void validate(String body) {
        Node root;
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(50);
            options.setCodePointLimit(MAX_BYTES);
            // Compose a syntax tree only: never construct user-provided Java objects.
            root = new Yaml(options).compose(new StringReader(body));
        } catch (Exception error) {
            throw new GradleException("Invalid CloudFormation JSON/YAML template: check syntax, document count, "
                + "nesting, and aliases (contents omitted).");
        }
        if (root == null) throw new GradleException("CloudFormation template must be a nonempty object.");
        inspect(root, Collections.newSetFromMap(new IdentityHashMap<>()));
        Map<String, Node> document = mapping(root, "CloudFormation template");
        if (document.containsKey("Transform")) {
            throw new GradleException("CloudFormation Transform (including SAM and macros) is not supported: "
                + "expanded resource types cannot be checked locally. Supply a fully expanded template.");
        }
        Map<String, Node> resources = mapping(document.get("Resources"), "Resources");
        if (resources.isEmpty()) throw new GradleException("CloudFormation Resources must not be empty.");
        var failures = new ArrayList<String>();
        resources.forEach((logicalId, declaration) -> {
            if (!logicalId.matches("[A-Za-z][A-Za-z0-9]*")) {
                throw new GradleException("CloudFormation resource logical IDs must be alphanumeric and start with a letter.");
            }
            Node typeNode = mapping(declaration, "Resources." + logicalId).get("Type");
            if (!(typeNode instanceof ScalarNode scalar) || !Tag.STR.equals(typeNode.getTag())) {
                failures.add("Resources." + logicalId + ": Type must be a literal resource-type string.");
                return;
            }
            String type = scalar.getValue();
            String reason = null;
            if (type.equals("AWS::CloudFormation::Stack")) {
                reason = "nested templates cannot be inspected; deploy each child template with its own guarded task";
            } else if (type.equals("AWS::CloudFormation::CustomResource") || type.startsWith("Custom::")) {
                reason = "custom providers can create uninspectable resources; use explicit resource tasks";
            } else if (!PROVISIONED.contains(type) && !type.equals("AWS::CDK::Metadata")
                    && !type.equals("AWS::CloudFormation::WaitCondition")) {
                reason = "no audited provisioning handler; the emulator may report success without creating a resource";
            }
            if (reason != null) failures.add("Resources." + logicalId + " (" + type + "): " + reason + ".");
        });
        if (!failures.isEmpty()) {
            throw new GradleException("CloudFormation template rejected for AWS Emulator " + EMULATOR_VERSION
                + ":\n - " + String.join("\n - ", failures)
                + "\nNo stack was submitted. Use supported resource types or the plugin's service-specific tasks.");
        }
    }

    private static void inspect(Node node, Set<Node> seen) {
        if (!seen.add(node) || node.getAnchor() != null) {
            throw new GradleException("CloudFormation YAML anchors and aliases are not supported; expand them first.");
        }
        Tag tag = node.getTag();
        if (!Tag.MAP.equals(tag) && !Tag.SEQ.equals(tag) && !SCALAR_TAGS.contains(tag)
                && !INTRINSICS.contains(tag.getValue())) {
            throw new GradleException("Unsupported CloudFormation YAML tag; use standard intrinsic functions "
                + "and expand transforms before deployment.");
        }
        if (node instanceof MappingNode map) {
            // Enforce duplicates ourselves: composition intentionally skips object construction.
            var names = new java.util.HashSet<String>();
            for (NodeTuple entry : map.getValue()) {
                if (!(entry.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())) {
                    throw new GradleException("CloudFormation mapping keys must be literal strings; YAML merges are not supported.");
                }
                if (!names.add(key.getValue())) throw new GradleException("Duplicate CloudFormation mapping key (contents omitted).");
                if (key.getValue().equals("Fn::Transform") || key.getValue().startsWith("Fn::ForEach")) {
                    throw new GradleException("CloudFormation Fn::Transform and Fn::ForEach are not supported: "
                        + "supply a fully expanded template so every resource type can be checked.");
                }
                inspect(entry.getKeyNode(), seen);
                inspect(entry.getValueNode(), seen);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) inspect(child, seen);
        }
    }

    private static Map<String, Node> mapping(Node node, String label) {
        if (!(node instanceof MappingNode map) || !Tag.MAP.equals(node.getTag())) {
            throw new GradleException(label + " must be an object.");
        }
        var result = new LinkedHashMap<String, Node>();
        for (NodeTuple entry : map.getValue()) result.put(((ScalarNode) entry.getKeyNode()).getValue(), entry.getValueNode());
        return result;
    }

    private static Set<String> provisionedTypes() {
        try (var input = CloudFormationTemplate.class.getResourceAsStream("provisioned-types-" + EMULATOR_VERSION + ".txt")) {
            if (input == null) throw new IllegalStateException("Missing CloudFormation provisioning catalogue.");
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toUnmodifiableSet());
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot load CloudFormation provisioning catalogue.", error); }
    }
}
