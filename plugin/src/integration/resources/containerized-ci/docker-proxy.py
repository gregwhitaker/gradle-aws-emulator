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

# Test-only Docker API relay, reachable only on an isolated Docker network.
import selectors
import socket
import socketserver


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as daemon:
            daemon.connect("/var/run/docker.sock")
            with selectors.DefaultSelector() as selector:
                selector.register(self.request, selectors.EVENT_READ, daemon)
                selector.register(daemon, selectors.EVENT_READ, self.request)
                while True:
                    for key, _ in selector.select():
                        data = key.fileobj.recv(65536)
                        if not data:
                            return
                        key.data.sendall(data)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


with Server(("0.0.0.0", 2375), Relay) as server:
    server.serve_forever()
