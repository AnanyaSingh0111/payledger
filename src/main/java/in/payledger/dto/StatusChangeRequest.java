package in.payledger.dto;

import jakarta.validation.constraints.Size;

/** Optional reason recorded in the audit log when an admin freezes or disables something. */
public record StatusChangeRequest(
        @Size(max = 255, message = "Reason must not exceed 255 characters")
        String reason) { }
