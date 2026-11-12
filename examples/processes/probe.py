#
# Copyright 2026 Greg Whitaker
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""HTTPS verification without third-party dependencies; boto3 uses the same AWS_* environment."""
import os
import ssl
import sys
import urllib.request
from pathlib import Path

endpoint = os.environ["AWS_ENDPOINT_URL"]
assert Path(os.environ["AWS_SHARED_CREDENTIALS_FILE"]).is_file()
assert Path(os.environ["AWS_CONFIG_FILE"]).is_file()
context = ssl.create_default_context(cafile=os.environ["AWS_CA_BUNDLE"])
with urllib.request.urlopen(endpoint + "/__admin/health", context=context, timeout=10) as response:
    assert response.status == 200 and response.read().strip() == b"ok"
print("Python connected to " + endpoint)
if len(sys.argv) > 1 and sys.argv[1] == "true":
    raise RuntimeError("Intentional process failure")
