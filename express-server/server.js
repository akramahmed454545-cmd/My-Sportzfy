const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const child_process = require('child_process');

const app = express();
let PORT = process.env.PORT || 3000;
if (PORT === "8080" || PORT === 8080) {
    PORT = 3000;
}
const API_KEY = process.env.API_KEY || "sportzfy_secret_key";
const DB_FILE = path.join(__dirname, 'db.json');

app.use(cors());
app.use(express.json({ limit: '10mb' }));

// Analytics DB Initialization & Core Helpers
const ANALYTICS_FILE = path.join(__dirname, 'analytics.json');
const activeSessions = {}; // memory store for live active viewers

function initAnalyticsDb() {
    if (!fs.existsSync(ANALYTICS_FILE)) {
        const initial = {
            traffic: [],
            channelViews: [],
            streamViews: [],
            appDownloads: [],
            appActiveUsers: [],
            adClicks: []
        };
        fs.writeFileSync(ANALYTICS_FILE, JSON.stringify(initial, null, 2), 'utf8');
        return initial;
    }
    try {
        const data = fs.readFileSync(ANALYTICS_FILE, 'utf8');
        return JSON.parse(data);
    } catch (e) {
        console.error("Error reading analytics DB, resetting:", e);
        const initial = {
            traffic: [],
            channelViews: [],
            streamViews: [],
            appDownloads: [],
            appActiveUsers: [],
            adClicks: []
        };
        fs.writeFileSync(ANALYTICS_FILE, JSON.stringify(initial, null, 2), 'utf8');
        return initial;
    }
}

function logAnalyticsEvent(category, event) {
    try {
        const db = initAnalyticsDb();
        if (!db[category]) db[category] = [];
        if (!event.timestamp) {
            event.timestamp = new Date().toISOString();
        }
        db[category].push(event);
        if (db[category].length > 2000) {
            db[category] = db[category].slice(-2000);
        }
        fs.writeFileSync(ANALYTICS_FILE, JSON.stringify(db, null, 2), 'utf8');
    } catch (e) {
        console.error("Error logging analytics event:", e);
    }
}

// Middleware to track website traffic (before serving static files)
app.use((req, res, next) => {
    const urlPath = req.path;
    const isStaticAsset = urlPath.match(/\.(js|css|png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|otf|mpd|m3u8|ts|mp4)$/i);
    const isApi = urlPath.startsWith('/api/');
    const isObs = urlPath.startsWith('/obs/');
    
    if (!isStaticAsset && !isApi && !isObs && urlPath !== '/favicon.ico') {
        const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || "127.0.0.1";
        const clientIp = ip.split(',')[0].trim();
        
        logAnalyticsEvent('traffic', {
            ip: clientIp,
            ua: req.headers['user-agent'] || "Unknown",
            path: urlPath,
            timestamp: new Date().toISOString()
        });
    }
    next();
});

const activeAdminSessions = new Map(); // token -> expiry

function parseCookies(cookieHeader) {
    const list = {};
    if (!cookieHeader) return list;
    cookieHeader.split(';').forEach(cookie => {
        const parts = cookie.split('=');
        list[parts.shift().trim()] = decodeURI(parts.join('='));
    });
    return list;
}

function isSessionValid(req) {
    // 1. Check Authorization header
    const authHeader = req.headers.authorization;
    if (authHeader) {
        const token = authHeader.startsWith('Bearer ') ? authHeader.substring(7).trim() : authHeader.trim();
        const expiry = activeAdminSessions.get(token);
        if (expiry && Date.now() < expiry) {
            activeAdminSessions.set(token, Date.now() + 86400000); // extend 24h
            return true;
        } else if (expiry) {
            activeAdminSessions.delete(token);
        }
    }

    // 2. Check Cookie
    const cookies = parseCookies(req.headers.cookie);
    const token = cookies.admin_session;
    if (token) {
        const expiry = activeAdminSessions.get(token);
        if (expiry && Date.now() < expiry) {
            activeAdminSessions.set(token, Date.now() + 86400000); // extend 24h
            return true;
        } else if (expiry) {
            activeAdminSessions.delete(token);
        }
    }
    return false;
}

// Global Security Headers (Clickjacking, XSS, MIME Sniffing, Referrer protections)
app.use((req, res, next) => {
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-XSS-Protection', '1; mode=block');
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    next();
});

// Lightweight In-Memory Rate Limiter and Scraper Prevention
const ipRequestCounts = {};
setInterval(() => {
    // Clear rate limits every 60 seconds
    for (const ip in ipRequestCounts) {
        ipRequestCounts[ip].count = 0;
    }
}, 60000);

app.use((req, res, next) => {
    const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
    const urlPath = req.path;

    if (!ipRequestCounts[ip]) {
        ipRequestCounts[ip] = { count: 0, blockedUntil: 0 };
    }

    if (Date.now() < ipRequestCounts[ip].blockedUntil) {
        return res.status(429).json({
            error: "Too Many Requests",
            message: "Our security shields detected suspicious scraping activity from your IP. Access blocked temporarily."
        });
    }

    ipRequestCounts[ip].count++;

    // Limit to 120 API requests per minute (very safe but blocks heavy scraping/sniffing)
    if (ipRequestCounts[ip].count > 120) {
        ipRequestCounts[ip].blockedUntil = Date.now() + 10 * 60 * 1000; // Block for 10 minutes
        console.warn(`[SECURITY DETECTED] Rate limit exceeded by IP: ${ip}. Blocked for 10 mins.`);
        return res.status(429).json({
            error: "Too Many Requests",
            message: "Suspicious automated activity detected. Your IP has been temporarily blocked for security reasons."
        });
    }

    // Anti-Scraping Shield: check custom signatures for APIs
    if (urlPath.startsWith('/api/') && urlPath !== '/api/login' && urlPath !== '/api/logout') {
        const userAgent = req.headers['user-agent'] || '';
        const secureKey = req.headers['x-sportzfy-sec-key'];
        
        // Allowed client patterns:
        // 1. Android App client
        const isAndroidApp = userAgent.includes('SportzfySecureClient') || secureKey === 'sportzfy_bulletproof_android_sec_2026';
        // 2. Web App / Admin browser client (must have referer containing /web-app or admin console, and not a command line tool like curl, python, etc.)
        const isWebOrAdmin = req.headers['referer'] && 
                             (req.headers['referer'].includes('/web-app') || req.headers['referer'].includes('/login.html') || req.headers['referer'].includes('/index.html') || req.headers['referer'].includes(req.headers['host'])) &&
                             !userAgent.includes('curl') && 
                             !userAgent.includes('Wget') && 
                             !userAgent.includes('python-requests') && 
                             !userAgent.includes('Postman');

        if (!isAndroidApp && !isWebOrAdmin) {
            console.warn(`[SECURITY DETECTED] Scraper/Sniffer blocked. IP: ${ip}, User-Agent: ${userAgent}`);
            return res.status(403).json({
                error: "Forbidden",
                message: "Direct API scraping or headless data collection is blocked by Sportzfy Shields."
            });
        }
    }

    next();
});

// Authentication Check Middleware for web pages and write APIs
app.use((req, res, next) => {
    const urlPath = req.path;

    // 1. Exclude public static routes
    if (urlPath === '/web-app' || urlPath.startsWith('/web-app/')) {
        return next();
    }
    if (urlPath === '/apk/sportzfy-latest.apk') {
        return next();
    }
    if (urlPath.startsWith('/obs/')) {
        return next();
    }
    if (urlPath === '/login.html') {
        return next();
    }

    // 2. Exclude public API routes
    if (urlPath === '/api/login' || urlPath === '/api/logout') {
        return next();
    }

    // 3. Exclude API Key protected routes (handled by checkApiKey middleware later)
    if (urlPath.startsWith('/api/secure/')) {
        return next();
    }

    // 4. Exclude public read-only (GET) APIs
    const isPublicGet = req.method === 'GET' && (
        urlPath === '/api/status' ||
        urlPath === '/api/matches' ||
        urlPath === '/api/channels' ||
        urlPath === '/api/highlights' ||
        urlPath === '/api/notices' ||
        urlPath === '/api/banner-ad' ||
        urlPath === '/api/ads-settings' ||
        urlPath === '/api/app-update' ||
        urlPath === '/api/maintenance' ||
        urlPath === '/api/obs-status' ||
        urlPath === '/api/analytics/track-ad-click'
    );
    if (isPublicGet) {
        return next();
    }

    // 5. Exclude public write (POST) APIs
    const isPublicPost = req.method === 'POST' && (
        urlPath === '/api/analytics/heartbeat' ||
        urlPath === '/api/analytics/track-view' ||
        urlPath === '/api/stream-report' ||
        urlPath === '/api/send-fcm-alert'
    );
    if (isPublicPost) {
        return next();
    }

    // 6. Handle Administrative Pages (root, index.html)
    const isHtmlPage = urlPath === '/' || urlPath === '/index.html';
    if (isHtmlPage) {
        if (!isSessionValid(req)) {
            return res.sendFile(path.join(__dirname, 'public', 'login.html'));
        }
        return next();
    }

    // 7. For all other /api/* requests (Admin actions), require valid session
    if (urlPath.startsWith('/api/')) {
        if (!isSessionValid(req)) {
            return res.status(401).json({ error: "Unauthorized: Please login" });
        }
        return next();
    }

    // 8. For any other static assets (like favicon, css, js) requested:
    // We let them through to avoid breaking asset loads, but block raw HTML pages if not logged in.
    if (!isSessionValid(req) && (urlPath.endsWith('.html') || urlPath === '/')) {
        return res.sendFile(path.join(__dirname, 'public', 'login.html'));
    }

    next();
});

app.use(express.static(path.join(__dirname, 'public')));

// Secure API Key Middleware for /api/secure/*
function checkApiKey(req, res, next) {
    const key = req.headers['x-api-key'] || 
                (req.headers['authorization'] && req.headers['authorization'].split(' ')[1]) || 
                req.query.key;

    if (!key || key !== API_KEY) {
        return res.status(401).json({ error: "Unauthorized: Invalid or missing API Key" });
    }

    // Record Android App sync activity
    try {
        const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || "127.0.0.1";
        const clientIp = ip.split(',')[0].trim();
        const ua = req.headers['user-agent'] || "Unknown";
        const keySession = clientIp + "_" + ua.substring(0, 50);
        const versionCode = req.headers['x-app-version'] || req.query.version || "1.0";
        
        activeSessions[keySession] = {
            ip: clientIp,
            ua: "Android App (v" + versionCode + ")",
            source: "android-app",
            page: req.path || "/api/secure/data",
            watching: null,
            lastSeen: Date.now()
        };
        
        logAnalyticsEvent('appActiveUsers', {
            ip: clientIp,
            versionCode: versionCode,
            ua: ua,
            timestamp: new Date().toISOString()
        });
    } catch (e) {
        console.error("Error logging secure app activity:", e);
    }

    next();
}

// M3U Playlist Parser for seeding
function loadM3uChannels() {
    try {
        const m3uPath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'playlist.m3u');
        if (!fs.existsSync(m3uPath)) {
            console.log("M3U playlist not found at:", m3uPath);
            return [];
        }
        const m3uContent = fs.readFileSync(m3uPath, 'utf8');
        const lines = m3uContent.split(/\r?\n/);
        const channels = [];
        let currentMetadata = null;
        let idCounter = 1;

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            if (!line) continue;

            if (line.startsWith("#EXTINF:")) {
                currentMetadata = line;
            } else if (line.startsWith("#EXTM3U") || line.startsWith("#")) {
                continue;
            } else if (line.startsWith("http://") || line.startsWith("https://") || line.startsWith("rtmp://") || line.startsWith("rtsp://")) {
                if (currentMetadata) {
                    let name = "Unnamed Channel";
                    let logoUrl = "";
                    let category = "Local TV";

                    const commaIdx = currentMetadata.lastIndexOf(',');
                    if (commaIdx !== -1) {
                        name = currentMetadata.substring(commaIdx + 1).trim();
                    }

                    const logoMatch = currentMetadata.match(/tvg-logo="([^"]*)"/i);
                    if (logoMatch) logoUrl = logoMatch[1];

                    const groupMatch = currentMetadata.match(/group-title="([^"]*)"/i);
                    let parsedGroup = groupMatch ? groupMatch[1].trim() : "";

                    name = name.replace(/^["']|["']$/g, "").trim();
                    if (!name) name = `Channel ${idCounter}`;

                    // Determine Category
                    const lower = name.toLowerCase();
                    if (parsedGroup && parsedGroup !== "General") {
                        category = parsedGroup;
                    } else if (lower.includes("sports") || lower.includes("sport") || lower.includes("cup") || 
                               lower.includes("cricket") || lower.includes("ten") || lower.includes("willow") || 
                               lower.includes("bein") || lower.includes("golf") || lower.includes("espn") || 
                               lower.includes("tsn") || lower.includes("football") || lower.includes("nfl") || 
                               lower.includes("wtc") || lower.includes("epl") || lower.includes("bfl") ||
                               lower.includes("gp") || lower.includes("finals")) {
                        category = "Sports";
                    } else if (lower.includes("news") || lower.includes("khabar") || lower.includes("bbc") || 
                               lower.includes("jazeera") || lower.includes("aaj tak") || lower.includes("cnbc") || 
                               lower.includes("msnbc") || lower.includes("abc") || lower.includes("dw") || 
                               lower.includes("wion") || lower.includes("independent") || lower.includes("somoy") || 
                               lower.includes("dbc") || lower.includes("news18") || lower.includes("republic") || 
                               lower.includes("times now") || lower.includes("kolkata tv") || lower.includes("zee 24") ||
                               lower.includes("cbs") || lower.includes("cp 24") || lower.includes("business") ||
                               lower.includes("weather") || lower.includes("accuweather")) {
                        category = "News";
                    } else if (lower.includes("kids") || lower.includes("cartoon") || lower.includes("nick") || 
                               lower.includes("pogo") || lower.includes("disney") || lower.includes("toon") || 
                               lower.includes("goggles") || lower.includes("moonbug")) {
                        category = "Kids";
                    } else if (lower.includes("quran") || lower.includes("islamic") || lower.includes("deen") || 
                               lower.includes("eman") || lower.includes("madani")) {
                        category = "Islamic";
                    } else if (lower.includes("movie") || lower.includes("cinema") || lower.includes("max") || 
                               lower.includes("goldmines") || lower.includes("epix") || lower.includes("hbo") || 
                               lower.includes("action") || lower.includes("bollywood") || lower.includes("sphere") ||
                               lower.includes("amc") || lower.includes("pix")) {
                        category = "Movies";
                    } else if (lower.includes("vip")) {
                        category = "VIP";
                    }

                    channels.push({
                        id: idCounter++,
                        name: name,
                        category: category,
                        logoUrl: logoUrl,
                        streamUrl: line
                    });

                    currentMetadata = null;
                } else {
                    channels.push({
                        id: idCounter++,
                        name: `Channel ${idCounter}`,
                        category: "Local TV",
                        logoUrl: "",
                        streamUrl: line
                    });
                }
            }
        }
        console.log(`Parsed ${channels.length} channels successfully from assets M3U playlist!`);
        return channels;
    } catch (e) {
        console.error("Error loading M3U channels in Express:", e);
        return [];
    }
}

// Read database helper
function readDb() {
    try {
        if (!fs.existsSync(DB_FILE)) {
            return seedDb();
        }
        const data = fs.readFileSync(DB_FILE, 'utf8');
        const parsed = JSON.parse(data);
        
        // Force re-seed from M3U assets if we have practically no channels
        if (!parsed.channels || parsed.channels.length <= 2) {
            console.log("Database channels empty or default. Re-seeding from assets...");
            return seedDb();
        }
        
        if (!parsed.streamHealth) {
            parsed.streamHealth = [];
        }
        
        if (!parsed.maintenance) {
            parsed.maintenance = {
                enabled: false,
                message: "We are currently performing scheduled server maintenance. We'll be back shortly!"
            };
        }
        
        return parsed;
    } catch (e) {
        console.error("Error reading DB, re-initializing:", e);
        return seedDb();
    }
}

// Write database helper
function writeDb(data) {
    try {
        fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2), 'utf8');
    } catch (e) {
        console.error("Error writing to DB:", e);
    }
}

// Seed Initial Database
function seedDb() {
    const assetChannels = loadM3uChannels();
    const defaultChannels = assetChannels.length > 0 ? assetChannels : [
        {
            id: 1,
            name: "Sky Sports Main Event",
            category: "Sports",
            logoUrl: "https://upload.wikimedia.org/wikipedia/commons/e/e0/Sky_Sports_Main_Event_logo.svg",
            streamUrl: "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
        },
        {
            id: 2,
            name: "BBC News HD",
            category: "News",
            logoUrl: "https://upload.wikimedia.org/wikipedia/commons/6/62/BBC_News_2019.svg",
            streamUrl: "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
        }
    ];

    const defaultDb = {
        matches: [
            {
                id: 1,
                title: "MotorSports || MotoGP",
                sport: "MotorSports",
                team1Name: "MotoGP",
                team1Logo: "motogp",
                team2Name: "MotoGP",
                team2Logo: "motogp",
                time: "05:24:23",
                status: "Live",
                streamUrl: "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            },
            {
                id: 2,
                title: "Cricket || ICC Women World Twenty20",
                sport: "Cricket",
                team1Name: "PAK-W",
                team1Logo: "pakistan",
                team2Name: "NED-W",
                team2Logo: "netherlands",
                time: "03:54:23",
                status: "Live",
                streamUrl: "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            }
        ],
        channels: defaultChannels,
        highlights: [
            {
                id: 1,
                title: "Football | FIFA World Cup Highlight",
                team1Name: "Mexico",
                team1Logo: "mexico",
                team2Name: "South Africa",
                team2Logo: "southafrica",
                date: "11/06/2026",
                streamUrl: "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            }
        ],
        notices: [
            {
                id: 1,
                content: "(Ads Free). Keep supporting us for the best sports streaming experience!",
                active: true
            }
        ],
        bannerAd: {
            id: 1,
            mediaType: "gif",
            mediaUrl: "https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExM2Z5eWZ2OHdpeGtxNDlhMGlxMTI4eTVoZHkyeXB5dTRrZHNkczBybyZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/3o6vY6b2M3N66b8V5S/giphy.gif",
            clickUrl: "https://sportzfy.com",
            enabled: true
        },
        appUpdate: {
            versionCode: 1,
            versionName: "1.0.0",
            changelog: "Initial release settings synced.",
            apkUrl: "",
            isMandatory: false
        },
        maintenance: {
            enabled: false,
            message: "We are currently performing scheduled server maintenance. We'll be back shortly!"
        }
    };
    writeDb(defaultDb);
    return defaultDb;
}

// ==========================================
// UNSECURED / PUBLIC APIS (Admin Console / Web App)
// ==========================================

// Authentication Routes
app.post('/api/login', (req, res) => {
    const { username, password } = req.body;
    if (username === 'shafin.sportzfy' && password === 'Abcd#43214') {
        const crypto = require('crypto');
        const token = crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).substring(2) + Date.now().toString(36);
        activeAdminSessions.set(token, Date.now() + 86400000); // 24 hours
        res.cookie('admin_session', token, {
            path: '/',
            httpOnly: true,
            sameSite: 'strict',
            maxAge: 86400000
        });
        return res.json({ success: true, token });
    } else {
        // Anti brute force delay
        setTimeout(() => {
            return res.status(401).json({ success: false, error: "Invalid username or password" });
        }, 1500);
    }
});

app.post('/api/logout', (req, res) => {
    const cookies = parseCookies(req.headers.cookie);
    const token = cookies.admin_session;
    if (token) {
        activeAdminSessions.delete(token);
    }
    res.clearCookie('admin_session', { path: '/' });
    return res.json({ success: true });
});

// Server status
app.get('/api/status', (req, res) => {
    res.json({
        status: "online",
        name: "Sportzfy Live Unified Admin API",
        time: new Date().toISOString()
    });
});

// Matches API
app.get('/api/matches', (req, res) => {
    const db = readDb();
    res.json(db.matches || []);
});

app.post('/api/matches', (req, res) => {
    const db = readDb();
    const id = req.body.id ? parseInt(req.body.id) : (db.matches.length > 0 ? Math.max(...db.matches.map(m => m.id)) + 1 : 1);
    const updatedMatch = {
        id,
        title: req.body.title || "Unnamed Match",
        sport: req.body.sport || "All",
        team1Name: req.body.team1Name || "",
        team1Logo: req.body.team1Logo || "",
        team2Name: req.body.team2Name || "",
        team2Logo: req.body.team2Logo || "",
        time: req.body.time || "LIVE",
        status: req.body.status || "Upcoming",
        streamUrl: req.body.streamUrl || ""
    };
    
    const index = db.matches.findIndex(m => m.id === id);
    if (index !== -1) {
        db.matches[index] = updatedMatch;
    } else {
        db.matches.push(updatedMatch);
    }
    writeDb(db);
    res.json({ success: true, match: updatedMatch });
});

app.post('/api/send-fcm-alert', (req, res) => {
    const { matchId, title, sport, status, body } = req.body;
    console.log(`[FCM SIMULATION] Sending live match push notification. Match ID: ${matchId}, Title: ${title}, Sport: ${sport}, Status: ${status}`);
    res.json({
        success: true,
        message: "FCM notification broadcast simulated successfully!",
        payload: {
            topic: `match_${matchId}`,
            data: { matchId, title, sport, status, body: body || "The match is live now! Tap to watch." }
        }
    });
});

app.delete('/api/matches', (req, res) => {
    const db = readDb();
    const id = parseInt(req.query.id);
    db.matches = db.matches.filter(m => m.id !== id);
    writeDb(db);
    res.json({ success: true });
});

// Channels API
app.get('/api/channels', (req, res) => {
    const db = readDb();
    res.json(db.channels || []);
});

app.post('/api/channels', (req, res) => {
    const db = readDb();
    const id = req.body.id ? parseInt(req.body.id) : (db.channels.length > 0 ? Math.max(...db.channels.map(c => c.id)) + 1 : 1);
    const updatedChannel = {
        id,
        name: req.body.name || "Unnamed Channel",
        category: req.body.category || "VIP",
        logoUrl: req.body.logoUrl || "",
        streamUrl: req.body.streamUrl || "",
        streamUrl2: req.body.streamUrl2 || "",
        streamUrl3: req.body.streamUrl3 || "",
        streamUrl4: req.body.streamUrl4 || "",
        streamUrl5: req.body.streamUrl5 || ""
    };
    
    const index = db.channels.findIndex(c => c.id === id);
    if (index !== -1) {
        db.channels[index] = updatedChannel;
    } else {
        db.channels.push(updatedChannel);
    }
    writeDb(db);
    res.json({ success: true, channel: updatedChannel });
});

app.delete('/api/channels', (req, res) => {
    const db = readDb();
    const id = parseInt(req.query.id);
    db.channels = db.channels.filter(c => c.id !== id);
    writeDb(db);
    res.json({ success: true });
});

// Bulk M3U Importer
app.post('/api/channels/import-m3u', async (req, res) => {
    try {
        const m3uUrl = req.body.url ? req.body.url.trim() : "";
        const rawTextBase64 = req.body.rawTextBase64 ? req.body.rawTextBase64.trim() : "";
        const defaultCategory = req.body.defaultCategory || "IPTV";
        
        let m3uContent = "";
        if (rawTextBase64) {
            m3uContent = Buffer.from(rawTextBase64, 'base64').toString('utf8');
        } else if (m3uUrl) {
            try {
                const response = await fetch(m3uUrl);
                m3uContent = await response.text();
            } catch (fetchErr) {
                return res.status(400).json({ error: "Failed to download M3U URL: " + fetchErr.message });
            }
        } else {
            return res.status(400).json({ error: "Missing M3U URL or Raw Text" });
        }
        
        const lines = m3uContent.split(/\r?\n/);
        const db = readDb();
        
        // Helper function for name cleaning
        const cleanChanName = (name) => {
            if (!name) return "";
            let clean = name.trim();
            // Remove emojis and flags
            clean = clean.replace(/[\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD00-\uDFFF]/g, '');
            // Remove trailing country suffix
            clean = clean.replace(/\s+(Argentina|France|Germany|Itali|Italy|Brazil|Spain|USA|Bangladesh|Algeria|Mexico|Serbia|USA TV|India|BD)\b/gi, '');
            // Strip parenthesized things
            clean = clean.replace(/\s*\(.*?\)/gi, '');
            clean = clean.replace(/\s*\[.*?\]/gi, '');
            // Strip trailing backup indicator letters
            clean = clean.replace(/\s+-\s+$/gi, '');
            clean = clean.replace(/\s+([B-E])\b(\s+1080|\s+HD|\s+-)*$/gi, '');
            clean = clean.replace(/\s+-\s+([B-E])\b.*?$/gi, '');
            clean = clean.replace(/\s+(HD|VIP|TV|Live|SD)\b/gi, '');
            clean = clean.replace(/[\s\-_]+$/g, '');
            clean = clean.replace(/\s+/g, ' ').trim();
            return clean;
        };

        const getCategory = (name, parsedGroup) => {
            if (parsedGroup && parsedGroup !== "General") return parsedGroup;
            const lower = name.toLowerCase();
            if (lower.includes("sports") || lower.includes("sport") || lower.includes("cup") || 
                lower.includes("cricket") || lower.includes("ten") || lower.includes("willow") || 
                lower.includes("bein") || lower.includes("golf") || lower.includes("espn") || 
                lower.includes("tsn") || lower.includes("football") || lower.includes("nfl") || 
                lower.includes("wtc") || lower.includes("epl") || lower.includes("bfl") ||
                lower.includes("gp") || lower.includes("finals")) {
                return "Sports";
            } else if (lower.includes("news") || lower.includes("khabar") || lower.includes("bbc") || 
                       lower.includes("jazeera") || lower.includes("aaj tak") || lower.includes("cnbc") || 
                       lower.includes("msnbc") || lower.includes("abc") || lower.includes("dw") || 
                       lower.includes("wion") || lower.includes("independent") || lower.includes("somoy") || 
                       lower.includes("dbc") || lower.includes("news18") || lower.includes("republic") || 
                       lower.includes("times now") || lower.includes("kolkata tv") || lower.includes("zee 24") ||
                       lower.includes("cbs") || lower.includes("cp 24") || lower.includes("business") ||
                       lower.includes("weather") || lower.includes("accuweather")) {
                return "News";
            } else if (lower.includes("kids") || lower.includes("cartoon") || lower.includes("nick") || 
                       lower.includes("pogo") || lower.includes("disney") || lower.includes("toon") || 
                       lower.includes("goggles") || lower.includes("moonbug")) {
                return "Kids";
            } else if (lower.includes("quran") || lower.includes("islamic") || lower.includes("deen") || 
                       lower.includes("eman") || lower.includes("madani")) {
                return "Islamic";
            } else if (lower.includes("movie") || lower.includes("cinema") || lower.includes("max") || 
                       lower.includes("goldmines") || lower.includes("epix") || lower.includes("hbo") || 
                       lower.includes("action") || lower.includes("bollywood") || lower.includes("sphere") ||
                       lower.includes("amc") || lower.includes("pix")) {
                return "Movies";
            } else if (lower.includes("vip")) {
                return "VIP";
            }
            return defaultCategory;
        };

        // Track already imported URLs in db and this session
        const importedUrls = new Set();
        db.channels.forEach(c => {
            if (c.streamUrl) importedUrls.add(c.streamUrl);
            if (c.streamUrl2) importedUrls.add(c.streamUrl2);
            if (c.streamUrl3) importedUrls.add(c.streamUrl3);
            if (c.streamUrl4) importedUrls.add(c.streamUrl4);
            if (c.streamUrl5) importedUrls.add(c.streamUrl5);
        });

        // Index existing channels by category + cleanName to merge backups
        const channelsMap = new Map();
        db.channels.forEach(c => {
            const baseKey = `${c.category.toLowerCase()}:${c.name.toLowerCase()}`;
            channelsMap.set(baseKey, c);
        });

        let nextId = db.channels.length > 0 ? Math.max(...db.channels.map(c => c.id)) + 1 : 1;
        let importedCount = 0;
        let currentMetadata = null;

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            if (!line) continue;
            
            if (line.startsWith("#EXTINF:")) {
                currentMetadata = line;
            } else if (line.startsWith("#EXTM3U") || line.startsWith("#")) {
                continue;
            } else if (line.startsWith("http://") || line.startsWith("https://") || line.startsWith("rtmp://") || line.startsWith("rtsp://")) {
                let originalName = `Channel ${nextId}`;
                let logoUrl = "";
                let parsedGroup = "";
                
                if (currentMetadata) {
                    const commaIdx = currentMetadata.lastIndexOf(',');
                    if (commaIdx !== -1) {
                        originalName = currentMetadata.substring(commaIdx + 1).trim();
                    }
                    
                    const logoMatch = currentMetadata.match(/tvg-logo="([^"]*)"/i);
                    if (logoMatch) logoUrl = logoMatch[1];
                    
                    const groupMatch = currentMetadata.match(/group-title="([^"]*)"/i);
                    parsedGroup = groupMatch ? groupMatch[1].trim() : "";
                    
                    originalName = originalName.replace(/^["']|["']$/g, "").trim();
                    if (!originalName) originalName = `Channel ${nextId}`;
                }

                const streamUrl = line;
                currentMetadata = null;

                // Deduplicate URLs
                if (importedUrls.has(streamUrl)) {
                    continue;
                }
                importedUrls.add(streamUrl);

                const category = getCategory(originalName, parsedGroup);
                const baseName = cleanChanName(originalName) || originalName;
                const baseKey = `${category.toLowerCase()}:${baseName.toLowerCase()}`;

                if (channelsMap.has(baseKey)) {
                    // Update backup stream fields on existing channel
                    const ch = channelsMap.get(baseKey);
                    if (!ch.streamUrl2) ch.streamUrl2 = streamUrl;
                    else if (!ch.streamUrl3) ch.streamUrl3 = streamUrl;
                    else if (!ch.streamUrl4) ch.streamUrl4 = streamUrl;
                    else if (!ch.streamUrl5) ch.streamUrl5 = streamUrl;
                } else {
                    // Add new channel
                    const newChan = {
                        id: nextId++,
                        name: baseName,
                        category,
                        logoUrl,
                        streamUrl,
                        streamUrl2: "",
                        streamUrl3: "",
                        streamUrl4: "",
                        streamUrl5: ""
                    };
                    channelsMap.set(baseKey, newChan);
                    db.channels.push(newChan);
                }
                importedCount++;
            }
        }
        
        writeDb(db);
        res.json({ success: true, count: importedCount, message: `Successfully imported and merged ${importedCount} streams.` });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

// Clear channels
app.post('/api/channels/clear', (req, res) => {
    const db = readDb();
    db.channels = [];
    writeDb(db);
    res.json({ success: true, message: "All channels cleared successfully!" });
});

// Highlights API
app.get('/api/highlights', (req, res) => {
    const db = readDb();
    res.json(db.highlights || []);
});

app.post('/api/highlights', (req, res) => {
    const db = readDb();
    const id = req.body.id ? parseInt(req.body.id) : (db.highlights.length > 0 ? Math.max(...db.highlights.map(h => h.id)) + 1 : 1);
    const updatedHighlight = {
        id,
        title: req.body.title || "",
        team1Name: req.body.team1Name || "",
        team1Logo: req.body.team1Logo || "",
        team2Name: req.body.team2Name || "",
        team2Logo: req.body.team2Logo || "",
        date: req.body.date || "",
        streamUrl: req.body.streamUrl || ""
    };
    
    const index = db.highlights.findIndex(h => h.id === id);
    if (index !== -1) {
        db.highlights[index] = updatedHighlight;
    } else {
        db.highlights.push(updatedHighlight);
    }
    writeDb(db);
    res.json({ success: true, highlight: updatedHighlight });
});

app.delete('/api/highlights', (req, res) => {
    const db = readDb();
    const id = parseInt(req.query.id);
    db.highlights = db.highlights.filter(h => h.id !== id);
    writeDb(db);
    res.json({ success: true });
});

// Notices API
app.get('/api/notices', (req, res) => {
    const db = readDb();
    res.json(db.notices || []);
});

app.post('/api/notices', (req, res) => {
    const db = readDb();
    const active = req.body.active !== false && req.body.active !== 'false';
    const content = req.body.content || "";
    
    // We only manage a single active notice in this view
    db.notices = [{ id: 1, content, active }];
    writeDb(db);
    res.json({ success: true, notice: db.notices[0] });
});

// Banner Ad API
app.get('/api/banner-ad', (req, res) => {
    const db = readDb();
    res.json(db.bannerAd || {
        id: 1,
        mediaType: "gif",
        mediaUrl: "",
        clickUrl: "",
        enabled: false
    });
});

app.post('/api/banner-ad', (req, res) => {
    const db = readDb();
    db.bannerAd = {
        id: 1,
        mediaType: req.body.mediaType || "image",
        mediaUrl: req.body.mediaUrl || "",
        clickUrl: req.body.clickUrl || "",
        enabled: req.body.enabled === true || req.body.enabled === 'true'
    };
    if (db.adsSettings) {
        db.adsSettings.bannerAd = db.bannerAd;
    }
    writeDb(db);
    res.json({ success: true, bannerAd: db.bannerAd });
});

// Unified Ads Settings API
app.get('/api/ads-settings', (req, res) => {
    const db = readDb();
    if (!db.adsSettings) {
        db.adsSettings = {
            enabled: true,
            targeting: "global",
            targetCountries: [],
            appOpenAd: {
                enabled: false,
                mediaType: "image",
                mediaUrl: "",
                clickUrl: "",
                minTimeBound: 5,
                allowSkip: true
            },
            streamPreRollAd: {
                enabled: false,
                mediaType: "image",
                mediaUrl: "",
                clickUrl: "",
                minTimeBound: 5,
                allowSkip: true
            },
            minimizePopupAd: {
                enabled: false,
                mediaType: "image",
                mediaUrl: "",
                clickUrl: ""
            },
            bannerAd: db.bannerAd || {
                enabled: false,
                mediaType: "image",
                mediaUrl: "",
                clickUrl: ""
            }
        };
        writeDb(db);
    }
    res.json(db.adsSettings);
});

app.post('/api/ads-settings', (req, res) => {
    const db = readDb();
    db.adsSettings = {
        enabled: req.body.enabled !== false && req.body.enabled !== 'false',
        targeting: req.body.targeting || "global",
        targetCountries: Array.isArray(req.body.targetCountries) 
            ? req.body.targetCountries 
            : (req.body.targetCountries || "").split(',').map(s => s.trim().toUpperCase()).filter(Boolean),
        appOpenAd: {
            enabled: req.body.appOpenAd?.enabled === true || req.body.appOpenAd?.enabled === 'true',
            mediaType: req.body.appOpenAd?.mediaType || "image",
            mediaUrl: req.body.appOpenAd?.mediaUrl || "",
            clickUrl: req.body.appOpenAd?.clickUrl || "",
            minTimeBound: parseInt(req.body.appOpenAd?.minTimeBound) || 5,
            allowSkip: req.body.appOpenAd?.allowSkip !== false && req.body.appOpenAd?.allowSkip !== 'false'
        },
        streamPreRollAd: {
            enabled: req.body.streamPreRollAd?.enabled === true || req.body.streamPreRollAd?.enabled === 'true',
            mediaType: req.body.streamPreRollAd?.mediaType || "image",
            mediaUrl: req.body.streamPreRollAd?.mediaUrl || "",
            clickUrl: req.body.streamPreRollAd?.clickUrl || "",
            minTimeBound: parseInt(req.body.streamPreRollAd?.minTimeBound) || 5,
            allowSkip: req.body.streamPreRollAd?.allowSkip !== false && req.body.streamPreRollAd?.allowSkip !== 'false'
        },
        minimizePopupAd: {
            enabled: req.body.minimizePopupAd?.enabled === true || req.body.minimizePopupAd?.enabled === 'true',
            mediaType: req.body.minimizePopupAd?.mediaType || "image",
            mediaUrl: req.body.minimizePopupAd?.mediaUrl || "",
            clickUrl: req.body.minimizePopupAd?.clickUrl || ""
        },
        bannerAd: {
            enabled: req.body.bannerAd?.enabled === true || req.body.bannerAd?.enabled === 'true',
            mediaType: req.body.bannerAd?.mediaType || "image",
            mediaUrl: req.body.bannerAd?.mediaUrl || "",
            clickUrl: req.body.bannerAd?.clickUrl || ""
        }
    };
    db.bannerAd = db.adsSettings.bannerAd;
    writeDb(db);
    res.json({ success: true, adsSettings: db.adsSettings });
});

// Upload Ad Media API (Static images, zip HTML5 bundles, video mp4 files)
app.post('/api/upload-ad-file', (req, res) => {
    const filename = req.headers['x-filename'] || `ad-${Date.now()}`;
    const cleanFilename = filename.replace(/[^a-zA-Z0-9._-]/g, '_');
    const uploadsDir = path.join(__dirname, 'public', 'uploads', 'ads');
    fs.mkdirSync(uploadsDir, { recursive: true });
    const filePath = path.join(uploadsDir, cleanFilename);
    
    const data = [];
    req.on('data', chunk => data.push(chunk));
    req.on('end', () => {
        try {
            fs.writeFileSync(filePath, Buffer.concat(data));
            
            // If it is a ZIP, extract it
            if (cleanFilename.toLowerCase().endsWith('.zip')) {
                const folderName = path.basename(cleanFilename, '.zip') + '_' + Date.now();
                const extractDir = path.join(uploadsDir, 'extracted', folderName);
                fs.mkdirSync(extractDir, { recursive: true });
                
                try {
                    // Execute unzip command
                    child_process.execSync(`/usr/bin/unzip -o "${filePath}" -d "${extractDir}"`);
                    
                    // Verify if index.html exists
                    const indexPath = path.join(extractDir, 'index.html');
                    if (fs.existsSync(indexPath)) {
                        res.json({
                            success: true,
                            url: `/uploads/ads/extracted/${folderName}/index.html`,
                            type: 'html5'
                        });
                    } else {
                        // Search for index.html recursively inside
                        const files = child_process.execSync(`find "${extractDir}" -name "index.html"`).toString().trim().split('\n');
                        if (files.length > 0 && files[0]) {
                            const relativeIndex = path.relative(path.join(uploadsDir, 'extracted'), files[0]);
                            res.json({
                                success: true,
                                url: `/uploads/ads/extracted/${relativeIndex}`,
                                type: 'html5'
                            });
                        } else {
                            res.json({
                                success: true,
                                url: `/uploads/ads/${cleanFilename}`,
                                type: 'zip',
                                message: "ZIP extracted, but index.html was not found in root."
                            });
                        }
                    }
                } catch (unzipErr) {
                    console.error("Unzip error:", unzipErr);
                    res.json({
                        success: true,
                        url: `/uploads/ads/${cleanFilename}`,
                        type: 'zip',
                        message: "ZIP uploaded, but extraction failed. Hosted as raw file."
                    });
                }
            } else {
                // Determine type based on extension
                const ext = path.extname(cleanFilename).toLowerCase();
                let type = 'image';
                if (ext === '.mp4') {
                    type = 'video';
                }
                res.json({ success: true, url: `/uploads/ads/${cleanFilename}`, type });
            }
        } catch (e) {
            console.error("Upload save error:", e);
            res.status(500).json({ success: false, message: e.message });
        }
    });
});

// App Update API
app.get('/api/app-update', (req, res) => {
    const db = readDb();
    res.json(db.appUpdate || {
        versionCode: 1,
        versionName: "1.0.0",
        changelog: "No release notes.",
        apkUrl: "",
        isMandatory: false
    });
});

app.post('/api/app-update', (req, res) => {
    const db = readDb();
    db.appUpdate = {
        versionCode: parseInt(req.body.versionCode) || 1,
        versionName: req.body.versionName || "1.0.0",
        changelog: req.body.changelog || "",
        apkUrl: req.body.apkUrl || "",
        isMandatory: req.body.isMandatory === true || req.body.isMandatory === 'true'
    };
    writeDb(db);
    res.json({ success: true, appUpdate: db.appUpdate });
});

// Maintenance API
app.get('/api/maintenance', (req, res) => {
    const db = readDb();
    res.json(db.maintenance || {
        enabled: false,
        message: "We are currently performing scheduled server maintenance. We'll be back shortly!"
    });
});

app.post('/api/maintenance', (req, res) => {
    const db = readDb();
    db.maintenance = {
        enabled: req.body.enabled === true || req.body.enabled === 'true',
        message: req.body.message || "We are currently performing scheduled server maintenance. We'll be back shortly!"
    };
    writeDb(db);
    res.json({ success: true, maintenance: db.maintenance });
});

// Upload APK API
app.post('/api/upload-apk', (req, res) => {
    const dir = path.join(__dirname, 'public', 'apk');
    fs.mkdirSync(dir, { recursive: true });
    const filePath = path.join(dir, 'sportzfy-latest.apk');
    
    const data = [];
    req.on('data', chunk => data.push(chunk));
    req.on('end', () => {
        try {
            fs.writeFileSync(filePath, Buffer.concat(data));
            res.json({ success: true, message: "APK uploaded successfully" });
        } catch (e) {
            res.status(500).json({ success: false, message: e.message });
        }
    });
});

// OBS Studio Status API
app.get('/api/obs-status', (req, res) => {
    const streamKey = (req.query.streamKey || 'live').trim().toLowerCase();
    const indexPath = path.join(__dirname, 'public', 'obs', streamKey, 'index.m3u8');
    const active = fs.existsSync(indexPath) && fs.statSync(indexPath).size > 0;
    res.json({ active });
});

// OBS Studio Receiver Routes
app.put('/obs/:streamKey/:file', (req, res) => {
    const streamKey = req.params.streamKey;
    const file = req.params.file;
    const dir = path.join(__dirname, 'public', 'obs', streamKey);
    fs.mkdirSync(dir, { recursive: true });
    
    const filePath = path.join(dir, file);
    const data = [];
    req.on('data', chunk => data.push(chunk));
    req.on('end', () => {
        fs.writeFileSync(filePath, Buffer.concat(data));
        res.send("Created");
    });
});

app.post('/obs/:streamKey/:file', (req, res) => {
    const streamKey = req.params.streamKey;
    const file = req.params.file;
    const dir = path.join(__dirname, 'public', 'obs', streamKey);
    fs.mkdirSync(dir, { recursive: true });
    
    const filePath = path.join(dir, file);
    const data = [];
    req.on('data', chunk => data.push(chunk));
    req.on('end', () => {
        fs.writeFileSync(filePath, Buffer.concat(data));
        res.send("Created");
    });
});


// ==========================================
// SECURED MOBILE APP SYNC ENDPOINTS
// ==========================================

// Consolidated full-sync endpoint for mobile app
app.get('/api/secure/data', checkApiKey, (req, res) => {
    const db = readDb();
    res.json(db);
});

// Keep existing secure endpoints to prevent any breaking mobile interactions
app.get('/api/secure/matches', checkApiKey, (req, res) => {
    const db = readDb();
    res.json(db.matches || []);
});
app.get('/api/secure/channels', checkApiKey, (req, res) => {
    const db = readDb();
    res.json(db.channels || []);
});
app.get('/api/secure/highlights', checkApiKey, (req, res) => {
    const db = readDb();
    res.json(db.highlights || []);
});
app.get('/api/secure/notices', checkApiKey, (req, res) => {
    const db = readDb();
    res.json(db.notices || []);
});

// Full replace bulk sync
app.post('/api/secure/sync', checkApiKey, (req, res) => {
    const data = req.body;
    if (!data.matches || !data.channels || !data.highlights || !data.notices) {
        return res.status(400).json({ error: "Invalid full sync payload structure" });
    }
    writeDb(data);
    res.json({ success: true, message: "Database synchronized successfully!" });
});


// ==========================================
// REPORTING AND ANALYTICS ENDPOINTS
// ==========================================

// Track App Download and serve APK
app.get('/apk/sportzfy-latest.apk', (req, res) => {
    const apkPath = path.join(__dirname, 'public', 'apk', 'sportzfy-latest.apk');
    const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || "127.0.0.1";
    const clientIp = ip.split(',')[0].trim();
    
    logAnalyticsEvent('appDownloads', {
        ip: clientIp,
        ua: req.headers['user-agent'] || "Unknown",
        timestamp: new Date().toISOString()
    });
    
    if (fs.existsSync(apkPath)) {
        res.download(apkPath, 'sportzfy-latest.apk');
    } else {
        res.status(404).send("APK file not found. Please upload it via settings.");
    }
});

// Heartbeat ping from Web App, Web Admin, or Mobile App
app.post('/api/analytics/heartbeat', (req, res) => {
    try {
        const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || "127.0.0.1";
        const clientIp = ip.split(',')[0].trim();
        const ua = req.headers['user-agent'] || "Unknown";
        const key = clientIp + "_" + ua.substring(0, 50);
        
        const { source, page, watching } = req.body;
        
        activeSessions[key] = {
            ip: clientIp,
            ua: ua,
            source: source || "web-app",
            page: page || "/",
            watching: watching || null, // e.g. { type: 'channel', id: 5, name: 'Sky Sports' }
            lastSeen: Date.now()
        };
        
        res.json({ success: true });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// Track match / channel view
app.post('/api/analytics/track-view', (req, res) => {
    try {
        const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || "127.0.0.1";
        const clientIp = ip.split(',')[0].trim();
        const { type, id, name } = req.body;
        
        if (type === 'channel') {
            logAnalyticsEvent('channelViews', {
                channelId: parseInt(id) || 0,
                channelName: name || "Unknown Channel",
                ip: clientIp,
                timestamp: new Date().toISOString()
            });
        } else if (type === 'match') {
            logAnalyticsEvent('streamViews', {
                matchId: parseInt(id) || 0,
                matchTitle: name || "Unknown Match",
                ip: clientIp,
                timestamp: new Date().toISOString()
            });
        }
        
        res.json({ success: true });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// Track clickable banner redirect ads
app.get('/api/analytics/track-ad-click', (req, res) => {
    try {
        const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || "127.0.0.1";
        const clientIp = ip.split(',')[0].trim();
        const adId = req.query.id || "1";
        const clickUrl = req.query.url || "https://sportzfy.com";
        
        logAnalyticsEvent('adClicks', {
            adId: parseInt(adId) || 1,
            clickUrl: clickUrl,
            ip: clientIp,
            timestamp: new Date().toISOString()
        });
        
        res.redirect(clickUrl);
    } catch (err) {
        res.status(500).send("Redirect Error: " + err.message);
    }
});

// Dashboard aggregates
app.get('/api/analytics/dashboard', (req, res) => {
    try {
        const db = initAnalyticsDb();
        const now = Date.now();
        
        // Filter live active users (heartbeat in last 40 seconds)
        const liveViewers = Object.values(activeSessions).filter(session => now - session.lastSeen < 40000);
        
        // Calculate Top Channels by view count
        const channelCounts = {};
        (db.channelViews || []).forEach(v => {
            const key = (v.channelId || 0) + "||" + (v.channelName || "Unknown Channel");
            channelCounts[key] = (channelCounts[key] || 0) + 1;
        });
        const topChannels = Object.keys(channelCounts).map(key => {
            const parts = key.split('||');
            return {
                id: parseInt(parts[0]) || 0,
                name: parts[1],
                views: channelCounts[key]
            };
        }).sort((a, b) => b.views - a.views).slice(0, 10);
        
        // Calculate Top Matches/Streams by view count
        const streamCounts = {};
        (db.streamViews || []).forEach(v => {
            const key = (v.matchId || 0) + "||" + (v.matchTitle || "Unknown Match");
            streamCounts[key] = (streamCounts[key] || 0) + 1;
        });
        const topStreams = Object.keys(streamCounts).map(key => {
            const parts = key.split('||');
            return {
                id: parseInt(parts[0]) || 0,
                title: parts[1],
                views: streamCounts[key]
            };
        }).sort((a, b) => b.views - a.views).slice(0, 10);
        
        res.json({
            totals: {
                traffic: (db.traffic || []).length,
                downloads: (db.appDownloads || []).length,
                adClicks: (db.adClicks || []).length,
                appActive: (db.appActiveUsers || []).length,
                liveViewers: liveViewers.length
            },
            liveViewers: liveViewers,
            topChannels,
            topStreams,
            recentTraffic: (db.traffic || []).slice(-50).reverse(),
            recentDownloads: (db.appDownloads || []).slice(-30).reverse(),
            recentAppActive: (db.appActiveUsers || []).slice(-30).reverse(),
            recentAdClicks: (db.adClicks || []).slice(-30).reverse()
        });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// Full Raw data for table export CSV/Sheets
app.get('/api/analytics/raw', (req, res) => {
    try {
        const db = initAnalyticsDb();
        res.json(db);
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});


// --- STREAM HEALTH & USER VERIFICATION REPORTING ENGINE ---
const REPORTS_FILE = path.join(__dirname, 'reports.json');

function readReports() {
    try {
        if (!fs.existsSync(REPORTS_FILE)) {
            fs.writeFileSync(REPORTS_FILE, JSON.stringify([], null, 2), 'utf8');
            return [];
        }
        const data = fs.readFileSync(REPORTS_FILE, 'utf8');
        return JSON.parse(data);
    } catch (e) {
        console.error("Error reading reports file:", e);
        return [];
    }
}

function writeReports(reports) {
    try {
        fs.writeFileSync(REPORTS_FILE, JSON.stringify(reports, null, 2), 'utf8');
    } catch (e) {
        console.error("Error writing reports file:", e);
    }
}

// Global Stream Health State
let streamHealth = {
    lastChecked: null,
    status: {},
    checking: false,
    total: 0,
    completed: 0
};

// Stream HTTP ping validator (GET with abort signal)
async function checkStream(url) {
    if (!url) return { ok: false, error: "Empty URL", status: 0 };
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
        return { ok: false, error: "Unsupported protocol for ping", status: 0 };
    }
    
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 6000); // 6 seconds timeout
    
    try {
        const response = await fetch(url, {
            method: 'GET',
            signal: controller.signal,
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        });
        clearTimeout(timeoutId);
        
        if (response.status === 200) {
            return { ok: true, status: 200 };
        } else {
            return { ok: false, status: response.status, error: `HTTP Status ${response.status} ${response.statusText}` };
        }
    } catch (err) {
        clearTimeout(timeoutId);
        let msg = err.message || "Connection failed";
        if (err.name === 'AbortError') {
            msg = "Timeout (6s)";
        }
        return { ok: false, status: 0, error: msg };
    }
}

// Background Scan Executor
async function runFullStreamScan() {
    if (streamHealth.checking) return;
    streamHealth.checking = true;
    
    try {
        const db = readDb();
        const channels = db.channels || [];
        const matches = db.matches || [];
        
        streamHealth.total = channels.length + matches.length;
        streamHealth.completed = 0;
        
        console.log(`Starting stream health check scan for ${channels.length} channels and ${matches.length} matches...`);
        
        const healthResults = [];
        
        // Scan channels sequentially to avoid throttling or overloading
        for (const channel of channels) {
            if (channel.streamUrl) {
                const res = await checkStream(channel.streamUrl);
                const isOk = res.ok && res.status === 200;
                
                healthResults.push({
                    id: `channel_${channel.id}`,
                    type: "channel",
                    itemId: channel.id,
                    name: channel.name,
                    streamUrl: channel.streamUrl,
                    statusCode: res.status || 0,
                    ok: isOk,
                    errorMsg: isOk ? null : (res.error || `HTTP Status ${res.status}`),
                    checkedAt: new Date().toISOString()
                });
                
                streamHealth.status[channel.id] = {
                    ok: isOk,
                    errorMsg: isOk ? null : (res.error || `HTTP Status ${res.status}`),
                    checkedAt: new Date().toISOString()
                };
            } else {
                healthResults.push({
                    id: `channel_${channel.id}`,
                    type: "channel",
                    itemId: channel.id,
                    name: channel.name,
                    streamUrl: "",
                    statusCode: 0,
                    ok: false,
                    errorMsg: "No stream URL defined",
                    checkedAt: new Date().toISOString()
                });
                
                streamHealth.status[channel.id] = {
                    ok: false,
                    errorMsg: "No stream URL defined",
                    checkedAt: new Date().toISOString()
                };
            }
            streamHealth.completed++;
        }
        
        // Scan matches sequentially to avoid throttling or overloading
        for (const match of matches) {
            if (match.streamUrl) {
                const res = await checkStream(match.streamUrl);
                const isOk = res.ok && res.status === 200;
                
                healthResults.push({
                    id: `match_${match.id}`,
                    type: "match",
                    itemId: match.id,
                    name: match.title,
                    streamUrl: match.streamUrl,
                    statusCode: res.status || 0,
                    ok: isOk,
                    errorMsg: isOk ? null : (res.error || `HTTP Status ${res.status}`),
                    checkedAt: new Date().toISOString()
                });
            } else {
                healthResults.push({
                    id: `match_${match.id}`,
                    type: "match",
                    itemId: match.id,
                    name: match.title,
                    streamUrl: "",
                    statusCode: 0,
                    ok: false,
                    errorMsg: "No stream URL defined",
                    checkedAt: new Date().toISOString()
                });
            }
            streamHealth.completed++;
        }
        
        db.streamHealth = healthResults;
        writeDb(db);
        
        streamHealth.lastChecked = new Date().toISOString();
    } catch (e) {
        console.error("Error in stream health scan:", e);
    } finally {
        streamHealth.checking = false;
        console.log("Stream health check scan completed.");
    }
}

// Scan every hour automatically (60 minutes)
setInterval(runFullStreamScan, 60 * 60 * 1000);

// Run 15 seconds after start to let the server boot fully
setTimeout(runFullStreamScan, 15000);

// Stream Health & Reports REST endpoints
app.get('/api/stream-health', (req, res) => {
    res.json(streamHealth);
});

app.get('/api/stream-health/db', (req, res) => {
    try {
        const db = readDb();
        res.json(db.streamHealth || []);
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

app.post('/api/stream-health/scan', (req, res) => {
    if (streamHealth.checking) {
        return res.json({ success: false, message: "Scan already running", progress: streamHealth });
    }
    // Run asynchronously to prevent HTTP timeout
    runFullStreamScan();
    res.json({ success: true, message: "Scan started in background" });
});

app.get('/api/stream-reports', (req, res) => {
    const reports = readReports();
    res.json(reports);
});

app.post('/api/stream-report', (req, res) => {
    try {
        const channelId = req.body.channelId ? parseInt(req.body.channelId) : null;
        const channelName = req.body.channelName || "Unknown Channel";
        const streamUrl = req.body.streamUrl;
        const error = req.body.error || "Playback failure";
        
        if (!streamUrl) {
            return res.status(400).json({ error: "streamUrl is required" });
        }
        
        const reports = readReports();
        const existingIndex = reports.findIndex(r => r.streamUrl === streamUrl || (channelId && r.channelId === channelId));
        
        const newReport = {
            channelId,
            channelName,
            streamUrl,
            error,
            timestamp: new Date().toISOString(),
            ip: (req.headers['x-forwarded-for'] || req.socket.remoteAddress || "127.0.0.1").split(',')[0].trim(),
            count: existingIndex !== -1 ? (reports[existingIndex].count || 1) + 1 : 1
        };
        
        if (existingIndex !== -1) {
            reports[existingIndex] = newReport;
        } else {
            reports.push(newReport);
        }
        
        writeReports(reports);
        res.json({ success: true, report: newReport });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

app.post('/api/stream-reports/clear', (req, res) => {
    writeReports([]);
    res.json({ success: true, message: "All user reports cleared" });
});

app.post('/api/stream-reports/dismiss', (req, res) => {
    try {
        const channelId = req.body.channelId ? parseInt(req.body.channelId) : null;
        const streamUrl = req.body.streamUrl;
        
        let reports = readReports();
        if (channelId) {
            reports = reports.filter(r => r.channelId !== channelId);
        } else if (streamUrl) {
            reports = reports.filter(r => r.streamUrl !== streamUrl);
        } else {
            return res.status(400).json({ error: "channelId or streamUrl is required" });
        }
        
        writeReports(reports);
        res.json({ success: true, message: "Report dismissed successfully" });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});


// Fallback route for SPA dashboard client
app.get('*', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`=========================================`);
    console.log(`SPORTZFY LIVE UNIFIED EXPRESS ADMIN SERVER`);
    console.log(`Server: Running at http://0.0.0.0:${PORT}`);
    console.log(`Secure API Key: ${API_KEY}`);
    console.log(`Local Access URL: http://localhost:${PORT}`);
    console.log(`=========================================`);
});
