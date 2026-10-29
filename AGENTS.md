# Project collaboration rules

- Member A owns auth/, persistence/, contract/, web/ foundation, configuration, migrations, build files and shared layout/CSS.
- Member B owns requests/, quotes/, DeliveryLifecycle implementation and the corresponding templates/tests.
- Member C owns scheduling/, availability/, delivery/ and corresponding templates/tests.
- Do not implement or alter another member's business logic without explicit handoff.
- Business code uses DeliveryStore; SQL belongs only to persistence or A's initialization.
- Do not disable CSRF, weaken ownership checks, return AccountData/DeliveryPrincipal to browsers, or store plaintext passwords.
- Respect contract IDs, versions, timezone and lock ordering. Staff ascending before requests ascending.
- Do not rewrite published Flyway migrations; add a new migration.
- Do not commit data/, target/, secrets, IDE state or real personal data.
- Run ./mvnw verify before a merge. A owns integration, not all business debugging.
- Root README and docs/TEAM_RULES.md describe scope; implementation, tests and execution evidence must be described separately.
