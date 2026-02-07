package com.longvin.trading.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Maps short QuoteReqIDs (max 39 chars per DAS) to full context (shadowAccount, primaryClOrdId, route).
 * DAS FIX API limits tag 131 QuoteReqID to 39 characters; our embedded format exceeds that.
 */
public final class QuoteReqIdMapper {

    private static final Logger log = LoggerFactory.getLogger(QuoteReqIdMapper.class);
    private static final int MAX_QUOTE_REQ_ID_LENGTH = 39;

    private static final ConcurrentHashMap<String, QuoteReqContext> CACHE = new ConcurrentHashMap<>();

    public record QuoteReqContext(String shadowAccount, String primaryClOrdId, String locateRoute) {}

    private QuoteReqIdMapper() {}

    /**
     * Register context and return a short QuoteReqID (<=39 chars) for use in MsgType=R.
     * Call before sending Short Locate Quote Request.
     */
    public static String registerAndGetShortId(String shadowAccount, String primaryClOrdId, String locateRoute) {
        long ts = System.currentTimeMillis();
        String base36 = Long.toString(ts, 36);
        String rnd = randomAlphanum(4);
        String shortId = "QL_" + base36 + "_" + rnd;
        if (shortId.length() > MAX_QUOTE_REQ_ID_LENGTH) {
            shortId = shortId.substring(0, MAX_QUOTE_REQ_ID_LENGTH);
        }
        CACHE.put(shortId, new QuoteReqContext(shadowAccount, primaryClOrdId, locateRoute));
        log.debug("Registered QuoteReqID mapping: shortId={}, shadowAccount={}, primaryClOrdId={}",
                shortId, shadowAccount, primaryClOrdId);
        return shortId;
    }

    /**
     * Look up context by QuoteReqID. Removes the entry after retrieval.
     * Returns empty if not found (e.g. old embedded format or expired).
     */
    public static Optional<QuoteReqContext> lookupAndRemove(String quoteReqId) {
        if (quoteReqId == null || quoteReqId.isBlank()) {
            return Optional.empty();
        }
        QuoteReqContext ctx = CACHE.remove(quoteReqId);
        if (ctx != null) {
            log.debug("Looked up QuoteReqID: {} -> shadowAccount={}, primaryClOrdId={}",
                    quoteReqId, ctx.shadowAccount(), ctx.primaryClOrdId());
            return Optional.of(ctx);
        }
        return Optional.empty();
    }

    private static String randomAlphanum(int len) {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(len);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
