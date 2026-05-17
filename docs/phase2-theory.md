# Phase 2 Theory: REST APIs in Spring Boot

A standalone deep-dive. All examples use generic domains (Book/Author, Customer/Order) so the concepts stay separated from any specific project.

Each chapter ends with **10 self-quiz questions** with short answers. Cover the answer, ask yourself the question, then check.

---

## Table of contents

1. [Layering — controller, service, repository](#1-layering--controller-service-repository)
2. [REST controllers in Spring Boot](#2-rest-controllers-in-spring-boot)
3. [Request and response binding](#3-request-and-response-binding)
4. [DTOs at the API boundary (recap)](#4-dtos-at-the-api-boundary-recap)
5. [Validation with Bean Validation](#5-validation-with-bean-validation)
6. [HTTP status codes — when to use which](#6-http-status-codes--when-to-use-which)
7. [Redirects: 301 vs 302](#7-redirects-301-vs-302)
8. [Exception handling with @RestControllerAdvice](#8-exception-handling-with-restcontrolleradvice)
9. [Common pitfalls](#9-common-pitfalls-to-avoid)
10. [Interview probes](#10-interview-probes-you-should-be-ready-for)
11. [Glossary](#11-glossary)

---

## 1. Layering — controller, service, repository

```
                HTTP request
                     │
                     ▼
            ┌────────────────┐
            │   Controller   │  ← HTTP-shaped: paths, methods, status codes
            │                │     No DB. No business logic.
            └────────┬───────┘
                     │ DTO in, DTO out
                     ▼
            ┌────────────────┐
            │    Service     │  ← @Transactional, business rules
            │                │     Entity ↔ DTO mapping
            └────────┬───────┘
                     │ entity ↔ DB
                     ▼
            ┌────────────────┐
            │   Repository   │  ← Spring Data, queries
            └────────────────┘
```

**Strict rule:** controllers never touch entities directly, never start transactions, never call repositories. They are thin — receive HTTP, delegate, return HTTP.

If you find yourself writing `bookRepo.findById(...)` in a controller, something is wrong.

### Why so strict?

- **Single responsibility.** Controllers deal in HTTP concepts (status codes, headers, paths). Services deal in business operations. Repositories deal in persistence. When the boundaries blur, every concern leaks into every layer.
- **Testability.** A controller test can be a `@WebMvcTest` with the service mocked — fast, no DB. A service test can mock the repo. Both impossible if business logic lives in the controller.
- **Transaction boundaries.** Putting `@Transactional` on a controller means the persistence context spans the entire HTTP handling — including JSON serialization. That's the OSIV anti-pattern reborn. Transactions belong on services.
- **Reusability.** The same service method might be called by an HTTP controller, a CLI command, a scheduled job, a message consumer. Each uses a different transport; only the service is shared.

### Chapter 1 — 10 self-quiz questions

1. **Where do transactions live?**
   On service methods, via `@Transactional`. Never on controllers or repositories.

2. **What's wrong with putting business logic in a controller?**
   Couples business rules to HTTP. Can't reuse from other transports (CLI, scheduled jobs, message consumers). Hard to test in isolation.

3. **Can a controller method call multiple service methods?**
   Yes — sometimes you orchestrate, but better is to have one service method that does the business operation and is itself transactional. Multiple service calls from a controller means multiple transactions, with no atomic guarantee across them.

4. **What does the controller's return type look like for a JSON API?**
   A DTO (or `ResponseEntity<DTO>`). Never an entity.

5. **Where does entity → DTO conversion happen?**
   In the service. The controller receives a DTO from the service and passes it out.

6. **Can a controller throw raw exceptions and let them propagate?**
   Yes, that's the canonical pattern — domain exceptions thrown in services bubble up; a centralized `@RestControllerAdvice` translates them to HTTP responses.

7. **Why is "no DB calls in controllers" a non-negotiable?**
   Because it pulls transactional concerns into the HTTP layer, blurs responsibility, and makes the controller untestable without a DB.

8. **What replaces the View layer in `@RestController`?**
   Jackson serializing your return value directly to JSON in the response body. No template engine involved.

9. **Should authorization checks live in the controller or service?**
   Most teams use a separate security layer (filters/interceptors) that runs before the controller. Business-rule-driven authorization ("only the owner can edit") lives in the service.

10. **What's the canonical controller method body length?**
    1-5 lines. Receive input, call service, return output. If it's longer, you're doing too much.

---

## 2. REST controllers in Spring Boot

A controller is a class annotated `@RestController` with `@GetMapping`/`@PostMapping`/etc. methods. Spring discovers them at startup and routes requests to them.

```java
@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @PostMapping
    public ResponseEntity<BookResponse> create(@Valid @RequestBody CreateBookRequest req) {
        BookResponse created = bookService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public BookResponse get(@PathVariable Long id) {
        return bookService.get(id);
    }
}
```

### `@RestController` vs `@Controller`

- **`@Controller`** — traditional MVC. Methods return view names (`"book/details"`) that resolve to templates (Thymeleaf, JSP).
- **`@RestController`** = `@Controller` + `@ResponseBody`. Methods return objects, Spring serializes them (Jackson → JSON) directly to the response body.

For JSON APIs, always `@RestController`.

### Mapping annotations

| Annotation | HTTP method | Common use |
|---|---|---|
| `@GetMapping` | GET | Read |
| `@PostMapping` | POST | Create |
| `@PutMapping` | PUT | Replace |
| `@PatchMapping` | PATCH | Partial update |
| `@DeleteMapping` | DELETE | Remove |
| `@RequestMapping` | any | Generic form. Rarely used directly. |

`@RequestMapping("/api/books")` at class level prefixes all method paths.

### Constructor injection vs `@Autowired`

The canonical pattern is **constructor injection** (as in the example):

```java
public BookController(BookService bookService) {
    this.bookService = bookService;
}
```

No `@Autowired` annotation needed — Spring auto-wires single-constructor classes. Final fields, immutable, testable.

**Avoid field injection** (`@Autowired private BookService bookService;`). It bypasses constructor, breaks immutability, and is hard to test without Spring.

### Discovery at startup

When the app boots, Spring's `@ComponentScan` (triggered by `@SpringBootApplication`) finds every class annotated `@Controller`/`@RestController` and registers their methods with the `RequestMappingHandlerMapping`. From then on, each incoming HTTP request is matched against the registered paths and dispatched.

### Chapter 2 — 10 self-quiz questions

1. **Difference between `@Controller` and `@RestController`?**
   `@RestController` = `@Controller` + `@ResponseBody`. Methods return data, not view names.

2. **What does `@RequestMapping("/api/books")` at the class level do?**
   Prefixes every method's path. `@GetMapping("/{id}")` becomes `GET /api/books/{id}`.

3. **Which annotation pulls all the standard JSON-API behaviors together?**
   `@RestController`.

4. **Why prefer constructor injection over field injection?**
   Allows `final` fields, immutability, easy unit testing without Spring, fails fast at construction if a dependency is missing.

5. **Do you need `@Autowired` on a single-constructor class?**
   No. Spring auto-wires it. `@Autowired` is implicit.

6. **How does Spring discover your controllers?**
   `@ComponentScan` (via `@SpringBootApplication`) scans the package tree for `@Controller`/`@RestController` annotations and registers their methods.

7. **What library does Spring Boot use by default to serialize return values to JSON?**
   Jackson. It's pulled in by `spring-boot-starter-web`.

8. **Can a controller method be `private`?**
   It can, but Spring won't route to it. Mapping methods must be `public`.

9. **What's the difference between `@PostMapping` and `@RequestMapping(method = RequestMethod.POST)`?**
   None functionally. `@PostMapping` is the preferred short form.

10. **What happens if two methods map to the same path and method?**
    Spring throws at startup: "ambiguous mapping." Fail-fast.

---

## 3. Request and response binding

Spring extracts data from the HTTP request into method parameters and serializes the return value into the response.

### `@PathVariable` — values from the URL path

```java
@GetMapping("/{id}")
public BookResponse get(@PathVariable Long id) { ... }

// GET /api/books/42  →  id = 42
```

Spring converts the path segment to the declared type. If it can't parse (`/api/books/foo` when `id` is `Long`), Spring throws `MethodArgumentTypeMismatchException` → maps to 400.

If the parameter name doesn't match the path variable name, specify it: `@PathVariable("id") Long bookId`.

### `@RequestParam` — query string parameters

```java
@GetMapping
public Page<BookResponse> list(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size,
    @RequestParam(required = false) String title
) { ... }

// GET /api/books?page=1&size=10&title=Foo
```

`required = false` makes the param optional (null if absent). `defaultValue` gives a fallback.

### `@RequestBody` — the JSON body, deserialized

```java
@PostMapping
public ResponseEntity<BookResponse> create(@Valid @RequestBody CreateBookRequest req) { ... }
```

Spring uses Jackson to parse the request body JSON into your DTO class. `@Valid` triggers Bean Validation on the parsed object.

### `@RequestHeader` — read HTTP headers

```java
@GetMapping("/{id}")
public BookResponse get(
    @PathVariable Long id,
    @RequestHeader(value = "X-Trace-Id", required = false) String traceId
) { ... }
```

Useful for correlation IDs, custom client identifiers, etc.

### Return type — `ResponseEntity` vs plain object

**Plain return** — Spring uses HTTP 200 and serializes the return value:
```java
@GetMapping("/{id}")
public BookResponse get(@PathVariable Long id) {
    return bookService.get(id);
}
```

**`ResponseEntity` return** — you control status, headers, body:
```java
@PostMapping
public ResponseEntity<BookResponse> create(@Valid @RequestBody CreateBookRequest req) {
    BookResponse created = bookService.create(req);
    return ResponseEntity.status(HttpStatus.CREATED)
                         .header("Location", "/api/books/" + created.id())
                         .body(created);
}
```

**When to use which:**
- Plain return for vanilla GETs that always succeed (200).
- `ResponseEntity` when you need a non-200 status, custom headers, or sometimes-no-body.

An alternative for status alone: `@ResponseStatus(HttpStatus.CREATED)` on the method. Either is fine; `ResponseEntity` is more explicit.

### Common response patterns

```java
// 200 OK with body
return ResponseEntity.ok(body);

// 201 Created with Location
return ResponseEntity.created(URI.create("/api/books/42")).body(body);

// 204 No Content
return ResponseEntity.noContent().build();

// 302 Found redirect
return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();

// 404 Not Found, no body (rare — usually you'd throw and let advice handle it)
return ResponseEntity.notFound().build();
```

### Chapter 3 — 10 self-quiz questions

1. **`@PathVariable` vs `@RequestParam`?**
   `@PathVariable` extracts from the URL path (`/api/books/42`). `@RequestParam` extracts from the query string (`?page=0`).

2. **What happens if a `@PathVariable Long id` receives `/api/books/foo`?**
   `MethodArgumentTypeMismatchException` is thrown → typically becomes a 400 via global exception handler.

3. **How do you make a `@RequestParam` optional?**
   `@RequestParam(required = false)` or `@RequestParam(defaultValue = "X")`.

4. **What does `@RequestBody` do?**
   Tells Spring to deserialize the request body (typically JSON) into the parameter's type using Jackson.

5. **Plain return type vs `ResponseEntity`?**
   Plain returns always produce HTTP 200. `ResponseEntity` lets you set custom status, headers, and decide whether to include a body.

6. **What's `@ResponseStatus` for?**
   Sets the default HTTP status for the method's response without using `ResponseEntity`. Also works on exception classes to map them to a status.

7. **How do you set a custom response header?**
   `ResponseEntity.ok().header("X-Custom", "value").body(...)` or via `ResponseEntity.BodyBuilder`.

8. **What's the default content type Spring uses for JSON responses?**
   `application/json`. Set automatically when returning objects from `@RestController`.

9. **Can you read multiple values from the same header?**
   Yes — use `@RequestHeader List<String> X-Custom` or take a `HttpHeaders` parameter directly.

10. **What library deserializes the JSON body by default?**
    Jackson, brought in by `spring-boot-starter-web`.

---

## 4. DTOs at the API boundary (recap)

Covered fully in Phase 1 chapter 5. Quickly:

The controller's contract is in DTOs, not entities. Why:

- **Lazy-loading risk** — entity associations break when serialized outside a transaction.
- **API/DB decoupling** — rename a column, the API contract doesn't change.
- **Exposure control** — internal fields (audit columns, soft-delete markers, `@Version`) shouldn't go on the wire.
- **Validation lives on input DTOs**, not entities.

```java
public record CreateBookRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Pattern(regexp = "\\d{13}") String isbn,
    @NotNull Long authorId
) {}

public record BookResponse(
    Long id,
    String title,
    String isbn,
    String authorName,
    LocalDate publishedAt
) {}
```

**Use Java records for DTOs.** Immutable, compact, auto-generated equals/hashCode/toString. Final, by design — perfect for transfer objects.

The conversion entity ↔ DTO happens in the **service layer**, not the controller.

### Chapter 4 — 10 self-quiz questions

1. **Why never return entities from controllers?**
   Lazy-loading risks, exposes internal fields, couples API shape to DB schema, makes refactoring break clients.

2. **Why are Java records ideal for DTOs?**
   Immutable, terse syntax, auto equals/hashCode/toString, perfect fit for transfer objects which shouldn't be mutated post-creation.

3. **Where does the entity-to-DTO mapping happen?**
   In the service layer (or a dedicated mapper class). Never in the controller or repository.

4. **Can a single entity map to multiple DTOs?**
   Yes — and you'll often have `BookSummary`, `BookResponse`, `CreateBookRequest`, `UpdateBookRequest` all backed by one entity.

5. **Why do request DTOs have validation annotations but entities usually don't?**
   Validation is an input-boundary concern. Once data is in the DB, it's already valid. Re-validating on every load is wasteful.

6. **Can a DTO have JPA annotations?**
   Technically yes, but don't. JPA annotations on a DTO mean the DTO is an entity — defeats the separation. Keep them on opposite sides of the divide.

7. **What's MapStruct?**
   Annotation processor that generates entity ↔ DTO mapper implementations at compile time. Fast, type-safe, no reflection.

8. **Is a 1:1 mapping (DTO mirrors entity) ever useful?**
   Yes — it's the simplest starting point. The point of DTOs isn't to be different from entities; it's to decouple. Even a 1:1 DTO insulates the API contract from future entity changes.

9. **What's the cost of using DTOs?**
   Mapping code (often 1 method per direction). For small projects, negligible. For large ones, use MapStruct.

10. **Are records the only valid DTO style?**
    No — plain POJOs work, Lombok `@Value` works, traditional classes with constructors and getters work. Records are just the cleanest modern idiom.

---

## 5. Validation with Bean Validation

Two pieces: **declare constraints on DTO fields**, **trigger validation with `@Valid` in the controller**.

### Common annotations

| Annotation | Meaning |
|---|---|
| `@NotNull` | Must not be null. Empty string OK. |
| `@NotBlank` | String must not be null or whitespace-only. |
| `@NotEmpty` | Collection/String must not be null or empty. |
| `@Size(min=, max=)` | Length bounds for String/Collection. |
| `@Min(n)` / `@Max(n)` | Numeric bounds. |
| `@Positive` / `@PositiveOrZero` | For numbers. |
| `@Email` | Valid email format. |
| `@Pattern(regexp = "...")` | Regex check. |
| `@Past` / `@Future` | Date constraints. |

The annotations come from `jakarta.validation.constraints.*` (was `javax.validation.constraints.*` before Jakarta EE 9).

### Triggering validation

`@Valid` on a controller parameter triggers the constraints:

```java
@PostMapping
public ResponseEntity<BookResponse> create(@Valid @RequestBody CreateBookRequest req) { ... }
```

If any constraint fails, Spring throws `MethodArgumentNotValidException` **before your method body runs**. With centralized exception handling (chapter 8), this becomes a 400 response with field-level error details.

### Nested validation

`@Valid` cascades into nested objects when you also put it on the field:

```java
public record CreateOrderRequest(
    @NotNull Long customerId,
    @NotEmpty List<@Valid OrderLineDto> lines    // @Valid here cascades
) {}

public record OrderLineDto(
    @NotNull Long productId,
    @Min(1) int quantity
) {}
```

### Input validation vs business validation

Two different things:

| | Input validation | Business validation |
|---|---|---|
| **Where** | On DTOs, runs before controller body | In services, requires DB or external state |
| **Examples** | "isbn matches regex", "title not blank", "quantity >= 1" | "isbn already exists", "customer is suspended", "stock is sufficient" |
| **Status on failure** | 400 (or 422) | 409, 422, or domain-specific 4xx |

Input validation is fast and cheap. Business validation often hits the DB. Don't mix them up.

### Custom validators

When the built-in annotations aren't enough, write your own:

```java
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidUrlValidator.class)
public @interface ValidUrl {
    String message() default "must be a valid URL";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class ValidUrlValidator implements ConstraintValidator<ValidUrl, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) return true; // @NotNull handles null
        try { new URL(value); return true; }
        catch (MalformedURLException e) { return false; }
    }
}
```

Then use `@ValidUrl` on DTO fields like any other constraint.

### Chapter 5 — 10 self-quiz questions

1. **What annotation triggers validation on a controller parameter?**
   `@Valid` (`jakarta.validation.Valid`).

2. **Difference between `@NotNull`, `@NotBlank`, `@NotEmpty`?**
   `@NotNull`: not null (empty string OK). `@NotEmpty`: not null and not empty (for Strings/Collections). `@NotBlank`: not null and has non-whitespace characters (Strings only).

3. **What exception is thrown when `@Valid` validation fails?**
   `MethodArgumentNotValidException`.

4. **What HTTP status maps to validation failure?**
   400 Bad Request, conventionally. Some teams use 422 Unprocessable Entity for semantic validation errors.

5. **Where does validation run — before or after the controller method body?**
   Before. If validation fails, your method body never executes.

6. **How does nested validation work?**
   Put `@Valid` on the field that holds nested objects. Spring cascades validation into each one.

7. **Input validation vs business validation — example of each?**
   Input: "isbn matches pattern", checked in DTO. Business: "isbn already in DB", checked in service.

8. **Can you write custom constraints?**
   Yes — define a `@interface` with `@Constraint(validatedBy = ...)` and a `ConstraintValidator` implementation.

9. **Does Bean Validation work on plain method parameters (not from `@RequestBody`)?**
   Yes — annotate the class with `@Validated` and put constraints on individual parameters. Works on service methods too.

10. **Is validation enabled out of the box?**
    Yes, via `spring-boot-starter-validation`. Without it, `@Valid` is silently ignored.

---

## 6. HTTP status codes — when to use which

### 2xx — Success

| Code | Meaning | When |
|---|---|---|
| **200 OK** | Generic success | GET that returns a body |
| **201 Created** | Resource created | POST that creates something. Include a `Location` header pointing to the new resource. |
| **202 Accepted** | Async — work queued, not done | When the request kicks off background work |
| **204 No Content** | Success, no body | DELETE; PUT/PATCH that returns nothing |

### 3xx — Redirection

| Code | Meaning | When |
|---|---|---|
| **301 Moved Permanently** | Cacheable redirect | Permanent URL changes |
| **302 Found** | Temporary redirect | Most app-level redirects |
| **303 See Other** | "Go look at this other URL via GET" | Post-redirect-get pattern |
| **307 Temporary Redirect** | Same method preserved on new URL | Like 302 but POST stays POST |
| **308 Permanent Redirect** | Like 301, method preserved | Rare in app code |

### 4xx — Client errors

| Code | Meaning | When |
|---|---|---|
| **400 Bad Request** | Malformed request | Invalid JSON, validation failures, wrong types |
| **401 Unauthorized** | Not authenticated | No/invalid credentials |
| **403 Forbidden** | Authenticated, not allowed | Auth ok, action not permitted |
| **404 Not Found** | Resource doesn't exist | GET /api/books/999 when 999 doesn't exist |
| **405 Method Not Allowed** | Wrong method | POST to a GET-only endpoint |
| **409 Conflict** | State conflict | Unique constraint violation, optimistic-lock conflict |
| **410 Gone** | Permanently removed | Soft-deleted record explicitly |
| **422 Unprocessable Entity** | Syntactically valid, semantically wrong | Validation failure (alternative to 400) |
| **429 Too Many Requests** | Rate limited | API rate limit hit |

### 5xx — Server errors

| Code | Meaning | When |
|---|---|---|
| **500 Internal Server Error** | Unexpected exception | Catch-all for crashes |
| **503 Service Unavailable** | Temporary overload / dependency down | DB down, queue full |

### Rules of thumb

- **2xx for success, 4xx for client mistakes, 5xx for your mistakes.** Don't return 200 with `{"success": false}` — use the right status code.
- **`Location` header on 201** pointing to the new resource. Convention since the dawn of REST.
- **400 vs 422** — pick one for validation failures and stick to it across the API. Don't randomize.
- **404 vs 403** — return 404 if the user shouldn't even know the resource exists (avoid leaking via "you can't see this"). 403 if their existence is OK to expose.
- **Never 200 + error body.** Clients can't reliably parse "is this an error?" Use HTTP status.

### Chapter 6 — 10 self-quiz questions

1. **200 vs 201?**
   200 for successful read/update. 201 specifically for "I created a new resource as a result of this request." Often with a `Location` header.

2. **What's 204 for?**
   Success, but no body in the response. DELETE; PUT/PATCH that doesn't return the modified resource.

3. **400 vs 422?**
   400 is "your request is malformed" (bad JSON, wrong types). 422 is "your request is well-formed but semantically invalid" (validation rules failed). Some APIs use 400 for both.

4. **404 vs 410?**
   404: "I don't know about this resource (never did, or I won't say)." 410: "this resource used to exist but is permanently gone."

5. **401 vs 403?**
   401: "you didn't authenticate (or your credentials are invalid)." 403: "you are authenticated, but you can't do this."

6. **What's 409 for?**
   Conflict with the resource's current state. Unique-constraint violations, optimistic-lock conflicts.

7. **Right status for "unique constraint violated"?**
   409 Conflict.

8. **Right status for "I parsed your JSON fine but the business rule failed"?**
   422 Unprocessable Entity (or 400 if you've standardized on that).

9. **Should you return 5xx for client mistakes?**
   No. 5xx means your service screwed up. Client mistakes are 4xx.

10. **What header should accompany a 201 Created response?**
    `Location` — pointing to the URL of the newly created resource.

---

## 7. Redirects: 301 vs 302

### The two codes

**301 Moved Permanently**
- "This URL is permanently somewhere else."
- Browsers cache aggressively (often forever).
- Search engines update their index.

**302 Found** (temporary redirect)
- "Currently the resource is over there, but this URL is still the canonical one."
- Browsers don't cache the redirect.

### Why URL shorteners use 302

If you use 301:

1. **Browsers cache the redirect.** Once seen, `bit.ly/foo → real.com/A` is cached locally. The browser stops asking your service.
2. **Click analytics break.** Only the first click is recorded. Every subsequent visit bypasses your server.
3. **You lose the ability to retarget.** Even if the destination changes, cached browsers go to the old place.

With 302:

1. The browser re-requests `/{shortCode}` every time.
2. You record every click.
3. You can change the target.

**Production URL shorteners universally use 302** for these reasons.

### In Spring Boot

```java
@GetMapping("/{shortCode}")
public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
    String longUrl = urlService.resolve(shortCode);
    return ResponseEntity.status(HttpStatus.FOUND)            // 302
                         .location(URI.create(longUrl))
                         .build();
}
```

`HttpStatus.FOUND` is the constant for 302. The `Location` header tells the browser where to go. No response body needed.

### 307 — when method preservation matters

302 historically had loose behavior — some clients would change a POST to GET when following the redirect. 307 was added to require the method be preserved. For a GET-only redirect (URL shortener), 302 is fine. For a redirect that needs to preserve POST/PUT, use 307.

### Chapter 7 — 10 self-quiz questions

1. **301 vs 302 in behavior?**
   301: cacheable, browsers remember and skip the original URL. 302: not cached, every visit hits the redirect endpoint.

2. **Why does a URL shortener use 302?**
   To force every visit through the service, capturing analytics and preserving the ability to change destinations.

3. **What HTTP header tells the browser where to go?**
   `Location`.

4. **What's 307 for?**
   Temporary redirect that preserves the HTTP method (POST stays POST). Important for non-GET redirects.

5. **What `HttpStatus` constant maps to 302?**
   `HttpStatus.FOUND`.

6. **Why does 301 break click analytics?**
   Browsers cache the redirect. After the first visit, requests go directly to the target without hitting your service.

7. **Can you change the destination after sending 301?**
   You can change the server-side mapping, but clients with cached 301s will keep going to the old destination until the cache expires.

8. **What body does a redirect response usually have?**
   Empty. The `Location` header carries the redirect target; no body is needed.

9. **Right status for "submit form → go to confirmation page"?**
   303 See Other (the canonical post-redirect-get pattern).

10. **What's 308 for?**
    Permanent redirect that preserves the method. Like 301 but POST stays POST. Rare in app code.

---

## 8. Exception handling with `@RestControllerAdvice`

The naïve approach: try/catch in every controller method. Tedious, repetitive, inconsistent.

The canonical approach: one class with `@RestControllerAdvice` that maps exceptions to responses globally.

### The advice class

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(EntityNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "Resource conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage));

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errors", fieldErrors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAny(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }
}
```

### How it works

When a controller method throws, Spring walks up the stack looking for a registered `@ExceptionHandler`. `@RestControllerAdvice` registers handlers globally. The matching handler's return value becomes the HTTP response.

**Order of matching:** Spring picks the most specific handler first. `EntityNotFoundException` matches its specific handler; `RuntimeException` falls through to the `Exception.class` catch-all.

### Which exceptions to handle

- **Domain exceptions** you throw from services (`ShortCodeNotFoundException`, `UrlExpiredException`) → map to 404, 410, 409, etc.
- **`MethodArgumentNotValidException`** — `@Valid` validation failures → 400.
- **`HttpMessageNotReadableException`** — body isn't valid JSON → 400.
- **`MethodArgumentTypeMismatchException`** — path/query param wrong type → 400.
- **`DataIntegrityViolationException`** — DB constraint violations → 409.
- **`OptimisticLockException`** — concurrent edit conflict → 409.
- **`Exception.class` fallback** — catch-all → 500.

### RFC 7807 / Problem Details — `ProblemDetail`

The modern standard for error responses. Spring 6 / Boot 3 has built-in support:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Short code 'Xy7Q2k' not found",
  "instance": "/api/urls/Xy7Q2k/stats"
}
```

Standard fields:
- **`type`** — URI identifying the problem type (often a docs link)
- **`title`** — short human-readable summary
- **`status`** — HTTP status code (mirrored)
- **`detail`** — specific explanation
- **`instance`** — URI of the specific occurrence

You can extend it with custom fields (e.g., validation field errors). Spring serializes them alongside the standard ones via `setProperty(...)`.

Why it's the standard: clients parse it predictably. Tools (Postman, OpenAPI) understand it. Beats every team inventing its own error JSON.

### Don't leak stack traces

**Never** put exception details into responses unsanitized. Stack traces reveal:
- Internal package structure (helps attackers map your code).
- Library versions (helps CVE targeting).
- Sometimes accidentally exposed secrets in messages.

Pattern: **log full exception server-side, return clean public message to the client.**

### Custom domain exceptions

Define them as plain `RuntimeException` subclasses:

```java
public class ShortCodeNotFoundException extends RuntimeException {
    public ShortCodeNotFoundException(String code) {
        super("Short code not found: " + code);
    }
}
```

Throw from services:

```java
@Service
public class UrlService {
    public String resolve(String code) {
        return repo.findByShortCode(code)
            .map(ShortUrl::getLongUrl)
            .orElseThrow(() -> new ShortCodeNotFoundException(code));
    }
}
```

Handle in the advice:

```java
@ExceptionHandler(ShortCodeNotFoundException.class)
public ResponseEntity<ProblemDetail> handleNotFound(ShortCodeNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
}
```

Clean separation: services express failures in domain terms, advice translates to HTTP.

### Chapter 8 — 10 self-quiz questions

1. **What does `@RestControllerAdvice` do?**
   Marks a class whose `@ExceptionHandler` methods apply globally to all `@RestController` exceptions, producing structured error responses.

2. **What's `@ExceptionHandler` for?**
   Declares a method that handles a specific exception type. Its return value becomes the HTTP response when that exception bubbles up.

3. **How does Spring pick which handler to use?**
   The most specific matching exception type wins. `RuntimeException` falls through to `Exception.class` if a more specific match isn't registered.

4. **What's RFC 7807 / `ProblemDetail`?**
   The standard format for HTTP error responses: a JSON body with `type`, `title`, `status`, `detail`, `instance`. Spring 6 has built-in support via the `ProblemDetail` class.

5. **What HTTP status maps to `MethodArgumentNotValidException`?**
   400 Bad Request (or 422 Unprocessable Entity, by convention).

6. **What HTTP status maps to `DataIntegrityViolationException`?**
   409 Conflict — it usually indicates a unique constraint or FK violation.

7. **Why never return stack traces to clients?**
   They leak internal structure, library versions, and sometimes secrets, helping attackers map your system.

8. **Where should the actual exception stack trace be logged?**
   Server-side, with full detail, at error level. Not in the response.

9. **What's the fallback `Exception.class` handler for?**
   Catch-all for unexpected exceptions you didn't explicitly handle. Returns 500 with a generic message; the real stack trace is logged.

10. **What's the canonical way to express a "not found" failure from a service?**
    Throw a domain `RuntimeException` (or `EntityNotFoundException`) and let the advice translate it to 404. Cleaner than returning `Optional` all the way to the controller.

---

## 9. Common pitfalls to avoid

1. **Business logic in the controller.** If your controller method has anything beyond "parse input, call service, return DTO," you've broken the layering.
2. **Returning entities directly.** Don't. DTOs always.
3. **Inline try/catch in controllers.** Use `@RestControllerAdvice`.
4. **Mixing 400 and 422 randomly.** Pick one convention for validation failures; document it; stick to it.
5. **Using 301 for a URL shortener redirect.** Will break analytics. Use 302.
6. **Returning stack traces in error responses.** Security risk.
7. **`@Transactional` on a controller method.** Transactions belong in services.
8. **Field injection (`@Autowired private`).** Use constructor injection for testability and immutability.
9. **`open-in-view: true` plus returning lazy entities.** Already turned off in `application.yml`; don't accidentally turn it back on.
10. **200 OK with `{"success": false}` bodies.** Use HTTP status codes properly.

---

## 10. Interview probes you should be ready for

- "What's the difference between `@Controller` and `@RestController`?"
- "Where does input validation happen?"
- "How does `@Valid` work?"
- "Difference between `@PathVariable` and `@RequestParam`?"
- "What's `ResponseEntity` and when do you use it instead of plain returns?"
- "Walk me through error handling in your API."
- "Why 302 instead of 301 for a URL shortener redirect?"
- "What's RFC 7807 / Problem Details?"
- "How would you respond if validation fails? What status code?"
- "How do you avoid leaking stack traces to clients?"
- "What's the right status code for 'this resource doesn't exist'? 'Unique constraint violated'? 'Authenticated but not allowed'?"
- "How does Spring know which `@ExceptionHandler` to invoke?"
- "Why is constructor injection preferred over field injection?"
- "What library serializes responses to JSON by default in Spring Boot?"
- "How would you handle a `MethodArgumentNotValidException`?"
- "What's the difference between input validation and business validation?"
- "When would you use 422 instead of 400?"
- "What's the right HTTP status when creating a resource?"
- "How do you express a custom domain exception cleanly across layers?"

---

## 11. Glossary

| Term | Meaning |
|---|---|
| **`@RestController`** | `@Controller` + `@ResponseBody`. Methods return data, Spring serializes to JSON. |
| **`@RequestMapping`** | Maps HTTP path/method to a controller method. Class-level prefixes paths. |
| **`@GetMapping` / `@PostMapping` / etc.** | Short forms for `@RequestMapping(method=GET)` etc. |
| **`@PathVariable`** | Extracts a value from the URL path into a method parameter. |
| **`@RequestParam`** | Extracts a value from the query string. |
| **`@RequestBody`** | Deserializes the request body (typically JSON) into a Java object. |
| **`@RequestHeader`** | Reads HTTP request headers. |
| **`ResponseEntity`** | Wrapper for response with status, headers, and body. |
| **`@ResponseStatus`** | Sets default HTTP status for a controller method or exception class. |
| **`@Valid`** | Triggers Bean Validation on a parameter. |
| **Bean Validation / Jakarta Validation** | The validation spec (`@NotNull`, `@Size`, etc.). |
| **`MethodArgumentNotValidException`** | Thrown when `@Valid` validation fails. |
| **`HttpMessageNotReadableException`** | Thrown when the request body isn't parseable JSON. |
| **`MethodArgumentTypeMismatchException`** | Thrown when a path/query param can't be converted to the declared type. |
| **`@RestControllerAdvice`** | Global exception-handler class for `@RestController`s. |
| **`@ExceptionHandler`** | Marks a method as the handler for a specific exception type. |
| **`ProblemDetail`** | Spring 6+ class for RFC 7807 error responses. |
| **RFC 7807** | "Problem Details for HTTP APIs" — standard error response format. |
| **DTO** | Data Transfer Object — dumb data class for API boundary. |
| **301 / 302 / 307 / 308** | Redirect status codes. 302 is the default for app-level redirects. |
| **`HttpStatus.FOUND`** | Java constant for 302. |
| **Constructor injection** | Inject dependencies via constructor parameters. Preferred over field injection. |
| **Field injection** | `@Autowired` on a field. Discouraged. |
| **Content negotiation** | Spring's mechanism for picking the response media type based on `Accept` header. |
| **Jackson** | Default JSON serializer in Spring Boot Web. |
| **`@Controller`** | Traditional MVC controller returning view names. Not used for JSON APIs. |

---

When you're back, ask for the approach to Task 2.2.
