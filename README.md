# Your Order We Deliver

Delivery requests, quotations, staff scheduling and delivery tracking.
Built with Java 17+, Spring Boot, Thymeleaf and H2.

## Run

```sh
./mvnw clean verify
java -jar target/delivery-management-0.1.0.jar
```

Open http://127.0.0.1:8080. On Windows, use `mvnw.cmd clean verify`.

## Accounts

| Role | Username |
|---|---|
| Customer | `customer.alice`, `customer.bob` |
| Office | `office.demo` |
| Driver | `driver.li`, `driver.sam` |

Initial password: `Group5-Demo-2026!`. Existing accounts retain their passwords.
Data is stored in `./data/delivery.mv.db`. Times use `Australia/Melbourne`.

Code style: [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
