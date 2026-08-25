package tv.blofy.player;

import java.util.ArrayList;
import java.util.List;

/** Builds a safe FTS prefix expression; no leading-wildcard table scan is used. */
final class CatalogSearch {
    private CatalogSearch() {}

    static String prefixQuery(String value) {
        String normalized = ArabicNormalizer.normalizeForSearch(value);
        if (normalized.isEmpty()) return "";
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int offset = 0; offset < normalized.length(); ) {
            int point = normalized.codePointAt(offset);
            offset += Character.charCount(point);
            if (Character.isLetterOrDigit(point)) {
                token.appendCodePoint(point);
            } else if (token.length() > 0) {
                tokens.add(token.toString());
                token.setLength(0);
            }
        }
        if (token.length() > 0) tokens.add(token.toString());
        if (tokens.isEmpty()) return "";
        StringBuilder query = new StringBuilder();
        for (String part : tokens) {
            if (query.length() > 0) query.append(" AND ");
            query.append(part).append('*');
        }
        return query.toString();
    }
}
