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

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorVersion;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** An upgrade cannot pass the build by changing only the shared runtime default. */
class CloudFormationAuditTest {
    @Test void defaultVersionHasAnAuditedCatalogue() throws Exception {
        String version = EmulatorVersion.DEFAULT;
        assertEquals(version, CloudFormationTemplate.EMULATOR_VERSION,
            "CloudFormation audit required: review upstream provisioning handlers, update the versioned catalogue "
                + "and its source commit, and update CloudFormationTemplate.EMULATOR_VERSION. See CONTRIBUTING.md.");
        try (var input = CloudFormationTemplate.class.getResourceAsStream("provisioned-types-" + version + ".txt")) {
            assertNotNull(input, "Missing version-specific CloudFormation provisioning catalogue.");
            String catalogue = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertTrue(catalogue.startsWith("# Native CloudFormation provisioning dispatch in AWS Emulator v" + version + ".\n"),
                "The catalogue must identify the audited emulator version.");
            assertTrue(catalogue.matches("(?s).*\n# Audited commit: [0-9a-f]{40}\n.*"),
                "The catalogue must record the reviewed upstream commit.");
            for (String source : new String[] {"provisioner.rs", "provisioning.rs"}) {
                assertTrue(catalogue.contains("# https://github.com/aws/aws-emulator/blob/v" + version
                    + "/services/service-com-amazonaws-cloudformation-cloudformation/src/state/" + source),
                    "Update the upstream source reference for " + source + " after reviewing its provisioning handlers.");
            }
        }
    }
}
