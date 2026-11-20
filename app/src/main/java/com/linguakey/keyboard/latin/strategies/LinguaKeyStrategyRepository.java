package com.linguakey.keyboard.latin.strategies;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Modular JSON Smart Strategies Engine for LinguaKey (Schema v1).
 *
 * Allows users to import one or multiple JSON Strategy Packs (such as Smart Contextual Punctuation,
 * Formal Business Tone, Concise Replies, etc.) that enhance BOTH:
 * 1. Offline/Instant Local Post-Processing (<0.1ms): contextual commas, question marks, exclamation marks,
 *    English capitalization, and spacing cleanup.
 * 2. Gemini API AI Prompt Directives: automatically injects each enabled strategy's `aiInstruction`
 *    into live typing and screen translation prompts.
 */
public final class LinguaKeyStrategyRepository {
    private static final String TAG = "LKStrategyRepo";
    public static final int SCHEMA_VERSION = 1;
    private static final String DIR_NAME = "smart_strategies";
    private static final String INDEX_FILE = "strategies_index.json";

    public static final class StrategyPack {
        public String id;
        public String name;
        public String description;
        public String category;
        public String aiInstruction;
        public boolean enabled;
        public boolean autoQuestionMark;
        public boolean autoComma;
        public boolean autoExclamation;
        public boolean autoCapitalizeEnglish;
        public boolean cleanSpacing;
        public final List<String> questionTriggersAr = new ArrayList<>();
        public final List<String> questionTriggersEn = new ArrayList<>();
        public final List<String> commaBeforeAr = new ArrayList<>();
        public final List<String> commaAfterAr = new ArrayList<>();
        public final List<String> commaBeforeEn = new ArrayList<>();
        public final List<String> commaAfterEn = new ArrayList<>();
        public final List<String> exclamationTriggers = new ArrayList<>();

        public int getRuleCount() {
            return questionTriggersAr.size() + questionTriggersEn.size()
                    + commaBeforeAr.size() + commaAfterAr.size()
                    + commaBeforeEn.size() + commaAfterEn.size()
                    + exclamationTriggers.size();
        }
    }

    public static final class StrategyImportResult {
        public boolean success = false;
        public String errorMessage = null;
        public StrategyPack pack = null;
    }

    private static volatile LinguaKeyStrategyRepository sInstance;

    private final File mRootDir;
    private final File mIndexFile;
    private final Object mLock = new Object();
    private final List<StrategyPack> mPacks = new ArrayList<>();
    private boolean mLoaded = false;

    public static LinguaKeyStrategyRepository getInstance(final Context context) {
        if (sInstance == null) {
            synchronized (LinguaKeyStrategyRepository.class) {
                if (sInstance == null) {
                    File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
                    sInstance = new LinguaKeyStrategyRepository(dir);
                }
            }
        }
        return sInstance;
    }

    public LinguaKeyStrategyRepository(final File rootDir) {
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
            mPacks.clear();
            if (mIndexFile.exists() && mIndexFile.length() > 0L) {
                try {
                    JSONObject root = new JSONObject(readUtf8(mIndexFile));
                    JSONArray arr = root.optJSONArray("strategies");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            StrategyPack p = parsePackObject(o);
                            if (p != null) {
                                mPacks.add(p);
                            }
                        }
                    }
                } catch (Throwable e) {
                    try {
                        Log.e(TAG, "Error loading strategies", e);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    public List<StrategyPack> getStrategies() {
        ensureLoaded();
        synchronized (mLock) {
            return new ArrayList<>(mPacks);
        }
    }

    public StrategyImportResult importStrategyJson(final String rawJson, final String fallbackName) {
        ensureLoaded();
        StrategyImportResult res = new StrategyImportResult();
        if (rawJson == null || rawJson.trim().isEmpty()) {
            res.errorMessage = "الملف فارغ ولا يحتوي على بيانات JSON صالحة.";
            return res;
        }
        String cleaned = rawJson.trim();
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1).trim();
        }

        JSONObject root;
        try {
            root = new JSONObject(cleaned);
        } catch (JSONException e) {
            res.errorMessage = "صيغة ملف JSON غير صالحة: " + e.getMessage();
            return res;
        }

        String type = root.optString("type", "strategy").trim();
        if (!"strategy".equalsIgnoreCase(type) && !root.has("rules") && !root.has("aiInstruction")) {
            res.errorMessage = "هذا الملف لا يتبع بنية استراتيجية LinguaKey (يجب أن يحتوي على rules أو aiInstruction).";
            return res;
        }

        StrategyPack pack = parsePackObject(root);
        if (pack == null) {
            res.errorMessage = "تعذر قراءة محتوى الاستراتيجية.";
            return res;
        }
        if (pack.name == null || pack.name.trim().isEmpty()) {
            pack.name = (fallbackName != null && !fallbackName.trim().isEmpty()) ? fallbackName.trim() : "استراتيجية ذكية";
        }
        pack.id = "strat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        pack.enabled = true;

        synchronized (mLock) {
            mPacks.add(0, pack);
            persistLocked();
        }
        com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
        res.success = true;
        res.pack = pack;
        return res;
    }

    public boolean setStrategyEnabled(final String id, final boolean enabled) {
        ensureLoaded();
        boolean changed = false;
        synchronized (mLock) {
            for (int i = 0; i < mPacks.size(); i++) {
                StrategyPack p = mPacks.get(i);
                if (p.id.equals(id)) {
                    if (p.enabled != enabled) {
                        p.enabled = enabled;
                        persistLocked();
                        changed = true;
                    }
                    break;
                }
            }
        }
        if (changed) {
            com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
        }
        return changed;
    }

    public boolean deleteStrategy(final String id) {
        ensureLoaded();
        boolean removed = false;
        synchronized (mLock) {
            for (int i = 0; i < mPacks.size(); i++) {
                if (mPacks.get(i).id.equals(id)) {
                    mPacks.remove(i);
                    persistLocked();
                    removed = true;
                    break;
                }
            }
        }
        if (removed) {
            com.linguakey.keyboard.latin.LiveTranslationEngine.clearTranslationCache();
        }
        return removed;
    }

    /**
     * Applies all enabled local strategy rules (smart commas, contextual question marks,
     * exclamation marks, English capitalization, spacing cleanup) in < 0.1ms.
     */
    public String applyLocalStrategies(final String rawText, final String targetLang) {
        if (rawText == null || rawText.trim().isEmpty()) return rawText;
        ensureLoaded();

        List<StrategyPack> active;
        synchronized (mLock) {
            if (mPacks.isEmpty()) return rawText;
            active = new ArrayList<>();
            for (int i = 0; i < mPacks.size(); i++) {
                if (mPacks.get(i).enabled) active.add(mPacks.get(i));
            }
        }
        if (active.isEmpty()) return rawText;

        String result = rawText.trim();
        boolean isArabicTarget = "ar".equalsIgnoreCase(targetLang) || isMostlyArabic(result);

        for (int i = 0; i < active.size(); i++) {
            StrategyPack p = active.get(i);

            if (p.cleanSpacing) {
                result = result.replaceAll("\\s{2,}", " ")
                        .replaceAll("\\s+([,.?!؟،;:])", "$1")
                        .trim();
            }

            if (p.autoComma) {
                if (isArabicTarget) {
                    result = applyCommaRules(result, p.commaBeforeAr, p.commaAfterAr, "،");
                } else {
                    result = applyCommaRules(result, p.commaBeforeEn, p.commaAfterEn, ",");
                }
            }

            if (p.autoCapitalizeEnglish && !isArabicTarget && result.length() > 0) {
                char first = result.charAt(0);
                if (Character.isLowerCase(first)) {
                    result = Character.toUpperCase(first) + result.substring(1);
                }
                result = result.replaceAll("\\bi\\b", "I")
                        .replaceAll("\\bi'(m|ve|ll|d)\\b", "I'$1");
            }

            if (p.autoQuestionMark && !endsWithTerminalPunctuation(result)) {
                String lower = result.toLowerCase(Locale.ROOT);
                List<String> qList = isArabicTarget ? p.questionTriggersAr : p.questionTriggersEn;
                if (startsWithAnyTrigger(lower, qList)) {
                    result = result + (isArabicTarget ? "؟" : "?");
                }
            }

            if (p.autoExclamation && !endsWithTerminalPunctuation(result)) {
                String lower = result.toLowerCase(Locale.ROOT);
                if (startsWithAnyTrigger(lower, p.exclamationTriggers)) {
                    result = result + "!";
                }
            }
        }
        return result;
    }

    /**
     * Collects AI instructions from all enabled strategies for injection into Gemini API prompts.
     */
    public String buildAiStrategyPromptSection() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder();
        synchronized (mLock) {
            for (int i = 0; i < mPacks.size(); i++) {
                StrategyPack p = mPacks.get(i);
                if (p.enabled && p.aiInstruction != null && !p.aiInstruction.trim().isEmpty()) {
                    if (sb.length() == 0) {
                        sb.append("Active user writing strategies (follow strictly): ");
                    } else {
                        sb.append(" ");
                    }
                    sb.append(p.aiInstruction.trim());
                }
            }
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String applyCommaRules(
            final String text,
            final List<String> beforeTriggers,
            final List<String> afterTriggers,
            final String commaChar
    ) {
        String out = text;
        // Insert comma BEFORE mid-sentence conjunctions (e.g., "I like it, but ..." / "جميل، بس ...")
        for (int i = 0; i < beforeTriggers.size(); i++) {
            String trig = beforeTriggers.get(i);
            if (trig == null || trig.isEmpty()) continue;
            String escaped = java.util.regex.Pattern.quote(trig);
            // Match only when preceded by a word character + space (not at start of sentence, not already after comma)
            out = out.replaceAll("(?i)(?<=\\S[^,،?!؟.])\\s+(" + escaped + ")(\\s+)", commaChar + " $1$2");
        }
        // Insert comma AFTER introductory discourse markers at start of sentence (e.g., "Honestly, ..." / "بصراحة، ...")
        for (int i = 0; i < afterTriggers.size(); i++) {
            String trig = afterTriggers.get(i);
            if (trig == null || trig.isEmpty()) continue;
            String escaped = java.util.regex.Pattern.quote(trig);
            out = out.replaceAll("(?i)^(" + escaped + ")(\\s+)(?![,،?!؟.])", "$1" + commaChar + "$2");
        }
        return out;
    }

    private static boolean startsWithAnyTrigger(final String lowerText, final List<String> triggers) {
        for (int i = 0; i < triggers.size(); i++) {
            String t = triggers.get(i);
            if (t == null || t.isEmpty()) continue;
            String lt = t.toLowerCase(Locale.ROOT);
            if (lowerText.equals(lt) || lowerText.startsWith(lt + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithTerminalPunctuation(final String s) {
        if (s == null || s.isEmpty()) return false;
        char last = s.charAt(s.length() - 1);
        return last == '?' || last == '؟' || last == '!' || last == '.' || last == '…';
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

    private StrategyPack parsePackObject(final JSONObject o) {
        StrategyPack p = new StrategyPack();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "").trim();
        p.description = o.optString("description", "").trim();
        p.category = o.optString("category", "عام").trim();
        p.aiInstruction = o.optString("aiInstruction", "").trim();
        p.enabled = o.optBoolean("enabled", true);

        JSONObject rules = o.optJSONObject("rules");
        if (rules != null) {
            p.autoQuestionMark = rules.optBoolean("autoQuestionMark", false);
            p.autoComma = rules.optBoolean("autoComma", false);
            p.autoExclamation = rules.optBoolean("autoExclamation", false);
            p.autoCapitalizeEnglish = rules.optBoolean("autoCapitalizeEnglish", false);
            p.cleanSpacing = rules.optBoolean("cleanSpacing", true);
            readStringArray(rules.optJSONArray("questionTriggersAr"), p.questionTriggersAr);
            readStringArray(rules.optJSONArray("questionTriggersEn"), p.questionTriggersEn);
            readStringArray(rules.optJSONArray("commaBeforeAr"), p.commaBeforeAr);
            readStringArray(rules.optJSONArray("commaAfterAr"), p.commaAfterAr);
            readStringArray(rules.optJSONArray("commaBeforeEn"), p.commaBeforeEn);
            readStringArray(rules.optJSONArray("commaAfterEn"), p.commaAfterEn);
            readStringArray(rules.optJSONArray("exclamationTriggers"), p.exclamationTriggers);
        }
        return p;
    }

    private void readStringArray(final JSONArray arr, final List<String> dest) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) dest.add(s);
        }
    }

    private void persistLocked() {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < mPacks.size(); i++) {
                StrategyPack p = mPacks.get(i);
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("type", "strategy");
                o.put("name", p.name);
                o.put("description", p.description);
                o.put("category", p.category);
                o.put("aiInstruction", p.aiInstruction);
                o.put("enabled", p.enabled);

                JSONObject r = new JSONObject();
                r.put("autoQuestionMark", p.autoQuestionMark);
                r.put("autoComma", p.autoComma);
                r.put("autoExclamation", p.autoExclamation);
                r.put("autoCapitalizeEnglish", p.autoCapitalizeEnglish);
                r.put("cleanSpacing", p.cleanSpacing);
                r.put("questionTriggersAr", new JSONArray(p.questionTriggersAr));
                r.put("questionTriggersEn", new JSONArray(p.questionTriggersEn));
                r.put("commaBeforeAr", new JSONArray(p.commaBeforeAr));
                r.put("commaAfterAr", new JSONArray(p.commaAfterAr));
                r.put("commaBeforeEn", new JSONArray(p.commaBeforeEn));
                r.put("commaAfterEn", new JSONArray(p.commaAfterEn));
                r.put("exclamationTriggers", new JSONArray(p.exclamationTriggers));
                o.put("rules", r);
                arr.put(o);
            }
            root.put("strategies", arr);
            writeUtf8Atomic(mIndexFile, root.toString(2));
        } catch (Throwable e) {
            try {
                Log.e(TAG, "Error persisting strategies", e);
            } catch (Throwable ignored) {
            }
        }
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
