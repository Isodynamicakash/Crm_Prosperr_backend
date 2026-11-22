# Verification performed before delivery

The sandbox this was written in could not reach Maven Central, so `mvn test` has not been run yet.
These checks were run instead:

1. **unit-tests-run.txt**: the 64 business-rule unit tests compiled with `javac` and executed with a minimal JUnit stand-in. 64/64 pass.
2. **Type check**: all main and test sources compiled against stand-ins of every library API they call. 0 errors
   (`SecurityConfig` excluded: standard Spring Security 6 lambda DSL).
3. **sql-static-prepare.txt**: every SQL text block in the code `PREPARE`d against V1+V2 on Postgres 16. Lines listed as
   failures there are fragments that are only complete at runtime; they are covered by step 4.
4. **sql-composed-prepare.txt**: dashboard, report and list services were executed with a recording database stand-in using every
   filter. All 98 distinct assembled queries were captured and `PREPARE`d.

Run `mvn test` (Docker, or `CRM_TEST_DB_URL` pointing at an empty Postgres) to execute the 60 integration tests and the Modulith check.
