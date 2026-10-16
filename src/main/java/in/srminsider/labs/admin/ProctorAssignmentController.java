package in.srminsider.labs.admin;

import in.srminsider.labs.admin.dto.ProctorRequests;
import in.srminsider.labs.domain.model.ProblemCatalog;
import in.srminsider.labs.domain.model.ProctorAssignment;
import in.srminsider.labs.domain.model.Role;
import in.srminsider.labs.domain.model.TestSession;
import in.srminsider.labs.domain.model.User;
import in.srminsider.labs.domain.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/proctors")
@RequiredArgsConstructor
public class ProctorAssignmentController {

    private final ProctorAssignmentService assignmentService;
    private final UserRepository userRepository;

    
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping
    public ResponseEntity<?> listProctors() {
        List<Map<String, Object>> rows = new ArrayList<>();

        for (User admin : userRepository.findByRole(Role.ADMIN)) {
            Optional<ProctorAssignment> config = assignmentService.configFor(admin.getUsername());

            Map<String, Object> row = new HashMap<>();
            row.put("username", admin.getUsername());
            row.put("email", admin.getEmail());
            row.put("domains", config.map(ProctorAssignment::getDomains).orElseGet(List::of));
            row.put("capacity", config.map(ProctorAssignment::getCapacity).orElse(0));
            row.put("liveLoad", assignmentService.liveLoad(admin.getUsername()));
            row.put("configured", config.isPresent());
            rows.add(row);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("proctors", rows);
        body.put("availableDomains", ProblemCatalog.DOMAINS);
        body.put("allowedCapacities", ProctorAssignmentService.ALLOWED_CAPACITIES);
        body.put("unassignedStudents", assignmentService.unassignedCount(ProblemCatalog.DOMAINS));
        return ResponseEntity.ok(body);
    }

    
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/{adminUsername}")
    public ResponseEntity<?> assignScope(@PathVariable String adminUsername,
                                         @Valid @RequestBody ProctorRequests.AssignScope request) {

        Optional<User> admin = userRepository.findByUsername(adminUsername);
        if (admin.isEmpty() || admin.get().getRole() != Role.ADMIN) {
            return ResponseEntity.badRequest().body(Map.of("error", "No such admin"));
        }

        for (String domain : request.domains()) {
            if (!ProblemCatalog.isValidDomain(domain)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Domain \"" + domain + "\" is not one of: " + ProblemCatalog.domainOptions()));
            }
        }

        if (!ProctorAssignmentService.ALLOWED_CAPACITIES.contains(request.capacity())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Capacity must be one of: " + ProctorAssignmentService.ALLOWED_CAPACITIES));
        }

        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        ProctorAssignment saved = assignmentService.saveConfig(
                adminUsername, request.domains(), request.capacity(), actor);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("adminUsername", saved.getAdminUsername());
        body.put("domains", saved.getDomains());
        body.put("capacity", saved.getCapacity());
        body.put("liveLoad", assignmentService.liveLoad(adminUsername));
        return ResponseEntity.ok(body);
    }

    
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/me/roster")
    public ResponseEntity<?> myRoster() {
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<ProctorAssignment> config = assignmentService.configFor(admin);
        List<TestSession> roster = assignmentService.claimRoster(admin);

        List<Map<String, Object>> students = roster.stream().map(s -> {
            Map<String, Object> row = new HashMap<>();
            row.put("username", s.getUsername());
            row.put("year", s.getYear());
            row.put("domain", s.getDomain());
            row.put("isBlocked", s.isBlocked());
            return row;
        }).toList();

        Map<String, Object> body = new HashMap<>();
        body.put("students", students);
        body.put("assignedUsernames", roster.stream().map(TestSession::getUsername).toList());
        body.put("domains", config.map(ProctorAssignment::getDomains).orElseGet(List::of));
        body.put("capacity", config.map(ProctorAssignment::getCapacity).orElse(0));
        body.put("configured", config.isPresent());
        body.put("leaseSeconds", ProctorAssignmentService.LEASE_TTL_MS / 1000);
        return ResponseEntity.ok(body);
    }

    
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/me/release")
    public ResponseEntity<?> releaseMyRoster() {
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        assignmentService.releaseAll(admin);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
