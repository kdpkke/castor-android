package io.github.stupside.castor

// Needs real hardware or an external site. Gradle excludes it with one notAnnotation argument;
// run it explicitly with `am instrument -e class ...`.
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ManualDiagnostic
