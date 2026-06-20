package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonBuilder;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.schabi.newpipe.extractor.NewPipe.getDownloader;
import static org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_EMBEDDED_CLIENT_ID;
import static org.schabi.newpipe.extractor.services.youtube.ClientsConstants.WEB_EMBEDDED_CLIENT_VERSION;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CONTENT_CHECK_OK;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.CPN;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.RACY_CHECK_OK;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.VIDEO_ID;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_GAPIS_URL;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.generateTParameter;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientHeaders;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getOriginReferrerHeaders;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getValidJsonResponseBody;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYouTubeHeaders;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareJsonBuilder;

public final class YoutubeStreamHelper {

    private static final String PLAYER = "player";
    private static final String SERVICE_INTEGRITY_DIMENSIONS = "serviceIntegrityDimensions";
    private static final String PO_TOKEN = "poToken";
    private static final String BASE_YT_DESKTOP_WATCH_URL = "https://www.youtube.com/watch?v=";

    private YoutubeStreamHelper() {
    }

    @Nonnull
    public static JsonObject getWebMetadataPlayerResponse(
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry,
            @Nonnull final String videoId) throws IOException, ExtractionException {
        final InnertubeClientRequestInfo innertubeClientRequestInfo =
                InnertubeClientRequestInfo.ofWebClient();
        innertubeClientRequestInfo.clientInfo.clientVersion = getClientVersion();

        final Map<String, List<String>> headers = getYouTubeHeaders();

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
                YoutubeParsingHelper.getVisitorDataFromInnertube(innertubeClientRequestInfo,
                        localization, contentCountry, headers, YOUTUBEI_V1_URL, null, false);

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry,
                innertubeClientRequestInfo, null);

        addVideoIdCpnAndOkChecks(builder, videoId, null);

        final byte[] body = JsonWriter.string(builder.done())
                .getBytes(StandardCharsets.UTF_8);

        final String url = YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER
                + "&$fields=microformat,videoDetails.thumbnail.thumbnails,videoDetails.videoId";

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(
                        url, headers, body, localization)));
    }

    @Nonnull
    public static JsonObject getWebEmbeddedPlayerResponse(
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry,
            @Nonnull final String videoId,
            @Nonnull final String cpn,
            @Nullable final PoTokenResult webEmbeddedPoTokenResult,
            final int signatureTimestamp) throws IOException, ExtractionException {
        final InnertubeClientRequestInfo innertubeClientRequestInfo =
                InnertubeClientRequestInfo.ofWebEmbeddedPlayerClient();

        final Map<String, List<String>> headers = new HashMap<>(
                getClientHeaders(WEB_EMBEDDED_CLIENT_ID, WEB_EMBEDDED_CLIENT_VERSION));
        headers.putAll(getOriginReferrerHeaders("https://www.youtube.com"));

        final String embedUrl = BASE_YT_DESKTOP_WATCH_URL + videoId;

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData = webEmbeddedPoTokenResult == null
                ? YoutubeParsingHelper.getVisitorDataFromInnertube(innertubeClientRequestInfo,
                        localization, contentCountry, headers, YOUTUBEI_V1_URL, embedUrl, false)
                : webEmbeddedPoTokenResult.visitorData;

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry,
                innertubeClientRequestInfo, embedUrl);

        addVideoIdCpnAndOkChecks(builder, videoId, cpn);

        addPlaybackContext(builder, embedUrl, signatureTimestamp);

        if (webEmbeddedPoTokenResult != null) {
            addPoToken(builder, webEmbeddedPoTokenResult.playerRequestPoToken);
        }

        final byte[] body = JsonWriter.string(builder.done())
                .getBytes(StandardCharsets.UTF_8);
        final String url = YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER;

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)));
    }

    // ============================================================
    // Custom: modern WebEmbed body (2026-05-22).
    // YouTube extended WEB_EMBEDDED_PLAYER's innertube request to require
    // appInstallData, encryptedHostFlags, embeddedPlayerEncryptedContext
    // and rolloutToken from the embed page's ytcfg. Without them the API
    // answers "Video player configuration error" for every video.
    //
    // We bypass NPE's prepareJsonBuilder for this client and assemble the
    // body manually -- it's a one-off shape that does not benefit from
    // sharing NPE's generic builder.
    public static JsonObject getWebEmbeddedPlayerResponseModern(
            @Nonnull final org.schabi.newpipe.extractor.localization.Localization localization,
            @Nonnull final String videoId,
            final int signatureTimestamp) throws IOException, ExtractionException {
        final String embedUrl = "https://www.youtube.com/embed/" + videoId;
        final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
        // Referer/embedUrl: YouTube validates the embed-fetch's Referer against
        // a reputation list. Direct fetches (no Referer or Referer=youtube.com)
        // get bot-flagged "decoy" ytcfg values that fail the player API call.
        // We default to reddit.com (yt-dlp's choice); env-override in case
        // Google flags it. Must match the embedUrl we put in body.thirdParty.
        final String envReferer = System.getenv("WEBEMBED_REFERER");
        final String thirdPartyReferer = (envReferer != null && !envReferer.isEmpty())
                ? envReferer : "https://www.reddit.com/";

        // 1. Fetch the embed page to pull the experiment-flag + context fields.
        // CRITICAL: Referer: https://www.reddit.com/ -- YouTube treats embed
        // fetches without a third-party referer as bot-scraping and serves
        // decoy ytcfg values that fail validation in the player API call.
        // yt-dlp uses the same trick.
        final Map<String, java.util.List<String>> embedHeaders = new HashMap<>();
        embedHeaders.put("User-Agent", java.util.List.of(userAgent));
        embedHeaders.put("Accept", java.util.List.of("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        embedHeaders.put("Accept-Language", java.util.List.of("en-us,en;q=0.5"));
        embedHeaders.put("Sec-Fetch-Mode", java.util.List.of("navigate"));
        embedHeaders.put("Referer", java.util.List.of(thirdPartyReferer));
        final String embedHtml = getDownloader().get(embedUrl, embedHeaders, localization).responseBody();

        final String visitorData = extractEmbedField(embedHtml, "visitorData");
        final String encryptedHostFlags = extractEmbedField(embedHtml, "encryptedHostFlags");
        final String appInstallData = extractEmbedField(embedHtml, "appInstallData");
        final String embeddedPlayerEncryptedContext = extractEmbedField(embedHtml, "embeddedPlayerEncryptedContext");
        final String rolloutToken = extractEmbedField(embedHtml, "rolloutToken");
        final String deviceExperimentId = extractEmbedField(embedHtml, "deviceExperimentId");
        final String clickTrackingParams = extractEmbedField(embedHtml, "clickTrackingParams");

        if (visitorData == null || encryptedHostFlags == null || appInstallData == null
                || embeddedPlayerEncryptedContext == null) {
            throw new ExtractionException("WebEmbed modern: required field missing in embed page "
                    + "(visitorData=" + (visitorData != null) + " ehf=" + (encryptedHostFlags != null)
                    + " aid=" + (appInstallData != null) + " epec=" + (embeddedPlayerEncryptedContext != null) + ")");
        }

        // 2. Build the modern request body. JSON-escape strings defensively.
        final StringBuilder b = new StringBuilder(8192);
        b.append('{');
        b.append("\"context\":{\"client\":{");
        b.append("\"hl\":\"en\",\"gl\":\"US\",");
        b.append("\"clientName\":\"WEB_EMBEDDED_PLAYER\",");
        b.append("\"clientVersion\":\"2.20260521.00.00\",");
        b.append("\"visitorData\":\"").append(jsonEscape(visitorData)).append("\",");
        b.append("\"userAgent\":\"").append(jsonEscape(userAgent)).append("\",");
        b.append("\"osName\":\"Windows\",\"osVersion\":\"10.0\",");
        b.append("\"platform\":\"DESKTOP\",\"clientFormFactor\":\"UNKNOWN_FORM_FACTOR\",");
        b.append("\"originalUrl\":\"").append(jsonEscape(embedUrl)).append("?html5=1\",");
        b.append("\"configInfo\":{\"appInstallData\":\"").append(jsonEscape(appInstallData)).append("\"},");
        b.append("\"timeZone\":\"UTC\",\"browserName\":\"Chrome\",\"browserVersion\":\"141.0.0.0\",");
        b.append("\"acceptHeader\":\"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\",");
        b.append("\"deviceMake\":\"\",\"deviceModel\":\"\",");
        if (deviceExperimentId != null) {
            b.append("\"deviceExperimentId\":\"").append(jsonEscape(deviceExperimentId)).append("\",");
        }
        b.append("\"rolloutToken\":\"").append(jsonEscape(rolloutToken != null ? rolloutToken : "")).append("\",");
        b.append("\"utcOffsetMinutes\":0");
        b.append("},");
        b.append("\"user\":{\"lockedSafetyMode\":false},");
        b.append("\"request\":{\"useSsl\":true},");
        if (clickTrackingParams != null) {
            b.append("\"clickTracking\":{\"clickTrackingParams\":\"").append(jsonEscape(clickTrackingParams)).append("\"},");
        }
        b.append("\"thirdParty\":{");
        b.append("\"embeddedPlayerContext\":{");
        b.append("\"embeddedPlayerEncryptedContext\":\"").append(jsonEscape(embeddedPlayerEncryptedContext)).append("\",");
        b.append("\"ancestorOriginsSupported\":false");
        b.append("},");
        b.append("\"embedUrl\":\"").append(jsonEscape(thirdPartyReferer)).append("\"");
        b.append("}");
        b.append("},");
        b.append("\"videoId\":\"").append(jsonEscape(videoId)).append("\",");
        b.append("\"playbackContext\":{\"contentPlaybackContext\":{");
        b.append("\"html5Preference\":\"HTML5_PREF_WANTS\",");
        b.append("\"signatureTimestamp\":").append(signatureTimestamp).append(',');
        b.append("\"encryptedHostFlags\":\"").append(jsonEscape(encryptedHostFlags)).append('"');
        b.append("}},");
        b.append("\"contentCheckOk\":true,\"racyCheckOk\":true");
        b.append('}');

        // 3. POST it.
        final Map<String, java.util.List<String>> postHeaders = new HashMap<>();
        postHeaders.put("User-Agent", java.util.List.of(userAgent));
        postHeaders.put("Content-Type", java.util.List.of("application/json"));
        postHeaders.put("Origin", java.util.List.of("https://www.youtube.com"));
        postHeaders.put("Referer", java.util.List.of(embedUrl));
        postHeaders.put("X-Youtube-Client-Name", java.util.List.of("56"));
        postHeaders.put("X-Youtube-Client-Version", java.util.List.of("2.20260521.00.00"));

        final byte[] body = b.toString().getBytes(StandardCharsets.UTF_8);
        final String url = YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER;

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, postHeaders, body, localization)));
    }

    /** Extract the *value* of "field":"value" from raw embed HTML. Returns null if absent. */
    private static String extractEmbedField(final String html, final String fieldName) {
        final String needle = "\"" + fieldName + "\":\"";
        final int i = html.indexOf(needle);
        if (i < 0) return null;
        final int start = i + needle.length();
        final int end = html.indexOf('"', start);
        if (end < 0) return null;
        return html.substring(start, end);
    }

    /** Minimal JSON string-escape for the fields we control (no embedded newlines etc). */
    private static String jsonEscape(final String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static JsonObject getAndroidPlayerResponse(
            @Nonnull final ContentCountry contentCountry,
            @Nonnull final Localization localization,
            @Nonnull final String videoId,
            @Nonnull final String cpn,
            @Nonnull final PoTokenResult androidPoTokenResult)
            throws IOException, ExtractionException {
        final InnertubeClientRequestInfo innertubeClientRequestInfo =
                InnertubeClientRequestInfo.ofAndroidClient();
        innertubeClientRequestInfo.clientInfo.visitorData = androidPoTokenResult.visitorData;

        final Map<String, List<String>> headers =
                getMobileClientHeaders(getAndroidUserAgent(localization));

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry,
                innertubeClientRequestInfo, null);

        addVideoIdCpnAndOkChecks(builder, videoId, cpn);

        addPoToken(builder, androidPoTokenResult.playerRequestPoToken);

        final byte[] body = JsonWriter.string(builder.done())
                .getBytes(StandardCharsets.UTF_8);

        final String url = YOUTUBEI_V1_GAPIS_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER
                + "&t=" + generateTParameter() + "&id=" + videoId;

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)));
    }

    public static JsonObject getAndroidReelPlayerResponse(
            @Nonnull final ContentCountry contentCountry,
            @Nonnull final Localization localization,
            @Nonnull final String videoId,
            @Nonnull final String cpn) throws IOException, ExtractionException {
        final InnertubeClientRequestInfo innertubeClientRequestInfo =
                InnertubeClientRequestInfo.ofAndroidClient();

        final Map<String, List<String>> headers =
                getMobileClientHeaders(getAndroidUserAgent(localization));

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
                YoutubeParsingHelper.getVisitorDataFromInnertube(innertubeClientRequestInfo,
                        localization, contentCountry, headers, YOUTUBEI_V1_GAPIS_URL, null, false);

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry,
                innertubeClientRequestInfo, null);

        builder.object("playerRequest");
        addVideoIdCpnAndOkChecks(builder, videoId, cpn);
        builder.end()
                .value("disablePlayerResponse", false);

        final byte[] body = JsonWriter.string(builder.done())
                .getBytes(StandardCharsets.UTF_8);

        final String url = YOUTUBEI_V1_GAPIS_URL + "reel/reel_item_watch" + "?"
                + DISABLE_PRETTY_PRINT_PARAMETER + "&t=" + generateTParameter() + "&id=" + videoId
                + "&$fields=playerResponse";

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)))
                .getObject("playerResponse");
    }

    public static JsonObject getAndroidVrPlayerResponse(
            @Nonnull final ContentCountry contentCountry,
            @Nonnull final Localization localization,
            @Nonnull final String videoId,
            @Nonnull final String cpn) throws IOException, ExtractionException {
        final InnertubeClientRequestInfo innertubeClientRequestInfo =
                InnertubeClientRequestInfo.ofAndroidVrClient();

        final Map<String, List<String>> headers =
                getMobileClientHeaders(ClientsConstants.ANDROID_VR_USER_AGENT);

        innertubeClientRequestInfo.clientInfo.visitorData =
                YoutubeParsingHelper.getVisitorDataFromInnertube(innertubeClientRequestInfo,
                        localization, contentCountry, headers, YOUTUBEI_V1_URL, null, false);

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry,
                innertubeClientRequestInfo, null);

        addVideoIdCpnAndOkChecks(builder, videoId, cpn);

        final byte[] body = JsonWriter.string(builder.done())
                .getBytes(StandardCharsets.UTF_8);

        final String url = YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER
                + "&t=" + generateTParameter() + "&id=" + videoId;

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)));
    }

    // Custom 2026-06-09: TVHTML5 (TV/Cobalt) client. poToken-free fallback for
    // audio0 videos where ANDROID/VR have no audio and WEB_EMBEDDED is 403'd.
    // Web-family (needs signatureTimestamp + downstream sig/nsig descramble),
    // modelled on getWebEmbeddedPlayerResponse but WATCH screen + Cobalt UA.
    // Authenticated TVHTML5 (TV/Cobalt) player response. The TV client is
    // bot-walled (LOGIN_REQUIRED) unless the request carries a real signed-in
    // session, so we scrape youtube.com/tv (the downloader attaches the account
    // cookies) for its ytcfg, build the full TV context from those fields, and
    // let the downloader add the SAPISIDHASH Authorization. The user session id
    // for that auth is handed to the downloader via the private
    // X-Yt-Auth-Session header (consumed + stripped there). Modelled on
    // getWebEmbeddedPlayerResponseModern + yt-dlp's tv client.
    public static JsonObject getTvHtml5PlayerResponse(
            @Nonnull final Localization localization,
            @Nonnull final ContentCountry contentCountry,
            @Nonnull final String videoId,
            @Nonnull final String cpn,
            final int signatureTimestamp) throws IOException, ExtractionException {
        final TvCfg cfg = getTvCfg(localization);
        if (cfg == null || cfg.visitorData == null || cfg.clientVersion == null) {
            throw new ExtractionException("TVHTML5: /tv ytcfg scrape missing required fields "
                    + "(visitorData/clientVersion) -- account not signed in?");
        }

        final String cobaltUa = ClientsConstants.TVHTML5_USER_AGENT + ",gzip(gfe)";
        final String gl = contentCountry.getCountryCode();
        final String hl = localization.getLanguageCode();

        final StringBuilder b = new StringBuilder(4096);
        b.append('{');
        b.append("\"context\":{\"client\":{");
        b.append("\"hl\":\"").append(jsonEscape(hl)).append("\",");
        b.append("\"gl\":\"").append(jsonEscape(gl)).append("\",");
        b.append("\"deviceMake\":\"Unknown\",\"deviceModel\":\"Unknown\",");
        b.append("\"visitorData\":\"").append(jsonEscape(cfg.visitorData)).append("\",");
        b.append("\"userAgent\":\"").append(jsonEscape(cobaltUa)).append("\",");
        b.append("\"clientName\":\"TVHTML5\",");
        b.append("\"clientVersion\":\"").append(jsonEscape(cfg.clientVersion)).append("\",");
        b.append("\"osName\":\"Unknown_0\",\"osVersion\":\"\",");
        b.append("\"originalUrl\":\"https://www.youtube.com/tv\",");
        b.append("\"theme\":\"CLASSIC\",\"platform\":\"TV\",");
        b.append("\"clientFormFactor\":\"UNKNOWN_FORM_FACTOR\",\"webpSupport\":false,");
        if (cfg.appInstallData != null) {
            b.append("\"configInfo\":{\"appInstallData\":\"")
                    .append(jsonEscape(cfg.appInstallData)).append("\"},");
        }
        b.append("\"tvAppInfo\":{\"appQuality\":\"TV_APP_QUALITY_FULL_ANIMATION\",");
        b.append("\"systemIntegrator\":\"unknown\",");
        b.append("\"releaseVehicle\":\"COBALT_RELEASE_VEHICLE_LEGACY_THIRD_PARTY\"},");
        b.append("\"timeZone\":\"UTC\",\"browserName\":\"Cobalt\",");
        b.append("\"browserVersion\":\"25.lts.30.1034943-gold\",");
        b.append("\"platformDetail\":\"PLATFORM_DETAIL_TV\",\"chipset\":\"unknown_0\",");
        b.append("\"acceptHeader\":\"text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\",");
        if (cfg.deviceExperimentId != null) {
            b.append("\"deviceExperimentId\":\"")
                    .append(jsonEscape(cfg.deviceExperimentId)).append("\",");
        }
        if (cfg.rolloutToken != null) {
            b.append("\"rolloutToken\":\"")
                    .append(jsonEscape(cfg.rolloutToken)).append("\",");
        }
        b.append("\"utcOffsetMinutes\":0");
        b.append("},");
        b.append("\"user\":{\"lockedSafetyMode\":false},");
        b.append("\"request\":{\"useSsl\":true}");
        b.append("},");
        b.append("\"videoId\":\"").append(jsonEscape(videoId)).append("\",");
        b.append("\"playbackContext\":{\"contentPlaybackContext\":{");
        b.append("\"html5Preference\":\"HTML5_PREF_WANTS\",");
        b.append("\"signatureTimestamp\":").append(signatureTimestamp);
        b.append("}},");
        b.append("\"contentCheckOk\":true,\"racyCheckOk\":true");
        b.append('}');

        final Map<String, List<String>> postHeaders = new HashMap<>();
        postHeaders.put("User-Agent", List.of(cobaltUa));
        postHeaders.put("Content-Type", List.of("application/json"));
        postHeaders.put("Origin", List.of("https://www.youtube.com"));
        postHeaders.put("X-Youtube-Client-Name", List.of(ClientsConstants.TVHTML5_CLIENT_ID));
        postHeaders.put("X-Youtube-Client-Version", List.of(cfg.clientVersion));
        postHeaders.put("X-Goog-Visitor-Id", List.of(cfg.visitorData));
        postHeaders.put("X-Youtube-Bootstrap-Logged-In", List.of("true"));
        if (cfg.userSessionId != null) {
            postHeaders.put("X-Yt-Auth-Session", List.of(cfg.userSessionId));
        }

        final byte[] body = b.toString().getBytes(StandardCharsets.UTF_8);
        final String url = YOUTUBEI_V1_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER;
        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, postHeaders, body, localization)));
    }

    // ---- youtube.com/tv ytcfg scrape + cache (for the authenticated TV client) ----
    private static volatile TvCfg tvCfgCache;
    private static volatile long tvCfgFetchedAt;
    private static final long TVCFG_TTL_MS = 30L * 60 * 1000;

    private static final class TvCfg {
        String visitorData;
        String clientVersion;
        String appInstallData;
        String rolloutToken;
        String deviceExperimentId;
        String userSessionId;
    }

    private static synchronized TvCfg getTvCfg(final Localization localization) {
        final long now = System.currentTimeMillis();
        if (tvCfgCache != null && (now - tvCfgFetchedAt) < TVCFG_TTL_MS) {
            return tvCfgCache;
        }
        try {
            // Fetch /tv directly via HttpURLConnection (NOT the reqwest4j downloader,
            // which requests brotli but doesn't decode it -> garbled body) with
            // Accept-Encoding: identity and the account cookies read from the same
            // file the downloader uses. Needs the full signed-in page for the ytcfg.
            final String html = fetchTvPage();
            final TvCfg c = new TvCfg();
            c.visitorData = extractEmbedField(html, "visitorData");
            c.clientVersion = extractEmbedField(html, "INNERTUBE_CONTEXT_CLIENT_VERSION");
            c.appInstallData = extractEmbedField(html, "appInstallData");
            c.rolloutToken = extractEmbedField(html, "rolloutToken");
            c.deviceExperimentId = extractEmbedField(html, "DEVICE_EXPERIMENT_ID");
            c.userSessionId = extractEmbedField(html, "USER_SESSION_ID");
            if (c.userSessionId == null) {
                final String dsid = extractEmbedField(html, "DATASYNC_ID");
                if (dsid != null && dsid.contains("||")) {
                    c.userSessionId = dsid.substring(0, dsid.indexOf("||"));
                }
            }
            System.out.println("[NPE/TvHtml5] /tv ytcfg vd=" + (c.visitorData != null)
                    + " cv=" + c.clientVersion + " aid=" + (c.appInstallData != null)
                    + " rt=" + (c.rolloutToken != null) + " deid=" + (c.deviceExperimentId != null)
                    + " usid=" + (c.userSessionId != null));
            if (c.visitorData != null && c.clientVersion != null) {
                tvCfgCache = c;
                tvCfgFetchedAt = now;
            }
            return c;
        } catch (final Exception e) {
            System.out.println("[NPE/TvHtml5] /tv ytcfg scrape failed: " + e.getMessage());
            return tvCfgCache;
        }
    }

    private static volatile String tvCookieHeader;
    private static volatile boolean tvCookieLoaded;

    private static String tvCookies() {
        if (tvCookieLoaded) return tvCookieHeader;
        tvCookieLoaded = true;
        String path = System.getenv("YOUTUBE_COOKIES_FILE");
        if (path == null || path.isEmpty()) path = "/app/youtube-cookies.txt";
        final java.io.File f = new java.io.File(path);
        if (!f.exists()) return null;
        final StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final String[] p = line.split("\\t");
                if (p.length < 7) continue;
                // Only youtube-domain cookies. The file also holds .google.com
                // cookies (SID/SAPISID with DIFFERENT values); sending both yields
                // a duplicate SID= and YouTube falls back to the login page. curl's
                // -b domain-filters; mimic that here.
                if (!p[0].contains("youtube.com")) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(p[5]).append('=').append(p[6]);
            }
        } catch (final Exception e) {
            return null;
        }
        tvCookieHeader = sb.length() == 0 ? null : sb.toString();
        return tvCookieHeader;
    }

    private static String fetchTvPage() throws IOException {
        final java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL("https://www.youtube.com/tv").openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", ClientsConstants.TVHTML5_USER_AGENT);
        conn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "en-us,en;q=0.5");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        final String cookies = tvCookies();
        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
        try {
            // Manual Accept-Encoding disables HttpURLConnection's transparent
            // gunzip, so decode by Content-Encoding ourselves. (identity gave a
            // compressed body anyway; request gzip, which we can decode -- JDK
            // has no brotli.)
            final String enc = conn.getContentEncoding();
            java.io.InputStream raw = conn.getInputStream();
            final java.io.InputStream is = "gzip".equalsIgnoreCase(enc)
                    ? new java.util.zip.GZIPInputStream(raw) : raw;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    public static JsonObject getIosPlayerResponse(@Nonnull final ContentCountry contentCountry,
                                                  @Nonnull final Localization localization,
                                                  @Nonnull final String videoId,
                                                  @Nonnull final String cpn,
                                                  @Nullable final PoTokenResult iosPoTokenResult)
            throws IOException, ExtractionException {
        final InnertubeClientRequestInfo innertubeClientRequestInfo =
                InnertubeClientRequestInfo.ofIosClient();

        final Map<String, List<String>> headers =
                getMobileClientHeaders(getIosUserAgent(localization));

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData = iosPoTokenResult == null
                ? YoutubeParsingHelper.getVisitorDataFromInnertube(innertubeClientRequestInfo,
                        localization, contentCountry, headers, YOUTUBEI_V1_URL, null, false)
                : iosPoTokenResult.visitorData;

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry,
                innertubeClientRequestInfo, null);

        addVideoIdCpnAndOkChecks(builder, videoId, cpn);

        if (iosPoTokenResult != null) {
            addPoToken(builder, iosPoTokenResult.playerRequestPoToken);
        }

        final byte[] body = JsonWriter.string(builder.done())
                .getBytes(StandardCharsets.UTF_8);

        final String url = YOUTUBEI_V1_GAPIS_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER
                + "&t=" + generateTParameter() + "&id=" + videoId;

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)));
    }

    public static JsonObject getVisionOsPlayerResponse(@Nonnull final ContentCountry contentCountry,
                                                       @Nonnull final Localization localization,
                                                       @Nonnull final String videoId,
                                                       @Nonnull final String cpn)
            throws IOException, ExtractionException {
        final InnertubeClientRequestInfo innertubeClientRequestInfo =
                InnertubeClientRequestInfo.ofVisionOsClient();

        final Map<String, List<String>> headers =
                getMobileClientHeaders(getVisionOsUserAgent(localization));

        // We must always pass a valid visitorData to get valid player responses, which needs to be
        // got from YouTube
        innertubeClientRequestInfo.clientInfo.visitorData =
                YoutubeParsingHelper.getVisitorDataFromInnertube(innertubeClientRequestInfo,
                localization, contentCountry, headers, YOUTUBEI_V1_URL, null, false);

        final JsonBuilder<JsonObject> builder = prepareJsonBuilder(localization, contentCountry,
                innertubeClientRequestInfo, null);

        addVideoIdCpnAndOkChecks(builder, videoId, cpn);

        final byte[] body = JsonWriter.string(builder.done())
                .getBytes(StandardCharsets.UTF_8);

        final String url = YOUTUBEI_V1_GAPIS_URL + PLAYER + "?" + DISABLE_PRETTY_PRINT_PARAMETER
                + "&t=" + generateTParameter() + "&id=" + videoId;

        return JsonUtils.toJsonObject(getValidJsonResponseBody(
                getDownloader().postWithContentTypeJson(url, headers, body, localization)));
    }

    private static void addVideoIdCpnAndOkChecks(@Nonnull final JsonBuilder<JsonObject> builder,
                                                 @Nonnull final String videoId,
                                                 @Nullable final String cpn) {
        builder.value(VIDEO_ID, videoId);

        if (cpn != null) {
            builder.value(CPN, cpn);
        }

        builder.value(CONTENT_CHECK_OK, true)
                .value(RACY_CHECK_OK, true);
    }

    private static void addPlaybackContext(@Nonnull final JsonBuilder<JsonObject> builder,
                                           @Nonnull final String referer,
                                           final int signatureTimestamp) {
        builder.object("playbackContext")
                .object("contentPlaybackContext")
                .value("signatureTimestamp", signatureTimestamp)
                .value("referer", referer)
                .end()
                .end();
    }

    private static void addPoToken(@Nonnull final JsonBuilder<JsonObject> builder,
                                   @Nonnull final String poToken) {
        builder.object(SERVICE_INTEGRITY_DIMENSIONS)
                .value(PO_TOKEN, poToken)
                .end();
    }

    @Nonnull
    private static Map<String, List<String>> getMobileClientHeaders(
            @Nonnull final String userAgent) {
        return Map.of("User-Agent", List.of(userAgent),
                "X-Goog-Api-Format-Version", List.of("2"));
    }
}
