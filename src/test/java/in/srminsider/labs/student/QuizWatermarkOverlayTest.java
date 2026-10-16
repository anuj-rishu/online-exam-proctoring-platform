package in.srminsider.labs.student;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;


class QuizWatermarkOverlayTest {

    private static final Path QUIZ = Path.of("src/main/resources/static/student/quiz.html");

    private String stylesheet() throws Exception {
        String html = Files.readString(QUIZ);
        var matcher = Pattern.compile("<style>(.*?)</style>", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).as("quiz.html should have an inline stylesheet").isTrue();
        return matcher.group(1);
    }

    @Test
    void theWatermarkOverlayIsNeverGivenABackground() throws Exception {
        String css = stylesheet();

        assertThat(css)
                .as("positional child selectors on <section> land on the watermark overlay, not the bar they were written for — target an id instead")
                .doesNotContain("section > div:first-child")
                .doesNotContain("section > div:last-child");

        assertThat(css.replaceAll("\\s+", " "))
                .as("the watermark overlay must be explicitly transparent")
                .contains("#questionWatermark { background-color: transparent !important; }");
    }

    @Test
    void theBarsTheDarkThemeStylesAreAddressedByIdAndThoseIdsExist() throws Exception {
        String html = Files.readString(QUIZ);
        String css = stylesheet();

        for (String id : new String[]{"questionWatermark", "questionSubBar", "questionBody", "questionNavBar"}) {
            assertThat(html)
                    .as("quiz.html must keep id=%s, the dark theme targets it", id)
                    .contains("id=\"" + id + "\"");
        }

        assertThat(css).contains("#questionSubBar");
        assertThat(css).contains("#questionNavBar");
    }
}
