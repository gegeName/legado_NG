import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import cn.hutool.crypto.symmetric.SymmetricCrypto;
import com.google.gson.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.htmlunit.corejs.javascript.*;

/** Isolated desktop audit. Uses NG's vendored JS engine and cached Hutool;
 * does NOT execute Android AnalyzeUrl, Room, routing or playback services. */
public class XfBackupAudit {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Path OUT = Path.of("build/tts-multitts-audit");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    static boolean live;
    static String key;
    static int signRequests;
    public static class Bridge {
        public String md5Encode(String text) throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        public SymmetricCrypto createSymmetricCrypto(String transformation, String k) {
            return new SymmetricCrypto(transformation, new javax.crypto.spec.SecretKeySpec(k.getBytes(StandardCharsets.UTF_8), "AES"));
        }
        public String ajax(String expression, long timeout) throws Exception {
            int sep = expression.indexOf(',');
            String url = expression.substring(0, sep);
            JsonObject opt = JsonParser.parseString(expression.substring(sep + 1)).getAsJsonObject();
            if (!url.equals("https://peiyin.xunfei.cn/web-server/1.0/works_synth_sign"))
                throw new IllegalArgumentException("Unexpected sign endpoint");
            String body = opt.get("body").getAsString();
            SymmetricCrypto crypto = createSymmetricCrypto("AES/ECB/PKCS5Padding", key);
            String hash = crypto.decryptStr(JsonParser.parseString(body).getAsJsonObject().get("req").getAsString());
            if (!JsonParser.parseString(hash).getAsJsonObject().get("synth_text_hash_code").getAsString().matches("[a-f0-9]{32}"))
                throw new IllegalStateException("Invalid signed text hash");
            if (!live) return GSON.toJson(Map.of("body", crypto.encryptBase64("{\"time_stamp\":\"123456\",\"sign_text\":\"audit-placeholder\"}")));
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeout));
            opt.getAsJsonObject("headers").entrySet().forEach(e -> builder.header(e.getKey(), e.getValue().getAsString()));
            var response = HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            signRequests++;
            System.out.println("sign status=" + response.statusCode() + " content-type=" + response.headers().firstValue("content-type").orElse("") + " chars=" + response.body().length());
            if (response.statusCode() != 200) throw new IOException("Sign HTTP " + response.statusCode());
            JsonObject parsed;
            try { parsed = JsonParser.parseString(response.body()).getAsJsonObject(); }
            catch (Exception e) { throw new IOException("Sign returned non-JSON"); }
            if (!parsed.has("body") || parsed.get("body").isJsonNull()) {
                // Only report provider error fields, never credentials or signatures.
                var safe = new LinkedHashMap<String, Object>();
                for (String field : List.of("code", "msg", "message", "retcode", "desc"))
                    if (parsed.has(field)) safe.put(field, parsed.get(field));
                System.out.println("sign-error=" + GSON.toJson(safe));
                throw new IOException("Sign response lacks encrypted body");
            }
            return response.body();
        }
    }
    static String read(ZipFile z, String name) throws Exception {
        var entry = z.getEntry(name);
        if (entry == null || entry.getSize() > 2_000_000) throw new IOException("Invalid YAML entry");
        try (var input = z.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IOException("YAML too large");
            return new String(bytes, StandardCharsets.UTF_8).replace("\ufeff", "");
        }
    }
    static Object parse(String yaml) {
        // Remove only known MultiTTS bean tags; never instantiate arbitrary YAML classes.
        yaml = yaml.replace("!!org.nobody.multitts.tts.engine.EngineConfig", "")
            .replace("!!org.nobody.multitts.tts.speaker.Speaker", "");
        var settings = new LoaderOptions();
        settings.setAllowDuplicateKeys(false);
        settings.setMaxAliasesForCollections(20);
        settings.setCodePointLimit(2_000_000);
        return new Yaml(new SafeConstructor(settings)).load(yaml);
    }
    static String str(Object o) { return o == null ? "" : o.toString(); }
    static Number factor(Map<?,?> m, String field) { return m.get(field) instanceof Number n ? n : 1.0; }
    static JsonObject request(Context cx, TopLevel scope, JsonObject voice, String style, int speed) {
        JsonObject arg = voice.deepCopy();
        if (style != null) arg.addProperty("style_value", style);
        String js = "JSON.stringify(synthesize('你好，这是一段在线音色验证。'," + GSON.toJson(arg)
            + ",{speed:" + speed + ",volume:50,pitch:50},{},{}))";
        return JsonParser.parseString(Context.toString(cx.evaluateString(scope, js, "audit-call", 1, null))).getAsJsonObject();
    }
    public static void main(String[] args) throws Exception {
        live = Arrays.asList(args).contains("--live");
        Files.createDirectories(OUT);
        List<JsonObject> voices = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String,Integer> gender = new TreeMap<>(), rates = new TreeMap<>(), types = new TreeMap<>();
        Set<String> codes = new HashSet<>(), vids = new HashSet<>();
        int direct = 0, refs = 0, candidates = 0, avatarCount = 0;
        Object engines;
        Path zip = Path.of(args[0]);
        String zipHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        try (var z = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            engines = parse(read(z, "engines.yaml"));
            Map<?,?> config = (Map<?,?>)parse(read(z, "config.yaml"));
            if (!config.keySet().equals(Set.of("xfpeiyin"))) throw new IllegalArgumentException("Unexpected provider");
            List<?> speakers = (List<?>)config.get("xfpeiyin");
            for (Object item : speakers) {
                Map<?,?> s = (Map<?,?>)item;
                String code = str(s.get("code")), param = str(s.get("param")).trim();
                if (!codes.add(code)) errors.add("duplicate code " + code);
                gender.merge(str(s.get("gender")), 1, Integer::sum);
                rates.merge(str(s.get("sampleRate")), 1, Integer::sum);
                types.merge(str(s.get("type")), 1, Integer::sum);
                if (!str(s.get("avatar")).isEmpty()) avatarCount++;
                JsonObject v = new JsonObject();
                v.addProperty("id", "xfpeiyin:" + code); v.addProperty("name", str(s.get("name")));
                v.addProperty("language", str(s.get("locale")));
                v.addProperty("gender", str(s.get("gender")).equals("1") ? "male" : str(s.get("gender")).equals("0") ? "female" : "unknown");
                JsonObject extra = new JsonObject();
                extra.addProperty("provider", "xfpeiyin"); extra.addProperty("sourceCode", code);
                extra.addProperty("speed", factor(s,"speed")); extra.addProperty("volume", factor(s,"volume"));
                extra.addProperty("pitch", factor(s,"pitch")); extra.add("original", GSON.toJsonTree(s));
                String vid = param.split(",", -1)[0].trim();
                JsonArray styles = new JsonArray();
                if (vid.startsWith("@")) {
                    refs++;
                    String ref = vid.substring(1); vid = "";
                    try {
                        JsonArray ui = JsonParser.parseString(str(s.get("extendUI"))).getAsJsonArray();
                        for (JsonElement field : ui) {
                            JsonObject f = field.getAsJsonObject();
                            if (!f.has("code") || !f.get("code").getAsString().equals(ref)) continue;
                            vid = f.get("value").getAsString();
                            for (var candidate : f.getAsJsonObject("candidate").entrySet()) {
                                JsonObject style = new JsonObject();
                                style.addProperty("id", candidate.getKey()); style.addProperty("value", candidate.getKey());
                                style.addProperty("name", candidate.getValue().getAsString()); styles.add(style);
                                if (!candidate.getKey().matches("[0-9]+")) errors.add("non-numeric candidate " + code);
                                vids.add(candidate.getKey()); candidates++;
                            }
                        }
                    } catch (Exception e) { errors.add("extendUI " + code + ": " + e.getClass().getSimpleName()); }
                } else direct++;
                if (!vid.matches("[0-9]+")) errors.add("unresolved ID " + code);
                vids.add(vid); extra.addProperty("voiceCode", vid); extra.add("styles", styles);
                v.add("extra", extra); voices.add(v);
            }
        }
        var summary = new LinkedHashMap<String,Object>();
        summary.put("zipSha256", zipHash); summary.put("engines", engines); summary.put("speakers", voices.size());
        summary.put("numericParam", direct); summary.put("styleReferences", refs); summary.put("styleCandidates", candidates);
        summary.put("distinctProviderIdsIncludingStyles", vids.size()); summary.put("gender", gender); summary.put("sampleRate", rates);
        summary.put("type", types); summary.put("avatarReferences", avatarCount); summary.put("errors", errors);
        Files.writeString(OUT.resolve("backup-summary.json"), GSON.toJson(summary), StandardCharsets.UTF_8);
        Files.writeString(OUT.resolve("voice-catalog.json"), GSON.toJson(voices), StandardCharsets.UTF_8);
        System.out.println(GSON.toJson(summary));
        if (!errors.isEmpty()) throw new IllegalStateException("Backup errors");
        String source = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("XFPEIYIN_AES_KEY = \"((?:\\\\.|[^\"\\\\])*)\"").matcher(source);
        if (!m.find()) throw new IllegalStateException("Reference key missing");
        key = JsonParser.parseString("\"" + m.group(1) + "\"").getAsString();
        try (Context cx = Context.enter()) {
            cx.setOptimizationLevel(-1); cx.setLanguageVersion(Context.VERSION_ES6);
            TopLevel scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "java", Context.javaToJS(new Bridge(), scope));
            ScriptableObject.putProperty(scope, "AUDIT_KEY", key);
            cx.evaluateString(scope, Files.readString(Path.of("docs/audits/multitts/XfProtocolProbe.js")), "xf-probe", 1, null);
            int checks = 0;
            for (JsonObject v : voices) {
                var req = request(cx, scope, v, null, 50);
                if (!req.get("url").getAsString().contains("vid=" + v.getAsJsonObject("extra").get("voiceCode").getAsString()))
                    throw new IllegalStateException("Default ID mismatch");
                checks++;
                for (var style : v.getAsJsonObject("extra").getAsJsonArray("styles")) {
                    String id = style.getAsJsonObject().get("value").getAsString();
                    if (!request(cx, scope, v, id, 50).get("url").getAsString().contains("vid=" + id))
                        throw new IllegalStateException("Style ID mismatch");
                    checks++;
                }
                // Offline matrix only; live uses explicit representative samples below.
                if (live) break;
            }
            System.out.println("script request checks=" + checks + " mode=" + (live ? "LIVE" : "MOCK-SIGN"));
            if (!live) return;
            var samples = List.of(new String[]{"ningning_16", ""}, new String[]{"xiaofang", ""},
                new String[]{"tianming", ""}, new String[]{"tianming", "130005"},
                new String[]{"chuge", ""}, new String[]{"tianming_16", "65271"});
            for (String[] sample : samples) {
                String code = sample[0], style = sample[1].isEmpty() ? null : sample[1];
                JsonObject v = voices.stream().filter(x -> x.getAsJsonObject("extra").get("sourceCode").getAsString().equals(code)).findFirst().orElseThrow();
                var req = request(cx, scope, v, style, 50);
                var builder = HttpRequest.newBuilder(URI.create(req.get("url").getAsString())).timeout(Duration.ofSeconds(25));
                req.getAsJsonObject("headers").entrySet().forEach(e -> builder.header(e.getKey(), e.getValue().getAsString()));
                var response = HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                byte[] bytes = response.body();
                String contentType = response.headers().firstValue("content-type").orElse("");
                String header = HexFormat.of().formatHex(Arrays.copyOf(bytes, Math.min(16,bytes.length)));
                System.out.println("audio voice=" + code + " style=" + style + " status=" + response.statusCode() + " type=" + contentType + " bytes=" + bytes.length + " header=" + header);
                if (response.statusCode()!=200 || contentType.contains("json") || contentType.startsWith("text/") || bytes.length<1000)
                    throw new IOException("No usable audio for " + code);
                Files.write(OUT.resolve(code + (style == null ? "" : "_style_" + style) + ".audio"), bytes);
            }
        }
    }
}
