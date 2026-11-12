// Node loads NODE_EXTRA_CA_CERTS at startup. Certificate and hostname checks stay enabled.
import https from "node:https";
import { access } from "node:fs/promises";

await access(process.env.AWS_SHARED_CREDENTIALS_FILE);
await access(process.env.AWS_CONFIG_FILE);
const endpoint = process.env.AWS_ENDPOINT_URL;
await new Promise((resolve, reject) => {
    const request = https.get(`${endpoint}/__admin/health`, response => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", chunk => { body += chunk; });
        response.on("error", reject);
        response.on("end", () => {
            if (response.statusCode === 200 && body.trim() === "ok") resolve();
            else reject(new Error(`Unexpected health response: ${response.statusCode} ${body}`));
        });
    });
    request.setTimeout(10000, () => request.destroy(new Error("Health request timed out")));
    request.on("error", reject);
});
console.log(`Node connected to ${endpoint}`);
if (process.argv[2] === "true") throw new Error("Intentional process failure");
