import https from 'https';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 1. Simple .env parser to avoid dependencies
const loadEnv = () => {
    const envPath = path.join(__dirname, '.env');
    if (fs.existsSync(envPath)) {
        const envContent = fs.readFileSync(envPath, 'utf8');
        envContent.split('\n').forEach(line => {
            const match = line.match(/^\s*([\w\.\-]+)\s*=\s*(.*)?\s*$/);
            if (match !== null) {
                const key = match[1];
                let value = match[2] || '';
                if (value.length > 0 && value.charAt(0) === '"' && value.charAt(value.length - 1) === '"') {
                    value = value.replace(/\\n/gm, '\n');
                }
                value = value.replace(/(^['"]|['"]$)/g, '').trim();
                process.env[key] = value;
            }
        });
    }
};

loadEnv();

const API_TOKEN = process.env.API_TOKEN;
const TRACCAR_OSMAND_URL = process.env.TRACCAR_OSMAND_URL;

if (!API_TOKEN || !TRACCAR_OSMAND_URL) {
    console.error("Missing API_TOKEN or TRACCAR_OSMAND_URL in .env");
    process.exit(1);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

const makeRequest = (hostname, port, apiPath, method, headers = {}, isHttps = true) => new Promise(resolve => {
    const options = { hostname, port, path: apiPath, method, headers };
    const client = isHttps ? https : http;
    const req = client.request(options, res => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
            try {
                if (data) resolve({ status: res.statusCode, data: JSON.parse(data) });
                else resolve({ status: res.statusCode, data: null });
            } catch (e) {
                resolve({ status: res.statusCode, data });
            }
        });
    });
    req.on('error', e => resolve({ err: e.message }));
    req.end();
});

const getTimestamp = () => Math.floor(Date.now() / 1000).toString();

const fetchDevices = async () => {
    console.log("Fetching all devices...");
    const ts = getTimestamp();
    const headers = { 'api_token': API_TOKEN, 'Content-Type': 'application/json', 'timestamp': ts };

    const res = await makeRequest('tags.traqcare.com', 443, '/api/tag/all', 'GET', headers, true);

    if (res.status === 200 && res.data && res.data.data) {
        console.log(`Device List:`, JSON.stringify(res.data.data));
        return res.data.data;
    } else {
        console.error("Failed to fetch devices:", res);
        return [];
    }
};

const fetchLocationsForBatch = async (batch) => {
    const ts = getTimestamp();
    const timeTo = parseInt(ts, 10);
    const timeFrom = timeTo - (24 * 60 * 60); // 24 hours ago

    const ids = batch.join(',');
    console.log(`Fetching locations for batch of ${batch.length} devices...`);

    const headers = { 'api_token': API_TOKEN, 'Content-Type': 'application/json', 'timestamp': ts };
    const apiPath = `/api/tag?ids=${ids}&timeFrom=${timeFrom}&timeTo=${timeTo}`;

    const res = await makeRequest('tags.traqcare.com', 443, apiPath, 'GET', headers, true);

    if (res.status === 200 && res.data && Array.isArray(res.data.data)) {
        console.log(`Locations response for batch [${ids}]:`, JSON.stringify(res.data.data, null, 2));
        return res.data.data;
    } else {
        console.error(`Failed to fetch locations for batch (Status: ${res.status}):`, res.data);
        return [];
    }
};

const forwardToTraccar = async (locationList) => {
    const url = new URL(TRACCAR_OSMAND_URL);
    const hostname = url.hostname;
    const port = url.port || (url.protocol === 'https:' ? 443 : 80);
    const isHttps = url.protocol === 'https:';

    let sentCount = 0;

    for (const loc of locationList) {
        if (!loc.lat || !loc.lng || !loc.timestamp) continue;

        // Format OsmAnd GET request as requested
        const query = new URLSearchParams({
            id: loc.id.toString(),
            lat: loc.lat.toString(),
            lon: loc.lng.toString(),
            timestamp: (loc.timestamp * 1000).toString(), // ms
            battery: loc.battery.toString(),
            mac: loc.mac || '',
            isActived: loc.isActived ? 'true' : 'false'
        });

        const traccarPath = (url.pathname !== '/' ? url.pathname : "") + `/?${query.toString()}`;

        const res = await makeRequest(hostname, port, traccarPath, 'GET', {}, isHttps);
        if (res.status === 200) {
            console.log(`Traccar OK for ID ${loc.id}:`, res.data || 'No Content');
            sentCount++;
        } else {
            console.error(`Failed to send to Traccar for ${loc.id} (Status: ${res.status}):`, res.err || res.data);
        }
    }

    return sentCount;
};

const runJob = async () => {
    try {
        console.log(`\n--- Starting AirTag Fetch Job at ${new Date().toISOString()} ---`);
        const devices = await fetchDevices();
        console.log(`Found ${devices.length} devices.`);

        if (devices.length === 0) return;

        const batchSize = 1; // updated to one airtag per request
        let totalForwarded = 0;

        for (let i = 0; i < devices.length; i += batchSize) {
            const batch = devices.slice(i, i + batchSize);
            const locations = await fetchLocationsForBatch(batch);

            console.log(`Received ${locations.length} locations for batch.`);

            if (locations.length > 0) {
                const sent = await forwardToTraccar(locations);
                totalForwarded += sent;
                console.log(`Forwarded ${sent} locations to Traccar.`);
            }

            // Sleep slightly to avoid rate limit (100 per min == ~600ms per req max)
            await sleep(1000);
        }

        console.log(`Job complete. Total valid locations forwarded: ${totalForwarded}`);
    } catch (e) {
        console.error("Job error:", e);
    }
};

// Run immediately, then every 5 minutes
runJob();
setInterval(runJob, 5 * 60 * 1000);
