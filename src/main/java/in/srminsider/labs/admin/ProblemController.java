package in.srminsider.labs.admin;

import in.srminsider.labs.domain.model.Problem;
import in.srminsider.labs.domain.model.ProblemCatalog;
import in.srminsider.labs.domain.repository.ProblemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProblemController {

    private static final Set<String> CORRECT_OPTIONS = Set.of("A", "B", "C", "D");

    private final ProblemRepository problemRepository;
    private final MongoTemplate mongoTemplate;

    
    private static Criteria buildFilterCriteria(String domain, Integer year, String section) {
        List<Criteria> parts = new ArrayList<>();
        if (domain != null && !domain.trim().isEmpty()) parts.add(Criteria.where("domain").is(domain.trim()));
        if (year != null) parts.add(Criteria.where("year").is(year));
        if (section != null && !section.trim().isEmpty()) parts.add(Criteria.where("section").is(section.trim()));
        return parts.isEmpty() ? new Criteria() : new Criteria().andOperator(parts.toArray(new Criteria[0]));
    }

    private static boolean isBlankRow(String[] line) {
        for (String cell : line) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    private static String column(String[] line, int index) {
        if (line.length <= index) return null;
        String value = line[index] == null ? "" : line[index].trim();
        return value.isEmpty() ? null : value;
    }

    
    private static String rawColumn(String[] line, int index) {
        if (line.length <= index || line[index] == null || line[index].isEmpty()) return null;
        return line[index];
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/admin/problems")
    public ResponseEntity<Problem> createProblem(@RequestBody Problem problem) {
        if (problem.getTitle() == null || problem.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (problem.getYear() == null || problem.getYear() < 1 || problem.getYear() > 4) {
            throw new IllegalArgumentException("Year must be between 1 and 4");
        }
        if (!ProblemCatalog.isValidDomain(problem.getDomain())) {
            throw new IllegalArgumentException("Domain must be one of: " + ProblemCatalog.domainOptions());
        }
        if (!ProblemCatalog.isValidSection(problem.getSection())) {
            throw new IllegalArgumentException("Section must be one of: " + ProblemCatalog.sectionOptions());
        }
        return ResponseEntity.ok(problemRepository.save(problem));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/admin/problems")
    public ResponseEntity<Page<Problem>> getAllProblems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String section) {

        Criteria criteria = buildFilterCriteria(domain, year, section);
        long total = mongoTemplate.count(Query.query(criteria), Problem.class);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Query query = Query.query(criteria).with(pageable);
        List<Problem> content = mongoTemplate.find(query, Problem.class);

        return ResponseEntity.ok(new PageImpl<>(content, pageable, total));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/admin/problems/{id}")
    public ResponseEntity<Void> deleteProblem(@PathVariable String id) {
        problemRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/admin/problems/delete-batch")
    public ResponseEntity<Map<String, Object>> deleteProblemsBatch(@RequestBody List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No question IDs provided"));
        }
        problemRepository.deleteAllById(ids);
        return ResponseEntity.ok(Map.of("message", "Successfully deleted " + ids.size() + " questions", "count", ids.size()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/admin/problems/delete-by-filter")
    public ResponseEntity<Map<String, Object>> deleteProblemsByFilter(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String section) {

        Query query = Query.query(buildFilterCriteria(domain, year, section));
        long count = mongoTemplate.count(query, Problem.class);
        if (count > 0) {
            mongoTemplate.remove(query, Problem.class);
        }
        return ResponseEntity.ok(Map.of("message", "Successfully deleted " + count + " questions matching filter", "count", count));
    }

    @GetMapping("/admin/problems/template")
    public ResponseEntity<Resource> downloadTemplate() {
        String csvContent = "Title,Year,Domain,Section,OptionA,OptionB,OptionC,OptionD,CorrectOption\n" +
                "\"What does HTML stand for?\",1,Web Dev,Academics,\"Hyper Text\",\"Home Tool\",\"Hyperlinks\",\"Hyper Tool\",\"A\"";
        ByteArrayResource resource = new ByteArrayResource(csvContent.getBytes());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=mcq_template.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(resource);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping(value = "/admin/problems/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadProblems(@RequestParam("file") MultipartFile file) {
        CsvUploadValidator.validate(file);

        List<Problem> problems = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (Reader reader = new InputStreamReader(file.getInputStream());
             CSVReader csvReader = new CSVReader(reader)) {

            String[] line;
            int lineNumber = 0;
            while ((line = csvReader.readNext()) != null) {
                lineNumber++;
                if (lineNumber == 1) continue;
                if (isBlankRow(line)) continue;

                if (line.length < 4) {
                    errors.add("Row " + lineNumber + ": expected at least 4 columns (Title, Year, Domain, Section)");
                    continue;
                }

                String title = line[0].trim();
                String domain = line[2].trim();
                String section = line[3].trim();
                int errorsBefore = errors.size();

                if (title.isEmpty()) {
                    errors.add("Row " + lineNumber + ": Title is empty");
                }

                Integer year = null;
                try {
                    year = Integer.valueOf(line[1].trim());
                    if (year < 1 || year > 4) {
                        errors.add("Row " + lineNumber + ": Year must be between 1 and 4, found \"" + year + "\"");
                    }
                } catch (NumberFormatException e) {
                    errors.add("Row " + lineNumber + ": Year \"" + line[1].trim() + "\" is not a number");
                }

                if (!ProblemCatalog.isValidDomain(domain)) {
                    errors.add("Row " + lineNumber + ": Domain \"" + domain + "\" is not one of: " + ProblemCatalog.domainOptions());
                }

                if (!ProblemCatalog.isValidSection(section)) {
                    errors.add("Row " + lineNumber + ": Section \"" + section + "\" is not one of: " + ProblemCatalog.sectionOptions());
                }

                boolean mcq = !ProblemCatalog.PROGRAMMING_QUESTION.equals(section);
                String optionA = rawColumn(line, 4);
                String optionB = rawColumn(line, 5);
                String optionC = rawColumn(line, 6);
                String optionD = rawColumn(line, 7);
                String correctOption = column(line, 8);

                if (mcq && ProblemCatalog.isValidSection(section)) {
                    if (optionA == null || optionB == null || optionC == null || optionD == null) {
                        errors.add("Row " + lineNumber + ": MCQ rows need all four options (OptionA-OptionD)");
                    }
                    if (correctOption == null || !CORRECT_OPTIONS.contains(correctOption.toUpperCase())) {
                        errors.add("Row " + lineNumber + ": CorrectOption must be A, B, C or D, found \"" + correctOption + "\"");
                    }
                }

                if (errors.size() > errorsBefore) continue;

                problems.add(Problem.builder()
                        .title(title)
                        .year(year)
                        .domain(domain)
                        .section(section)
                        .type(mcq ? "MCQ" : "TEXT")
                        .optionA(optionA)
                        .optionB(optionB)
                        .optionC(optionC)
                        .optionD(optionD)
                        .correctOption(correctOption == null ? null : correctOption.toUpperCase())
                        .build());
            }
        } catch (IOException | CsvValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "The CSV file could not be read. Check that it is a valid, comma-separated file."));
        }

        if (!errors.isEmpty()) {
            List<String> shown = errors.stream().limit(10).collect(Collectors.toList());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Nothing was uploaded. " + errors.size() + " row(s) are invalid — fix them and upload again.",
                    "rowErrors", shown,
                    "errorCount", errors.size()));
        }

        if (problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "The CSV file has no question rows."));
        }

        problemRepository.saveAll(problems);
        return ResponseEntity.ok(Map.of("problems", problems, "count", problems.size()));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student/problems")
    public ResponseEntity<List<Problem>> getProblemsForStudent(
            @RequestParam Integer year,
            @RequestParam String domain) {

        List<Problem> allProblems = problemRepository.findByYearAndDomain(year, domain);

        Map<String, List<Problem>> grouped = allProblems.stream()
                .filter(p -> p.getSection() != null)
                .collect(Collectors.groupingBy(Problem::getSection));

        List<Problem> quiz = new ArrayList<>();

        List<Problem> progMcq = grouped.getOrDefault("Programming MCQ", new ArrayList<>());
        Collections.shuffle(progMcq);
        quiz.addAll(progMcq.stream().limit(30).collect(Collectors.toList()));

        List<Problem> aptMcq = grouped.getOrDefault("Aptitude MCQ", new ArrayList<>());
        Collections.shuffle(aptMcq);
        quiz.addAll(aptMcq.stream().limit(5).collect(Collectors.toList()));

        List<Problem> academics = grouped.getOrDefault("Academics", new ArrayList<>());
        Collections.shuffle(academics);
        quiz.addAll(academics.stream().limit(5).collect(Collectors.toList()));

        List<Problem> progQ = grouped.getOrDefault("Programming Question", new ArrayList<>());
        Collections.shuffle(progQ);
        quiz.addAll(progQ.stream().limit(10).collect(Collectors.toList()));

        Collections.shuffle(quiz);

        quiz.forEach(p -> p.setCorrectOption(null));

        return ResponseEntity.ok(quiz);
    }
}
