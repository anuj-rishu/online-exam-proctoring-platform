package in.srminsider.labs.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The CSP has silently broken production three times: identity photos loaded
 * from R2 (missing in img-src), WebRTC's STUN/TURN connections (missing in
 * connect-src), and the CodeMirror exam IDE (its stylesheet host was allowed
 * in script-src but not style-src, so students could not type code).
 *
 * A CSP violation produces no server-side error at all — the browser just
 * refuses to load the resource — so nothing in the build catches it. This
 * walks every static page, finds the external hosts each one actually loads,
 * and asserts the served CSP permits them in the matching directive.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentSecurityPolicyCoversStaticAssetsTest {

    private static final Path STATIC_ROOT = Paths.get("src/main/resources/static");

    /** <script src="https://host/..."> */
    private static final Pattern SCRIPT_SRC =
            Pattern.compile("<script[^>]+src=\"(https://[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** Any <link> tag, so href/rel can appear in either order. */
    private static final Pattern LINK_TAG =
            Pattern.compile("<link[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern HREF = Pattern.compile("href=\"(https://[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @Autowired
    private MockMvc mockMvc;

    private Map<String, List<String>> servedPolicy() throws Exception {
        MvcResult result = mockMvc.perform(get("/index.html")).andReturn();
        String header = result.getResponse().getHeader("Content-Security-Policy");

        assertThat(header).as("no Content-Security-Policy header is being served at all").isNotBlank();

        Map<String, List<String>> directives = new LinkedHashMap<>();
        for (String directive : header.split(";")) {
            String[] parts = directive.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isBlank()) continue;
            directives.put(parts[0].toLowerCase(),
                    Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length)));
        }
        return directives;
    }

    private static String originOf(String url) {
        int schemeEnd = url.indexOf("://");
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    /** Handles exact origins plus CSP's {@code https://*.example.com} wildcard form. */
    private static boolean allows(List<String> sources, String origin) {
        for (String source : sources) {
            if (source.equals(origin)) return true;
            if (source.contains("://*.")) {
                String suffix = source.substring(source.indexOf("://*.") + 4); // ".example.com"
                String scheme = source.substring(0, source.indexOf("://") + 3);
                if (origin.startsWith(scheme) && origin.endsWith(suffix)) return true;
            }
        }
        return false;
    }

    private record ExternalAsset(Path page, String url, String directive) {}

    private List<ExternalAsset> externalAssets() throws IOException {
        List<ExternalAsset> assets = new ArrayList<>();

        try (Stream<Path> pages = Files.walk(STATIC_ROOT)) {
            for (Path page : pages.filter(p -> p.toString().endsWith(".html")).toList()) {
                String html = Files.readString(page, StandardCharsets.UTF_8);

                Matcher scripts = SCRIPT_SRC.matcher(html);
                while (scripts.find()) {
                    assets.add(new ExternalAsset(page, scripts.group(1), "script-src"));
                }

                Matcher links = LINK_TAG.matcher(html);
                while (links.find()) {
                    String tag = links.group();
                    if (!tag.toLowerCase().contains("stylesheet")) continue;
                    Matcher href = HREF.matcher(tag);
                    if (href.find()) {
                        assets.add(new ExternalAsset(page, href.group(1), "style-src"));
                    }
                }
            }
        }
        return assets;
    }

    @Test
    void everyExternalScriptAndStylesheetIsPermittedByTheServedPolicy() throws Exception {
        Map<String, List<String>> policy = servedPolicy();
        List<ExternalAsset> assets = externalAssets();

        assertThat(assets)
                .as("found no external assets to check — the scan itself is probably broken")
                .isNotEmpty();

        List<String> blocked = new ArrayList<>();
        for (ExternalAsset asset : assets) {
            List<String> sources = policy.getOrDefault(asset.directive(), List.of());
            if (!allows(sources, originOf(asset.url()))) {
                blocked.add(String.format("%s loads %s but %s does not allow %s",
                        STATIC_ROOT.relativize(asset.page()), asset.url(),
                        asset.directive(), originOf(asset.url())));
            }
        }

        assertThat(blocked)
                .as("these resources would be silently blocked in the browser")
                .isEmpty();
    }

    /**
     * The exam IDE (CodeMirror) and the live proctoring media path are the two
     * things a CSP regression hurts most, so pin their requirements explicitly
     * rather than relying only on the scan above.
     */
    @Test
    void thePolicyAllowsTheExamIdeAndLiveProctoringToFunction() throws Exception {
        Map<String, List<String>> policy = servedPolicy();

        assertThat(allows(policy.getOrDefault("style-src", List.of()), "https://cdnjs.cloudflare.com"))
                .as("CodeMirror's stylesheet host must be in style-src or students cannot type code")
                .isTrue();

        assertThat(policy.getOrDefault("connect-src", List.of()))
                .as("WebRTC ICE negotiation needs the stun:/turn: schemes permitted in connect-src")
                .contains("stun:", "turn:");

        assertThat(allows(policy.getOrDefault("img-src", List.of()),
                "https://someaccountid.r2.cloudflarestorage.com"))
                .as("identity photos are served from R2 presigned URLs and must be allowed in img-src")
                .isTrue();
    }
}
