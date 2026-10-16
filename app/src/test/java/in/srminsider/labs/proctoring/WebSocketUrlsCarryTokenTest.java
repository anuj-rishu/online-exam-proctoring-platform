package in.srminsider.labs.proctoring;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;


class WebSocketUrlsCarryTokenTest {

    private static final Pattern WS_URL = Pattern.compile("new WebSocket\\(`([^`]*)`\\)");

    private static final List<String> PAGES_OPENING_THE_SOCKET = List.of(
            "src/main/resources/static/student/quiz.html",
            "src/main/resources/static/admin/admin-live.html");

    @Test
    void everyPageOpeningTheSignalingSocketSendsTheAuthToken() throws Exception {
        for (String page : PAGES_OPENING_THE_SOCKET) {
            String html = Files.readString(Path.of(page));
            Matcher matcher = WS_URL.matcher(html);

            boolean foundSignalingSocket = false;
            while (matcher.find()) {
                String url = matcher.group(1);
                if (!url.contains("ws-signaling")) continue;
                foundSignalingSocket = true;
                assertThat(url)
                        .as("%s opens /ws-signaling without ?token= — the handshake will be rejected", page)
                        .contains("token=");
            }

            assertThat(foundSignalingSocket)
                    .as("expected %s to open a /ws-signaling connection", page)
                    .isTrue();
        }
    }
}
