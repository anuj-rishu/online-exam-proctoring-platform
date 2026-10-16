package in.srminsider.labs.domain.model;

import java.util.List;
import java.util.Set;

public final class ProblemCatalog {

    public static final String PROGRAMMING_QUESTION = "Programming Question";

    public static final List<String> SECTIONS = List.of(
            "Programming MCQ",
            "Aptitude MCQ",
            "Academics",
            PROGRAMMING_QUESTION);

    public static final List<String> DOMAINS = List.of("Web Dev", "AIML", "Cloud", "App Dev");

    private static final Set<String> SECTION_SET = Set.copyOf(SECTIONS);
    private static final Set<String> DOMAIN_SET = Set.copyOf(DOMAINS);

    private ProblemCatalog() {
    }

    public static boolean isValidSection(String section) {
        return section != null && SECTION_SET.contains(section);
    }

    public static boolean isValidDomain(String domain) {
        return domain != null && DOMAIN_SET.contains(domain);
    }

    public static String sectionOptions() {
        return String.join(", ", SECTIONS);
    }

    public static String domainOptions() {
        return String.join(", ", DOMAINS);
    }
}
