# Repository agent instructions

- Never run builds, tests, generated binaries, Gradle tasks, or compiler execution in this repository.
- Do not invoke `gradlew`, `gradle`, `kotlinc`, C compilers compiler commands, or test runners.
- Verification is limited to static source inspection unless the user explicitly removes this restriction.
