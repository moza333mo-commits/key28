package com.linguakey.keyboard.latin.offline;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Modular Offline Translation Library Engine for LinguaKey (Schema v1).
 *
 * Key Design Principles:
 * 1. The APK ships 100% EMPTY (zero built-in offline libraries) to keep APK size minimal.
 * 2. Users can import MULTIPLE independent JSON libraries for the SAME language pair
 *    (e.g., "AR-EN Daily Chat" + "AR-EN Syrian Dialect" + "AR-EN Business").
 * 3. Each library can be enabled, disabled, or deleted independently at any time.
 * 4. O(1) merged in-memory index across all enabled libraries for instant 0ms offline lookup,
 *    supporting both exact/normalized full-sentence lookup (with Arabic normalization) AND
 *    multi-word compositional translation when no exact sentence entry matches.
 */
public final class LinguaKeyOfflineLibraryRepository {
    private static final String TAG = "LKOfflineLibRepo";
    public static final int SCHEMA_VERSION = 1;
    private static final String DIR_NAME = "offline_libraries";
    private static final String INDEX_FILE = "libraries_index.json";

    public static final class LibraryMeta {
        public String id;
        public String name;
        public String description;
        public String sourceLang;
        public String targetLang;
        public boolean bidirectional;
        public boolean enabled;
        public int entryCount;
        public long importedAt;

        public LibraryMeta() {
        }

        public LibraryMeta copy() {
            LibraryMeta c = new LibraryMeta();
            c.id = id;
            c.name = name;
            c.description = description;
            c.sourceLang = sourceLang;
            c.targetLang = targetLang;
            c.bidirectional = bidirectional;
            c.enabled = enabled;
            c.entryCount = entryCount;
            c.importedAt = importedAt;
            return c;
        }
    }

    public static final class OfflineTranslationHit {
        public final String primary;
        public final List<String> alternatives;
        public final String libraryName;
        public final boolean isExactMatch;

        public OfflineTranslationHit(
                final String primary,
                final List<String> alternatives,
                final String libraryName,
                final boolean isExactMatch
        ) {
            this.primary = primary;
            this.alternatives = alternatives != null ? alternatives : Collections.<String>emptyList();
            this.libraryName = libraryName != null ? libraryName : "";
            this.isExactMatch = isExactMatch;
        }
    }

    public static final class LibraryImportResult {
        public boolean success = false;
        public String errorMessage = null;
        public LibraryMeta meta = null;
        public int importedEntries = 0;
        public int skippedInvalid = 0;
    }

    private static final class IndexedPair {
        final String translation;
        final List<String> alts;
        final String libraryName;

        IndexedPair(final String translation, final List<String> alts, final String libraryName) {
            this.translation = translation;
            this.alts = alts;
            this.libraryName = libraryName;
        }
    }

    private static volatile LinguaKeyOfflineLibraryRepository sInstance;

    private final File mRootDir;
    private final File mIndexFile;
    private final Object mLock = new Object();

    private final List<LibraryMeta> mLibraries = new ArrayList<>();
    // pairKey ("ar->en") -> normalizedSource -> List<IndexedPair> (supports multiple libraries per language!)
    private final Map<String, Map<String, List<IndexedPair>>> mPairIndex = new HashMap<>();
    private boolean mLoaded = false;

    public static LinguaKeyOfflineLibraryRepository getInstance(final Context context) {
        if (sInstance == null) {
            synchronized (LinguaKeyOfflineLibraryRepository.class) {
                if (sInstance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
                    sInstance = new LinguaKeyOfflineLibraryRepository(dir);
                }
            }
        }
        return sInstance;
    }

    public LinguaKeyOfflineLibraryRepository(final File rootDir) {
        mRootDir = rootDir;
        if (mRootDir != null && !mRootDir.exists()) {
            mRootDir.mkdirs();
        }
        mIndexFile = new File(mRootDir, INDEX_FILE);
        ensureLoaded();
    }

    public void ensureLoaded() {
        synchronized (mLock) {
            if (mLoaded) return;
            mLoaded = true;
            mLibraries.clear();
            if (mIndexFile.exists() && mIndexFile.length() > 0L) {
                try {
                    String raw = readUtf8(mIndexFile);
                    JSONObject root = new JSONObject(raw);
                    JSONArray arr = root.optJSONArray("libraries");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            LibraryMeta m = new LibraryMeta();
                            m.id = o.optString("id", "");
                            if (m.id.isEmpty()) continue;
                            m.name = o.optString("name", "مكتبة غير مسماة");
                            m.description = o.optString("description", "");
                            m.sourceLang = normalizeLang(o.optString("sourceLang", "ar"));
                            m.targetLang = normalizeLang(o.optString("targetLang", "en"));
                            m.bidirectional = o.optBoolean("bidirectional", true);
                            m.enabled = o.optBoolean("enabled", true);
                            m.entryCount = o.optInt("entryCount", 0);
                            m.importedAt = o.optLong("importedAt", System.currentTimeMillis());
                            if (getLibraryFile(m.id).exists()) {
                                mLibraries.add(m);
                            }
                        }
                    }
                } catch (Throwable e) {
                    try {
                        Log.e(TAG, "Failed to load offline libraries index", e);
                    } catch (Throwable ignored) {
                    }
                }
            }
            rebuildMergedIndexLocked();
        com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
        }
    }

    public List<LibraryMeta> getLibraries() {
        ensureLoaded();
        synchronized (mLock) {
            List<LibraryMeta> out = new ArrayList<>(mLibraries.size());
            for (int i = 0; i < mLibraries.size(); i++) {
                out.add(mLibraries.get(i).copy());
            }
            return out;
        }
    }

    public boolean hasEnabledLibraries() {
        ensureLoaded();
        synchronized (mLock) {
            for (int i = 0; i < mLibraries.size(); i++) {
                if (mLibraries.get(i).enabled) return true;
            }
            return false;
        }
    }

    /**
     * Imports a JSON offline translation library file as a NEW independent library entry.
     * Multiple libraries for the same language pair are fully supported and merged at lookup time!
     */
    public LibraryImportResult importLibraryJson(final String rawJson, final String fallbackFileName) {
        ensureLoaded();
        LibraryImportResult result = new LibraryImportResult();
        if (rawJson == null || rawJson.trim().isEmpty()) {
            result.errorMessage = "الملف فارغ ولا يحتوي على بيانات JSON صالحة.";
            return result;
        }
        String cleaned = rawJson.trim();
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1).trim();
        }

        JSONObject root;
        try {
            root = new JSONObject(cleaned);
        } catch (JSONException e) {
            result.errorMessage = "صيغة ملف JSON غير صالحة: " + e.getMessage();
            return result;
        }

        JSONArray entriesArr = root.optJSONArray("entries");
        if (entriesArr == null || entriesArr.length() == 0) {
            result.errorMessage = "الملف لا يحتوي على حقل \"entries\" أو أنه فارغ.";
            return result;
        }

        String name = root.optString("name", "").trim();
        if (name.isEmpty()) {
            name = (fallbackFileName != null && !fallbackFileName.trim().isEmpty())
                    ? fallbackFileName.trim()
                    : "مكتبة ترجمة بدون نت";
        }
        String description = root.optString("description", "").trim();
        String sourceLang = normalizeLang(root.optString("sourceLang", "ar"));
        String targetLang = normalizeLang(root.optString("targetLang", "en"));
        boolean bidirectional = root.optBoolean("bidirectional", true);

        JSONArray validEntries = new JSONArray();
        int invalidCount = 0;

        for (int i = 0; i < entriesArr.length(); i++) {
            JSONObject item = entriesArr.optJSONObject(i);
            if (item == null) {
                invalidCount++;
                continue;
            }
            String src = item.optString("src", "").trim();
            String tgt = item.optString("tgt", "").trim();
            if (src.isEmpty() || tgt.isEmpty()) {
                invalidCount++;
                continue;
            }
            try {
                JSONObject cleanObj = new JSONObject();
                cleanObj.put("src", src);
                cleanObj.put("tgt", tgt);
                JSONArray alts = item.optJSONArray("alts");
                if (alts != null && alts.length() > 0) {
                    cleanObj.put("alts", alts);
                }
                validEntries.put(cleanObj);
            } catch (JSONException ignored) {
                invalidCount++;
            }
        }

        if (validEntries.length() == 0) {
            result.errorMessage = "لم يتم العثور على أي عبارات ترجمة صالحة داخل الملف.";
            return result;
        }

        LibraryMeta meta = new LibraryMeta();
        meta.id = "lib_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        meta.name = name;
        meta.description = description;
        meta.sourceLang = sourceLang;
        meta.targetLang = targetLang;
        meta.bidirectional = bidirectional;
        meta.enabled = true;
        meta.entryCount = validEntries.length();
        meta.importedAt = System.currentTimeMillis();

        synchronized (mLock) {
            try {
                JSONObject storePayload = new JSONObject();
                storePayload.put("schemaVersion", SCHEMA_VERSION);
                storePayload.put("id", meta.id);
                storePayload.put("name", meta.name);
                storePayload.put("description", meta.description);
                storePayload.put("sourceLang", meta.sourceLang);
                storePayload.put("targetLang", meta.targetLang);
                storePayload.put("bidirectional", meta.bidirectional);
                storePayload.put("entries", validEntries);

                writeUtf8Atomic(getLibraryFile(meta.id), storePayload.toString());
                mLibraries.add(0, meta);
                persistIndexLocked();
                rebuildMergedIndexLocked();
        com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();

                result.success = true;
                result.meta = meta.copy();
                result.importedEntries = validEntries.length();
                result.skippedInvalid = invalidCount;
            } catch (Throwable e) {
                result.success = false;
                result.errorMessage = "تعذر حفظ المكتبة محلياً: " + e.getMessage();
            }
        }
        return result;
    }

    public boolean setLibraryEnabled(final String libraryId, final boolean enabled) {
        ensureLoaded();
        synchronized (mLock) {
            for (int i = 0; i < mLibraries.size(); i++) {
                LibraryMeta m = mLibraries.get(i);
                if (m.id.equals(libraryId)) {
                    if (m.enabled != enabled) {
                        m.enabled = enabled;
                        persistIndexLocked();
                        rebuildMergedIndexLocked();
        com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    public boolean deleteLibrary(final String libraryId) {
        ensureLoaded();
        synchronized (mLock) {
            for (int i = 0; i < mLibraries.size(); i++) {
                if (mLibraries.get(i).id.equals(libraryId)) {
                    mLibraries.remove(i);
                    File f = getLibraryFile(libraryId);
                    if (f.exists()) f.delete();
                    persistIndexLocked();
                    rebuildMergedIndexLocked();
        com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Translates text offline using ALL enabled imported libraries matching (srcLang -> tgtLang).
     * Supports "auto" source language by detecting Arabic vs Latin script automatically.
     * Supports:
     * 1) Exact / normalized full-phrase lookup across all enabled libraries for that language pair.
     * 2) Compositional multi-word / sub-phrase lookup if the full sentence isn't stored verbatim.
     */
    public OfflineTranslationHit translateOffline(
            final String rawText,
            final String sourceLang,
            final String targetLang
    ) {
        if (rawText == null || rawText.trim().isEmpty()) return null;
        ensureLoaded();

        String tgt = normalizeLang(targetLang);
        String src = normalizeLang(sourceLang);
        if ("auto".equals(src) || src.isEmpty()) {
            src = isMostlyArabic(rawText) ? "ar" : "en";
        }
        if (src.equals(tgt)) return null;

        String pairKey = src + "->" + tgt;
        String normFull = normalizeLookupText(rawText);
        if (normFull.isEmpty()) return null;

        synchronized (mLock) {
            Map<String, List<IndexedPair>> dict = mPairIndex.get(pairKey);
            if (dict == null || dict.isEmpty()) {
                return null;
            }

            // 1. Exact full-phrase match (merges alternatives from all enabled libraries for this pair)
            List<IndexedPair> exactHits = dict.get(normFull);
            if (exactHits != null && !exactHits.isEmpty()) {
                IndexedPair primaryHit = exactHits.get(0);
                Set<String> altSet = new LinkedHashSet<>();
                for (int i = 0; i < exactHits.size(); i++) {
                    IndexedPair p = exactHits.get(i);
                    if (i > 0 && !p.translation.equalsIgnoreCase(primaryHit.translation)) {
                        altSet.add(p.translation);
                    }
                    if (p.alts != null) {
                        for (int j = 0; j < p.alts.size(); j++) {
                            String a = p.alts.get(j);
                            if (!a.equalsIgnoreCase(primaryHit.translation)) {
                                altSet.add(a);
                            }
                        }
                    }
                }
                List<String> altsOut = new ArrayList<>(altSet);
                if (altsOut.size() > 3) altsOut = altsOut.subList(0, 3);
                return new OfflineTranslationHit(primaryHit.translation, altsOut, primaryHit.libraryName, true);
            }

            // 2. Greedy longest-phrase compositional lookup (up to 4-word windows, then single words)
            String[] tokens = normFull.split("\\s+");
            if (tokens.length <= 1) {
                return null;
            }
            StringBuilder assembled = new StringBuilder();
            int matchedTokens = 0;
            String lastLib = "";
            int idx = 0;
            while (idx < tokens.length) {
                boolean matched = false;
                int maxWin = Math.min(4, tokens.length - idx);
                for (int win = maxWin; win >= 1; win--) {
                    StringBuilder chunk = new StringBuilder();
                    for (int k = 0; k < win; k++) {
                        if (k > 0) chunk.append(' ');
                        chunk.append(tokens[idx + k]);
                    }
                    List<IndexedPair> hitList = dict.get(chunk.toString());
                    if (hitList != null && !hitList.isEmpty()) {
                        if (assembled.length() > 0) assembled.append(' ');
                        assembled.append(hitList.get(0).translation);
                        lastLib = hitList.get(0).libraryName;
                        matchedTokens += win;
                        idx += win;
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    if (assembled.length() > 0) assembled.append(' ');
                    assembled.append(tokens[idx]);
                    idx++;
                }
            }
            // Require 100% token coverage so half-translated hybrids like "what اسمو" are NEVER returned!
            if (matchedTokens == tokens.length && matchedTokens > 0) {
                return new OfflineTranslationHit(assembled.toString().trim(), Collections.<String>emptyList(), lastLib, false);
            }
        }
        return null;
    }

    /**
     * Extracts matching idioms and vocabulary hints from ALL enabled user libraries for any unseen
     * sentence (4-word -> 1-word longest-match scan) so Gemini API translates with exact user-library
     * Syrian dialect meanings and terminology (RAG-style hybrid translation).
     */
    public List<String> findContextualGlossaryHints(
            final String rawText,
            final String sourceLang,
            final String targetLang,
            final int maxHints
    ) {
        if (rawText == null || rawText.trim().isEmpty() || maxHints <= 0) {
            return Collections.emptyList();
        }
        ensureLoaded();
        String tgt = normalizeLang(targetLang);
        String src = normalizeLang(sourceLang);
        if ("auto".equals(src) || src.isEmpty()) {
            src = isMostlyArabic(rawText) ? "ar" : "en";
        }
        if (src.equals(tgt)) return Collections.emptyList();

        String pairKey = src + "->" + tgt;
        String normFull = normalizeLookupText(rawText);
        if (normFull.isEmpty()) return Collections.emptyList();

        String[] tokens = normFull.split("\\s+");
        if (tokens.length == 0) return Collections.emptyList();

        List<String> hints = new ArrayList<>();
        Set<String> seenChunks = new LinkedHashSet<>();

        synchronized (mLock) {
            Map<String, List<IndexedPair>> dict = mPairIndex.get(pairKey);
            if (dict == null || dict.isEmpty()) {
                return Collections.emptyList();
            }
            int idx = 0;
            while (idx < tokens.length && hints.size() < maxHints) {
                boolean matched = false;
                int maxWin = Math.min(4, tokens.length - idx);
                for (int win = maxWin; win >= 1; win--) {
                    StringBuilder chunk = new StringBuilder();
                    for (int k = 0; k < win; k++) {
                        if (k > 0) chunk.append(' ');
                        chunk.append(tokens[idx + k]);
                    }
                    String key = chunk.toString();
                    List<IndexedPair> hitList = dict.get(key);
                    if (hitList != null && !hitList.isEmpty()) {
                        if (seenChunks.add(key)) {
                            hints.add(key + " = " + hitList.get(0).translation);
                        }
                        idx += win;
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    idx++;
                }
            }
        }
        return hints;
    }

    private void rebuildMergedIndexLocked() {
        mPairIndex.clear();
        for (int i = 0; i < mLibraries.size(); i++) {
            LibraryMeta meta = mLibraries.get(i);
            if (!meta.enabled) continue;
            File libFile = getLibraryFile(meta.id);
            if (!libFile.exists()) continue;

            try {
                String raw = readUtf8(libFile);
                JSONObject root = new JSONObject(raw);
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) continue;

                String forwardKey = meta.sourceLang + "->" + meta.targetLang;
                String reverseKey = meta.targetLang + "->" + meta.sourceLang;

                Map<String, List<IndexedPair>> forwardMap = getOrCreatePairMapLocked(forwardKey);
                Map<String, List<IndexedPair>> reverseMap = meta.bidirectional
                        ? getOrCreatePairMapLocked(reverseKey)
                        : null;

                for (int j = 0; j < entries.length(); j++) {
                    JSONObject item = entries.optJSONObject(j);
                    if (item == null) continue;
                    String src = item.optString("src", "").trim();
                    String tgt = item.optString("tgt", "").trim();
                    if (src.isEmpty() || tgt.isEmpty()) continue;

                    List<String> alts = new ArrayList<>();
                    JSONArray altsArr = item.optJSONArray("alts");
                    if (altsArr != null) {
                        for (int k = 0; k < altsArr.length(); k++) {
                            String a = altsArr.optString(k, "").trim();
                            if (!a.isEmpty()) alts.add(a);
                        }
                    }

                    String normSrc = normalizeLookupText(src);
                    if (!normSrc.isEmpty()) {
                        addToPairMap(forwardMap, normSrc, new IndexedPair(tgt, alts, meta.name));
                    }

                    if (reverseMap != null) {
                        String normTgt = normalizeLookupText(tgt);
                        if (!normTgt.isEmpty()) {
                            addToPairMap(reverseMap, normTgt, new IndexedPair(src, Collections.<String>emptyList(), meta.name));
                        }
                    }
                }
            } catch (Throwable e) {
                try {
                    Log.w(TAG, "Error indexing offline library " + meta.id, e);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Map<String, List<IndexedPair>> getOrCreatePairMapLocked(final String pairKey) {
        Map<String, List<IndexedPair>> map = mPairIndex.get(pairKey);
        if (map == null) {
            map = new HashMap<>();
            mPairIndex.put(pairKey, map);
        }
        return map;
    }

    private void addToPairMap(final Map<String, List<IndexedPair>> map, final String key, final IndexedPair pair) {
        List<IndexedPair> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>(2);
            map.put(key, list);
        }
        list.add(pair);
    }

    private void persistIndexLocked() {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            root.put("updatedAt", iso.format(new Date()));

            JSONArray arr = new JSONArray();
            for (int i = 0; i < mLibraries.size(); i++) {
                LibraryMeta m = mLibraries.get(i);
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("name", m.name);
                o.put("description", m.description);
                o.put("sourceLang", m.sourceLang);
                o.put("targetLang", m.targetLang);
                o.put("bidirectional", m.bidirectional);
                o.put("enabled", m.enabled);
                o.put("entryCount", m.entryCount);
                o.put("importedAt", m.importedAt);
                arr.put(o);
            }
            root.put("libraries", arr);
            writeUtf8Atomic(mIndexFile, root.toString(2));
        } catch (Throwable e) {
            try {
                Log.e(TAG, "Failed to persist offline libraries index", e);
            } catch (Throwable ignored) {
            }
        }
    }

    private File getLibraryFile(final String libraryId) {
        return new File(mRootDir, libraryId + ".json");
    }

    public static String normalizeLookupText(final String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // Strip Arabic tashkeel / diacritics
        s = s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "");
        // Normalize Arabic Alef / Teh Marbuta / Alef Maksura for resilient offline matching
        s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ة', 'ه').replace('ى', 'ي');
        // Collapse chat elongations (e.g. "مرحباااا" -> "مرحبا", "كتيييير" -> "كتير")
        s = s.replaceAll("(.)\\1{2,}", "$1");
        // Strip surrounding/redundant punctuation
        s = s.replaceAll("[?!؟.,،;:()\"'\\[\\]{}]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static String normalizeLang(final String code) {
        if (code == null || code.trim().isEmpty()) return "ar";
        return code.trim().toLowerCase(Locale.US).split("[-_]")[0];
    }

    private static boolean isMostlyArabic(final String text) {
        int ar = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                if (c >= 0x0600 && c <= 0x06FF) ar++;
                else other++;
            }
        }
        return ar > other;
    }

    private static String readUtf8(final File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static void writeUtf8Atomic(final File target, final String content) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(content);
            osw.flush();
            fos.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(content);
                osw.flush();
            }
            tmp.delete();
        }
    }
}
