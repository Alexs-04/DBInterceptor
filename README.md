# DBInterceptor

### Version 1.0.0

---

### Overview

It is in an application designed to analyze the complexity of migrating ORACLE databases to POSTGRESQL. In addition to
analyzing its table content, that is, identifying data types, names or files that should not be there.

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

If you prefer usingDocker, you can build and run the Docker container:

   ```bash
   docker build -t dbinterceptor:1.0 .
   docker run -p 8080:8080 dbinterceptor:1.0
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
---

### Usage

1. Configure database connection settings in `application.properties`.
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