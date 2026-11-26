# DBInterceptor

### Version 1.0.0

---

### Overview

DBInterceptor is an application designed to analyze the complexity of migrating Oracle databases to PostgreSQL

### Features

- Analyze database schemas and table structures.
- Identify incompatible data types between ORACLE and POSTGRESQL.
- Detect potential migration issues.
- Generate reports on database complexity and migration readiness.
- User-friendly interface for easy navigation and analysis.
- Docker support for easy deployment.

### Installation

1. Clone the repository:
    ```bash
    git clone https://github.com/Alexs-04/DBInterceptor.git
    cd DBInterceptor
    ```
2. Install dependencies:
    ```bash
   ./gradlew build
    ```
3. Run the application:
    ```bash
   ./gradlew bootRun
    ```

If you prefer using Docker, you can build and run the Docker container:

   ```bash
   docker build -t dbinterceptor:1.0 .
   docker run -p 8080:8080 \
     -e DB_URL=jdbc:oracle:thin:@//host:1521/ORCLPDB1 \
     -e DB_USERNAME=usuario -e DB_PASSWORD=secreto \
     dbinterceptor:1.0
   ``` 

Or using docker-compose:

   ```bash
   docker-compose up --build
   ```

* The docker-compose.yml configuration file might look like this:
* ```yaml
  version: '3.8'
  services:
    app:
      image: db-interceptor:1.0
      ports:
        - "8080:8080"
      container_name: db-interceptor
      environment:
        DB_URL: jdbc:oracle:thin:@//host:1521/ORCLPDB1
        DB_USERNAME: usuario
        DB_PASSWORD: secreto
  ```

---

### Configuration

The connection is configured through environment variables (see `application.properties`):

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_URL` | yes | – | Oracle JDBC URL |
| `DB_USERNAME` | yes | – | Database user |
| `DB_PASSWORD` | yes | – | Database password |
| `DB_POOL_SIZE` | no | `10` | Maximum connections in the pool |
| `DB_CONNECTION_TIMEOUT_MS` | no | `30000` | Wait for a free connection (ms) |
| `DB_FETCH_SIZE` | no | `1000` | Rows fetched per round trip |
| `DB_QUERY_TIMEOUT` | no | `120s` | Timeout for each query |
| `THYMELEAF_CACHE` | no | `true` | Template cache (`bootRun` with DevTools disables it) |

A user with `SELECT_CATALOG_ROLE` gets exact sizes and account status (`DBA_USERS`, `DBA_SEGMENTS`, `DBA_LOBS`);
without it the application falls back to `ALL_*` views and optimizer statistics.

### Tests

```bash
./gradlew test
```

The tests need no Oracle database: views are rendered with a mocked service and the catalog queries
run against H2 in Oracle mode.

---

### Usage

1. Set the `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` environment variables.
2. Start the application using the command above.
3. Access the application via `http://localhost:8080`.
4. Follow the on-screen instructions to analyze your database.

### Limitations

- Currently, supports only ORACLE to POSTGRESQL migration analysis.
- May not cover all edge cases in database structures.
- Performance may vary based on database size and complexity.
- Requires Java 21 or higher to run.
- Limited support for custom data types and extensions.
- It is still under development; some features may be incomplete or unstable.

---

### License

This project is for educational purposes only and is not intended for commercial use. Use at your own risk.