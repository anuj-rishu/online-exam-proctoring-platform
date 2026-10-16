package in.srminsider.labs.student;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;


class NoNativeDialogsInExamTest {

    private static final Path QUIZ = Path.of("src/main/resources/static/student/quiz.html");

    
    private static final Pattern NATIVE_DIALOG =
            Pattern.compile("(?<![\\w.$])(?:window\\s*\\.\\s*)?(alert|confirm|prompt)\\s*\\(");

    
    private static String stripCommentsAndStrings(String js) {
        StringBuilder out = new StringBuilder(js.length());
        int i = 0;
        while (i < js.length()) {
            char c = js.charAt(i);
            char next = i + 1 < js.length() ? js.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < js.length() && js.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < js.length() && !(js.charAt(i) == '*' && js.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, js.length());
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < js.length()) {
                    char q = js.charAt(i);
                    if (q == '\\') { i += 2; continue; }
                    if (q == quote) { i++; break; }
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    @Test
    void theQuizPageNeverOpensANativeDialog() throws Exception {
        String html = Files.readString(QUIZ);

        Matcher scripts = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
        List<String> offenders = new ArrayList<>();

        while (scripts.find()) {
            String code = stripCommentsAndStrings(scripts.group(1));
            Matcher m = NATIVE_DIALOG.matcher(code);
            while (m.find()) {
                offenders.add(m.group());
            }
        }

        assertThat(offenders)
                .as("use showAlert()/showConfirm() — a native dialog drops the candidate out of fullscreen mid-exam")
                .isEmpty();
    }

    @Test
    void theInPageDialogHelpersAndMarkupExist() throws Exception {
        String html = Files.readString(QUIZ);

        for (String id : new String[]{"examDialog", "examDialogTitle", "examDialogMessage",
                                      "examDialogConfirm", "examDialogCancel"}) {
            assertThat(html).as("examDialog markup must keep id=%s", id).contains("id=\"" + id + "\"");
        }
        assertThat(html).contains("const showConfirm");
        assertThat(html).contains("const showAlert");
    }
}
