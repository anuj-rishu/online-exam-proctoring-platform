package in.srminsider.labs.admin;

import in.srminsider.labs.domain.model.Problem;
import in.srminsider.labs.domain.model.ProblemCatalog;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.repository.ProblemRepository;
import in.srminsider.labs.domain.repository.TestSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects near-identical code submissions between students.
 *
 * Uses winnowing fingerprints (the approach behind MOSS) rather than naive
 * string comparison, so a match still registers when the copy has been
 * reformatted, re-commented, or had every variable renamed — the usual
 * ways a copied solution gets disguised. Identifiers, numbers and string
 * literals are normalised away before hashing, leaving only the structural
 * shape of the program.
 *
 * Scores are advisory. Short or heavily-templated answers legitimately look
 * alike, which is why very short submissions are excluded outright and the
 * proctor is shown the score rather than a verdict.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeSimilarityService {

    /** Tokens per k-gram. Smaller catches shorter copied fragments, but is noisier. */
    private static final int K_GRAM = 5;

    /** Winnowing window: guarantees any shared run of >= K_GRAM + WINDOW - 1 tokens is caught. */
    private static final int WINDOW = 4;

    /** Answers shorter than this are too trivial to distinguish copying from coincidence. */
    private static final int MIN_TOKENS = 25;

    /** Guard against a pathological O(n^2) sweep on a very large cohort. */
    private static final int MAX_SUBMISSIONS_PER_PROBLEM = 600;

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z_0-9]*|\\d+(?:\\.\\d+)?|[^\\s\\w]");

    /**
     * Structural vocabulary kept verbatim across Python / Java / JavaScript.
     * Everything else that looks like a name collapses to a single token, so
     * renaming variables changes nothing.
     */
    private static final Set<String> KEYWORDS = Set.of(
            "def", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or",
            "import", "from", "class", "try", "except", "finally", "with", "as", "lambda",
            "yield", "pass", "break", "continue", "global", "nonlocal", "raise", "assert",
            "del", "none", "true", "false", "self", "print", "range", "len", "input",
            "public", "private", "protected", "static", "void", "new", "this", "extends",
            "implements", "interface", "abstract", "final", "throws", "throw", "catch",
            "switch", "case", "default", "do", "instanceof", "package", "boolean", "char",
            "double", "long", "short", "byte", "null", "int", "float", "str", "list", "dict",
            "function", "var", "let", "const", "typeof", "undefined", "strlit");

    private final TestSessionRepository sessionRepository;
    private final ProblemRepository problemRepository;

    public record SimilarityHit(
            String problemId,
            String problemTitle,
            String studentA,
            String studentB,
            double similarity,
            String codeA,
            String codeB) {
    }

    /**
     * Strips comments and string contents with a single linear scan. A regex
     * pass over a few hundred lines of arbitrary student code is a known way
     * to hit catastrophic backtracking, so this deliberately doesn't use one.
     */
    private String stripCommentsAndStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();

        while (i < n) {
            char c = code.charAt(i);

            if (c == '#' || (c == '/' && i + 1 < n && code.charAt(i + 1) == '/')) {
                while (i < n && code.charAt(i) != '\n') i++;
                continue;
            }

            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                continue;
            }

            if ((c == '"' || c == '\'') && i + 2 < n
                    && code.charAt(i + 1) == c && code.charAt(i + 2) == c) {
                i += 3;
                while (i + 2 < n && !(code.charAt(i) == c
                        && code.charAt(i + 1) == c && code.charAt(i + 2) == c)) i++;
                i = Math.min(i + 3, n);
                out.append(" strlit ");
                continue;
            }

            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && code.charAt(i) != quote) {
                    if (code.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                out.append(" strlit ");
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    List<String> tokenize(String code) {
        if (code == null || code.isBlank()) return List.of();

        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(stripCommentsAndStrings(code));

        while (matcher.find()) {
            String token = matcher.group();
            char first = token.charAt(0);

            if (Character.isDigit(first)) {
                tokens.add("N");
            } else if (Character.isLetter(first) || first == '_') {
                String lowered = token.toLowerCase();
                tokens.add(KEYWORDS.contains(lowered) ? lowered : "V");
            } else {
                tokens.add(token);
            }
        }

        return tokens;
    }

    public Set<Long> fingerprint(String code) {
        List<String> tokens = tokenize(code);
        if (tokens.size() < MIN_TOKENS) return Set.of();

        int gramCount = tokens.size() - K_GRAM + 1;
        long[] hashes = new long[gramCount];
        for (int i = 0; i < gramCount; i++) {
            long hash = 1125899906842597L;
            for (int j = 0; j < K_GRAM; j++) {
                hash = 31 * hash + tokens.get(i + j).hashCode();
            }
            hashes[i] = hash;
        }

        Set<Long> fingerprints = new HashSet<>();
        if (hashes.length <= WINDOW) {
            for (long hash : hashes) fingerprints.add(hash);
            return fingerprints;
        }

        for (int i = 0; i + WINDOW <= hashes.length; i++) {
            long min = hashes[i];
            for (int j = 1; j < WINDOW; j++) {
                min = Math.min(min, hashes[i + j]);
            }
            fingerprints.add(min);
        }
        return fingerprints;
    }

    /** Jaccard overlap of two fingerprint sets, 0.0 to 1.0. */
    public double similarity(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<Long> smaller = a.size() <= b.size() ? a : b;
        Set<Long> larger = smaller == a ? b : a;

        int intersection = 0;
        for (Long fingerprint : smaller) {
            if (larger.contains(fingerprint)) intersection++;
        }

        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * Compares every pair of coding answers within a year/domain and returns
     * the pairs scoring at or above {@code threshold}, most similar first.
     */
    public List<SimilarityHit> findSuspiciousPairs(Integer year, String domain, double threshold) {
        List<Problem> codingProblems = problemRepository.findByYearAndDomain(year, domain).stream()
                .filter(p -> ProblemCatalog.PROGRAMMING_QUESTION.equals(p.getSection()))
                .toList();

        if (codingProblems.isEmpty()) return List.of();

        List<TestSession> sessions = sessionRepository.findAll().stream()
                .filter(s -> year.equals(s.getYear()) && domain.equals(s.getDomain()))
                .toList();

        List<SimilarityHit> hits = new ArrayList<>();

        for (Problem problem : codingProblems) {
            Map<String, Set<Long>> fingerprintsByStudent = new HashMap<>();
            Map<String, String> codeByStudent = new HashMap<>();

            for (TestSession session : sessions) {
                if (fingerprintsByStudent.size() >= MAX_SUBMISSIONS_PER_PROBLEM) {
                    log.warn("[Similarity] Capped comparison for problem {} at {} submissions",
                            problem.getId(), MAX_SUBMISSIONS_PER_PROBLEM);
                    break;
                }
                String answer = session.getAnswers().get(problem.getId());
                if (answer == null || answer.isBlank()) continue;

                Set<Long> fingerprint = fingerprint(answer);
                if (!fingerprint.isEmpty()) {
                    fingerprintsByStudent.put(session.getUsername(), fingerprint);
                    codeByStudent.put(session.getUsername(), answer);
                }
            }

            List<String> students = new ArrayList<>(fingerprintsByStudent.keySet());
            for (int i = 0; i < students.size(); i++) {
                for (int j = i + 1; j < students.size(); j++) {
                    String a = students.get(i);
                    String b = students.get(j);
                    double score = similarity(fingerprintsByStudent.get(a), fingerprintsByStudent.get(b));
                    if (score >= threshold) {
                        hits.add(new SimilarityHit(problem.getId(), problem.getTitle(), a, b, score,
                                codeByStudent.get(a), codeByStudent.get(b)));
                    }
                }
            }
        }

        hits.sort(Comparator.comparingDouble(SimilarityHit::similarity).reversed());
        return hits;
    }
}
