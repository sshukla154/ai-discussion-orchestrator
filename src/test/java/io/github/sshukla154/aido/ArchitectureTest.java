package io.github.sshukla154.aido;

import java.util.Collection;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural rules that would otherwise depend on a reviewer remembering them.
 *
 * <p>Each rule below encodes a decision whose violation is either invisible at compile time or
 * only shows up as a production hang. They are cheap, and a rule that fails a build is worth more
 * than the same sentence in a document.
 *
 * <p>Annotations are matched <b>by name</b> rather than by type. Spring's transaction annotation
 * is not on the main compile classpath while persistence is test-scoped, so a type-based rule
 * would silently pass for a different reason than the one intended, and would stop being a guard
 * exactly when persistence returns in phase two.
 */
class ArchitectureTest {

    private static final String BASE_PACKAGE = "io.github.sshukla154.aido";
    private static final String CLI_CLIENT = BASE_PACKAGE + ".provider.claude.ClaudeCliClient";
    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    private static JavaClasses productionClasses;

    /**
     * Transitive on purpose. The hazard is not only the class that spawns the process, but every
     * caller above it that could wrap the call in a transaction without realising how long it
     * runs.
     */
    private static final DescribedPredicate<JavaClass> CAN_REACH_THE_CLI =
            new DescribedPredicate<>("can reach the CLI subprocess") {
                @Override
                public boolean test(JavaClass clazz) {
                    if (CLI_CLIENT.equals(clazz.getName())) {
                        return true;
                    }
                    return clazz.getTransitiveDependenciesFromSelf().stream()
                            .anyMatch(d -> CLI_CLIENT.equals(d.getTargetClass().getName()));
                }
            };

    /**
     * Production classes only. The rules describe what main sources may do; test fixtures
     * deliberately break them.
     */
    private static JavaClasses violatingFixture;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
        violatingFixture = new ClassFileImporter().importPackages(BASE_PACKAGE + ".archfixture");
    }

    @Test
    @DisplayName("the rules are not scanning an empty set")
    void rulesActuallyHaveSomethingToCheck() {
        // An architecture rule that matches nothing is green and worthless, which by the test
        // severity lens is the dangerous kind of failure. This asserts the predicate really
        // resolves production classes, so the rules below are meaningful rather than vacuous.
        long reachable = productionClasses.stream().filter(CAN_REACH_THE_CLI).count();

        assertThat(productionClasses).isNotEmpty();
        assertThat(reachable)
                .describedAs("at least the client itself and its runner must be reachable")
                .isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("the transaction rules fail on a class that breaks them")
    void rulesRejectAKnownViolator() {
        // Proves the rules bite. The fixture is @Transactional, declares REQUIRES_NEW, and calls
        // the CLI client -- so both rules must report a violation against it. Without this, a
        // rule that silently stopped matching would look identical to a clean codebase.
        assertThatThrownBy(() -> transactionRule().check(violatingFixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("can reach a provider call");

        assertThatThrownBy(() -> requiresNewRule().check(violatingFixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("REQUIRES_NEW");
    }

    @Test
    @DisplayName("the transaction rule also catches a class transactional only at method level")
    void transactionRuleInspectsMethodsNotJustClasses() {
        // The class-level fixture trips the rule before the per-method loop runs, so without
        // this the loop was never actually proven to work -- and a service with one transactional
        // method is the shape that will actually occur.
        assertThatThrownBy(() -> transactionRule().check(violatingFixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("wrapsASubprocessCallInATransaction");
    }

    @Test
    @DisplayName("the persistence rule fails on a class that imports a banned package")
    void persistenceRuleRejectsAKnownViolator() {
        // Guards against the rule silently matching nothing. A typo in any banned package string
        // would otherwise leave it permanently vacuous, reporting green with no way to tell that
        // from a genuinely clean codebase.
        assertThatThrownBy(() -> noPersistenceRule().check(violatingFixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("jakarta.persistence");
    }

    @Test
    @DisplayName("nothing that can reach the CLI subprocess is transactional")
    void noTransactionSpansAProviderCall() {
        // A turn can legitimately run for five minutes, and the connection pool is size one
        // because SQLite has a single writer. A transaction held across the subprocess call
        // therefore blocks every other query and the application looks dead -- and the operator
        // response to an app that looks dead is to kill it, which lands straight in the
        // crash-recovery path this design works hard to make correct.
        //
        // "Can reach" is transitive on purpose: the danger is not only the class that spawns the
        // process but any caller above it in the stack.
        transactionRule().check(productionClasses);
    }

    private static ArchRule transactionRule() {
        // classes(), not noClasses(). The condition below already reports the violating case,
        // and noClasses() negates whatever it is given -- which silently inverts the rule into
        // "every reachable class must be transactional" and passes over a clean codebase.
        return classes()
                .that(CAN_REACH_THE_CLI)
                .should(new ArchCondition<JavaClass>("not be transactional, at class or method level") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        if (isTransactional(clazz.getAnnotations())) {
                            events.add(SimpleConditionEvent.violated(clazz,
                                    clazz.getName() + " is @Transactional and can reach a provider call"));
                        }
                        for (JavaMethod method : clazz.getMethods()) {
                            if (isTransactional(method.getAnnotations())) {
                                events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " is @Transactional and can reach a provider call"));
                            }
                        }
                    }
                })
                .allowEmptyShould(true);
    }

    @Test
    @DisplayName("REQUIRES_NEW is never used, because with one connection it deadlocks against itself")
    void noRequiresNewPropagation() {
        // A second transaction needs a second connection. The pool holds exactly one, so the
        // inner transaction waits for a connection the outer one is still holding, forever.
        // Nothing about the code reads as wrong; it simply stops.
        requiresNewRule().check(productionClasses);
    }

    private static ArchRule requiresNewRule() {
        // classes(), for the same reason as above.
        return classes()
                .should(new ArchCondition<JavaClass>("not declare Propagation.REQUIRES_NEW") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        checkForRequiresNew(clazz.getAnnotations(), clazz.getName(), clazz, events);
                        for (JavaMethod method : clazz.getMethods()) {
                            checkForRequiresNew(method.getAnnotations(), method.getFullName(), method, events);
                        }
                    }
                })
                .allowEmptyShould(true);
    }

    @Test
    @DisplayName("main sources stay free of persistence, so phase one really has no database")
    void mainSourcesDoNotDependOnJpa() {
        // Persistence is test-scoped while phase one writes plain files. Without this rule the
        // scoping decision decays the first time someone adds an entity and reaches for the
        // starter, and the claim in application.yaml becomes false without anything failing.
        noPersistenceRule().check(productionClasses);
    }

    private static ArchRule noPersistenceRule() {
        return noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "org.springframework.data.jpa..",
                        "org.flywaydb..")
                .allowEmptyShould(true);
    }

    @Test
    @DisplayName("the domain time format does not depend on persistence")
    void timeFormattingIsNotAPersistenceConcern() {
        // The fixed-width ordering guarantee is a domain invariant that happens to be convenient
        // for storage. Keeping the split explicit is what allowed persistence to become
        // test-scoped without the formatter following it.
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE_PACKAGE + ".common.time..")
                .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    private static boolean isTransactional(Collection<? extends JavaAnnotation<?>> annotations) {
        return annotations.stream().anyMatch(a -> {
            String name = a.getRawType().getName();
            return TRANSACTIONAL.equals(name) || JAKARTA_TRANSACTIONAL.equals(name);
        });
    }

    private static void checkForRequiresNew(Collection<? extends JavaAnnotation<?>> annotations,
                                            String owner, Object location, ConditionEvents events) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (!isTransactional(List.of(annotation))) {
                continue;
            }
            annotation.tryGetExplicitlyDeclaredProperty("propagation").ifPresent(value -> {
                if (String.valueOf(value).contains("REQUIRES_NEW")) {
                    events.add(SimpleConditionEvent.violated(location,
                            owner + " declares Propagation.REQUIRES_NEW, which self-deadlocks on a single-connection pool"));
                }
            });
        }
    }
}
