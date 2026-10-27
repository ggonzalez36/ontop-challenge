package com.ontop.transfers;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the dependency rule of ADR 001: dependencies point inwards, adapters never depend on each other.
 */
@AnalyzeClasses(packages = "com.ontop.transfers", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNothingElse = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapter..", "..config..",
                    "org.springframework..", "jakarta..", "com.fasterxml..", "org.hibernate..");

    @ArchTest
    static final ArchRule applicationDependsOnlyOnDomain = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..config..", "org.springframework..", "jakarta..", "org.hibernate..");

    @ArchTest
    static final ArchRule drivingAdaptersDoNotUseDrivenAdapters = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

    @ArchTest
    static final ArchRule drivenAdaptersAreIndependent = noClasses()
            .that().resideInAPackage("..adapter.out.wallet..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.out.payments..", "..adapter.out.persistence..");
}
