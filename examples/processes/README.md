# External processes and connection exports

This independent build attaches Python, Node, and AWS CLI `Exec` tasks to the emulator. Run from the repository root with Java 17+, Gradle 8.14+, and Docker. Install only the runtimes for the tasks you run; the Python and Node probes use their standard libraries.

```sh
./gradlew -p examples/processes pythonProbe nodeProbe --configuration-cache
./gradlew -p examples/processes pythonProbe nodeProbe --configuration-cache
./gradlew -p examples/processes awsIdentity --configuration-cache
```

The first two tasks validate HTTPS and generated connection files. `awsIdentity` requires AWS CLI v2 and makes a signed STS request with the generated emulator credentials. It passes the execution-time endpoint explicitly so older CLI versions that ignore `AWS_ENDPOINT_URL` also work. Boto3 and AWS SDK for JavaScript v3 applications can use the same connection environment with SDK versions that support external endpoints. `Exec` does not inject JVM options; use `JavaExec` for JVM applications that need the combined trust store.

Use `-PpythonExecutable=python`, `-PnodeExecutable=/path/to/node`, or `-PawsExecutable=/path/to/aws` to select installed executables. Add `-PfailExample=true` to a probe to verify failure cleanup. Logs survive in `.aws-emulator/logs/`; generated credentials and managed runtime homes are removed.

For a shared development session:

```sh
./gradlew -p examples/processes pythonProbe nodeProbe -Pdevelopment=true
./gradlew -p examples/processes exportAwsEmulatorConnection --configuration-cache
. examples/processes/.aws-emulator/development/connection.sh
python3 examples/processes/probe.py
node examples/processes/probe.mjs
aws --endpoint-url "$AWS_ENDPOINT_URL" sts get-caller-identity --no-cli-pager
```

In PowerShell:

```powershell
./gradlew -p examples/processes exportAwsEmulatorConnection
. ./examples/processes/.aws-emulator/development/connection.ps1
python ./examples/processes/probe.py
node ./examples/processes/probe.mjs
aws --endpoint-url "$env:AWS_ENDPOINT_URL" sts get-caller-identity --no-cli-pager
```

Source the script in a dedicated development shell; it removes conflicting AWS credentials, profiles, and service endpoints from that shell. Start your IDE or application from this shell so it inherits the settings, or copy the exported environment values into its run configuration and remove the same conflicting overrides. An already running IDE may need a fresh process. Scripts contain paths, never credential values; do not copy managed connection paths into an IDE because their lifetime ends with Gradle. Regenerate and source the scripts after restart, then relaunch the application. Close the shell when finished to discard the environment.

```sh
./gradlew -p examples/processes doctorAwsEmulator
./gradlew -p examples/processes cleanupAwsEmulator --report-only
./gradlew -p examples/processes cleanupAwsEmulator
./gradlew -p examples/processes stopAwsEmulator
```

Cleanup skips active managed builds and all development containers. The last command stops development and removes its connection scripts and generated credentials. Archived logs remain until you delete them.
