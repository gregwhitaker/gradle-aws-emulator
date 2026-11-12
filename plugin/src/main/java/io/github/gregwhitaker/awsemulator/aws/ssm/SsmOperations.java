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

package io.github.gregwhitaker.awsemulator.aws.ssm;

import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SsmOperations {
    static final String NAME = "[a-zA-Z0-9_./-]{1,1011}";
    record Parameter(String name, String value, String type, String keyId) {
        Parameter {
            ServiceSupport.name(name, NAME, "parameter name");
            String normalized = name.startsWith("/") ? name.substring(1) : name;
            if (normalized.toLowerCase(Locale.ROOT).matches("^(aws|ssm).*")) throw new GradleException("Parameter names cannot start with aws or ssm.");
            if (normalized.isEmpty() || normalized.contains("//") || normalized.endsWith("/") || normalized.split("/").length > 15) {
                throw new GradleException("Parameter name has an invalid hierarchy.");
            }
            if (!Set.of("String", "StringList", "SecureString").contains(type)) throw new GradleException("Parameter type must be String, StringList, or SecureString.");
            if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 4096) throw new GradleException("Parameter value must contain 1–4096 UTF-8 bytes.");
            if (keyId != null && (!type.equals("SecureString") || keyId.isBlank())) throw new GradleException("keyId requires a SecureString parameter and a nonblank key identifier.");
        }
        @Override public String toString() { return "Parameter[" + name + ", value omitted]"; }
    }

    void put(SsmClient client, Parameter p, boolean overwrite) {
        try {
            software.amazon.awssdk.services.ssm.model.Parameter existing = null;
            try { existing = client.getParameter(GetParameterRequest.builder().name(p.name()).withDecryption(true).build()).parameter(); }
            catch (ParameterNotFoundException ignored) { }
            String existingKey = null;
            if (existing != null && existing.type() == ParameterType.SECURE_STRING) {
                existingKey = list(client).stream().filter(m -> m.name().equals(p.name())).map(ParameterMetadata::keyId).filter(Objects::nonNull).findFirst().orElse(null);
            }
            boolean keyMatches = p.keyId() == null || Objects.equals(p.keyId(), existingKey);
            if (existing != null && Objects.equals(existing.value(), p.value()) && existing.typeAsString().equals(p.type()) && keyMatches) return;
            if (existing != null && !overwrite) throw new GradleException("Parameter '" + p.name() + "' differs; set overwrite=true to replace it (values omitted).");
            if (existing != null && !existing.typeAsString().equals(p.type())) throw new GradleException("Parameter '" + p.name() + "' has a different type; delete it explicitly before recreating it.");
            client.putParameter(PutParameterRequest.builder().name(p.name()).value(p.value()).type(p.type())
                .keyId(p.keyId() != null ? p.keyId() : existingKey).tier(ParameterTier.STANDARD).overwrite(existing != null && overwrite).build());
        } catch (software.amazon.awssdk.core.exception.SdkException e) { throw safe("Writing parameter '" + p.name() + "'", e); }
    }

    List<ParameterMetadata> list(SsmClient client) {
        List<ParameterMetadata> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.describeParameters(DescribeParametersRequest.builder().maxResults(50).nextToken(token).build());
            result.addAll(page.parameters()); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    void delete(SsmClient client, String name) {
        try { client.deleteParameter(DeleteParameterRequest.builder().name(name).build()); }
        catch (ParameterNotFoundException ignored) { }
    }

    private GradleException safe(String operation, software.amazon.awssdk.core.exception.SdkException e) {
        String code = e instanceof SsmException service && service.awsErrorDetails() != null
            ? service.awsErrorDetails().errorCode() : e.getClass().getSimpleName();
        return new GradleException(operation + " failed (" + code + "; values omitted).");
    }
}
