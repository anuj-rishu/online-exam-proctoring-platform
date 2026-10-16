package in.srminsider.labs.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ProctorRequests {

    private ProctorRequests() {
    }

    public record AssignScope(
            @NotNull(message = "Select at least one domain")
            @NotEmpty(message = "Select at least one domain")
            @Size(max = 20, message = "Too many domains")
            List<@Size(max = 60, message = "Domain name is too long") String> domains,

            @NotNull(message = "Capacity is required")
            Integer capacity
    ) {
    }
}
