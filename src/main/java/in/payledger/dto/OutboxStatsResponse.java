package in.payledger.dto;

public record OutboxStatsResponse(long pending, long published, long failed) { }
