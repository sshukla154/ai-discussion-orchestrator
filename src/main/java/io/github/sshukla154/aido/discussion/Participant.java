package io.github.sshukla154.aido.discussion;

/** Who is speaking. Two roles, deliberately asymmetric in what they are asked to do. */
public enum Participant {

    /** Develops a position, then defends or revises it under criticism. */
    ARCHITECT("Architect"),

    /** Attacks the position independently, and is instructed not to agree merely to agree. */
    CHALLENGER("Challenger");

    private final String label;

    Participant(String label) {
        this.label = label;
    }

    /** For prompts and the written artifact, where an enum constant would read badly. */
    public String label() {
        return label;
    }

    public Participant other() {
        return this == ARCHITECT ? CHALLENGER : ARCHITECT;
    }
}
