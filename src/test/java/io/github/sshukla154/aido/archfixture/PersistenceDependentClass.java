package io.github.sshukla154.aido.archfixture;

import jakarta.persistence.EntityManager;

/**
 * Depends on a package that main sources are forbidden to touch while phase one has no database.
 *
 * <p>Exists so the dependency rule can be shown to reject something. Without it, a typo in one of
 * the banned package strings -- {@code jakarta.persistance}, say -- would make the rule match
 * nothing, forever, and report green. That is indistinguishable from a clean codebase, which is
 * the worst property an architecture rule can have.
 *
 * <p>Deliberately <b>not</b> an {@code @Entity}. Annotating it as one puts it inside Hibernate's
 * entity scan, which then fails {@code ddl-auto=validate} against a table the migration never
 * creates and takes the whole persistence spike down with it. Holding a reference to a
 * persistence type creates the dependency the rule looks for without registering anything.
 *
 * <p>Never move to main sources.
 */
public class PersistenceDependentClass {

    private EntityManager entityManager;

    public EntityManager getEntityManager() {
        return entityManager;
    }
}
