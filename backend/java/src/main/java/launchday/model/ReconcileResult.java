package launchday.model;

public record ReconcileResult(int replayed, int confirmed, int reversed, boolean complete) {}