const fs = require('fs');
const path = require('path');

const DB_PATH = path.join(__dirname, 'db.json');
const PLAYLIST_PATH = path.join(__dirname, 'playlist.m3u');

function getBaseChannelName(name) {
    if (!name) return "";
    let clean = name.trim();
    
    // Remove emojis and flags
    clean = clean.replace(/[\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD00-\uDFFF]/g, '');
    
    // Remove trailing country suffix like " Argentina", " France", " Germany", " Itali", " Brazil", " Spain", " USA", " Bangladesh", " Algeria", " Mexico", " Serbia", " USA TV", " India"
    clean = clean.replace(/\s+(Argentina|France|Germany|Itali|Italy|Brazil|Spain|USA|Bangladesh|Algeria|Mexico|Serbia|USA TV|India|BD)\b/gi, '');
    
    // Strip parenthesized things like "(FIFA)", "(Local)"
    clean = clean.replace(/\s*\(.*?\)/gi, '');
    clean = clean.replace(/\s*\[.*?\]/gi, '');

    // Strip trailing backup indicator letters: " B", " C", " D", " E", " - B", " - C", " B 1080", " B HD", " B -", " - B 1080", " VIP", " HD"
    clean = clean.replace(/\s+-\s+$/gi, ''); // Trailing dash
    clean = clean.replace(/\s+([B-E])\b(\s+1080|\s+HD|\s+-)*$/gi, ''); // Backup letters B, C, D, E with potential extra suffix
    clean = clean.replace(/\s+-\s+([B-E])\b.*?$/gi, ''); // " - B"
    clean = clean.replace(/\s+(HD|VIP|TV|Live|SD)\b/gi, ''); // general quality indicator
    
    // Strip any trailing dashes or spaces
    clean = clean.replace(/[\s\-_]+$/g, '');
    
    // Collapse spacing and trim
    clean = clean.replace(/\s+/g, ' ').trim();
    
    return clean;
}

function determineCategory(name, parsedGroup) {
    const defaultCategory = "IPTV";
    if (parsedGroup && parsedGroup !== "General") {
        return parsedGroup;
    }
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
}

function importPlaylist() {
    if (!fs.existsSync(PLAYLIST_PATH)) {
        console.error("M3U Playlist file not found at " + PLAYLIST_PATH);
        return;
    }

    const playlistContent = fs.readFileSync(PLAYLIST_PATH, 'utf8');
    const lines = playlistContent.split(/\r?\n/);

    const db = fs.existsSync(DB_PATH) ? JSON.parse(fs.readFileSync(DB_PATH, 'utf8')) : {
        matches: [],
        channels: [],
        highlights: [],
        notices: [],
        bannerAd: {},
        appUpdate: {},
        adsSettings: {}
    };

    // Keep unique URLs globally to avoid duplicates
    const importedUrls = new Set();
    const channelsMap = new Map(); // baseName -> channelObject

    let currentMetadata = null;
    let nextId = 1;

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
            let category = "IPTV";
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

            // Global URL duplicate check
            if (importedUrls.has(streamUrl)) {
                continue;
            }
            importedUrls.add(streamUrl);

            category = determineCategory(originalName, parsedGroup);
            const baseName = getBaseChannelName(originalName);
            const baseKey = `${category.toLowerCase()}:${baseName.toLowerCase()}`;

            if (channelsMap.has(baseKey)) {
                // Add as backup stream source
                const ch = channelsMap.get(baseKey);
                if (!ch.streamUrl2) ch.streamUrl2 = streamUrl;
                else if (!ch.streamUrl3) ch.streamUrl3 = streamUrl;
                else if (!ch.streamUrl4) ch.streamUrl4 = streamUrl;
                else if (!ch.streamUrl5) ch.streamUrl5 = streamUrl;
            } else {
                // Create new channel
                const newChan = {
                    id: nextId++,
                    name: baseName || originalName,
                    category,
                    logoUrl,
                    streamUrl,
                    streamUrl2: "",
                    streamUrl3: "",
                    streamUrl4: "",
                    streamUrl5: ""
                };
                channelsMap.set(baseKey, newChan);
            }
        }
    }

    const finalChannels = Array.from(channelsMap.values());
    db.channels = finalChannels;

    fs.writeFileSync(DB_PATH, JSON.stringify(db, null, 2), 'utf8');
    console.log(`Successfully imported ${finalChannels.length} unique merged channels (with backup stream lines if any) into ${DB_PATH}.`);
}

importPlaylist();
