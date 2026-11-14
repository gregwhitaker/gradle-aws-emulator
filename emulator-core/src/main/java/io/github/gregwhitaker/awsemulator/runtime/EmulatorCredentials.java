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

package io.github.gregwhitaker.awsemulator.runtime;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.profiles.ProfileFile;
import java.nio.file.Path;

/** Reads only explicit emulator credentials and keeps parser diagnostics from exposing their contents. */
public final class EmulatorCredentials {
    private EmulatorCredentials() {}

    /** Reads the default profile from the given file; invalid input fails without exposing credential contents. */
    public static AwsCredentials read(Path file) {
        try {
            var profile = ProfileFile.builder().type(ProfileFile.Type.CREDENTIALS).content(file)
                .build().profile("default").orElseThrow();
            String access = profile.property("aws_access_key_id").filter(value -> !value.isBlank()).orElseThrow();
            String secret = profile.property("aws_secret_access_key").filter(value -> !value.isBlank()).orElseThrow();
            String token = profile.property("aws_session_token").filter(value -> !value.isBlank()).orElse(null);
            return token == null ? AwsBasicCredentials.create(access, secret) : AwsSessionCredentials.create(access, secret, token);
        } catch (RuntimeException e) {
            // Profile parser exceptions may quote the offending line, including credential values.
            throw new IllegalArgumentException("Cannot read AWS Emulator credentials. Supply a readable credentialsFile with "
                + "a [default] profile containing aws_access_key_id and aws_secret_access_key.");
        }
    }
}
