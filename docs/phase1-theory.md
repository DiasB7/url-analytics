# Phase 1 Theory: JPA, Hibernate, Spring Data, Transactions, Flyway

A standalone deep-dive. All examples use generic domains (Book/Author, Customer/Order, Product, Employee/Department) so the concepts stay separated from any specific project.

Read this when you have time. By the end, you should be able to talk fluently about every concept here without hedging.

---

## Table of contents

1. [The big picture: why JPA exists](#1-the-big-picture-why-jpa-exists)
2. [Entities — Java classes mapped to tables](#2-entities--java-classes-mapped-to-tables)
3. [The persistence context — Hibernate's working memory](#3-the-persistence-context--hibernates-working-memory)
4. [Spring Data repositories](#4-spring-data-repositories)
5. [Transactions](#5-transactions--the-core-mental-model)
6. [Fetch types, lazy/eager, OSIV, N+1](#6-fetch-types-lazyeager-osiv-n1)
7. [Flyway — schema as code](#7-flyway--schema-as-code)
8. [Common errors and what they mean](#8-common-errors-and-what-they-mean)
9. [Interview probes you should be ready for](#9-interview-probes-you-should-be-ready-for)
10. [Glossary](#10-glossary)

---

## 1. The big picture: why JPA exists

A Java application has objects (`Book`, `Author`, `Order`). A database has rows in tables. Bridging these two is **object-relational mapping (ORM)**.

Before ORMs, you wrote raw JDBC:

```java
PreparedStatement ps = conn.prepareStatement("SELECT * FROM books WHERE id = ?");
ps.setLong(1, 42);
ResultSet rs = ps.executeQuery();
if (rs.next()) {
    Book book = new Book();
    book.setId(rs.getLong("id"));
    book.setTitle(rs.getString("title"));
    book.setAuthorId(rs.getLong("author_id"));
}
```

For every entity, you wrote ~50 lines of this. Plus connection handling, transaction handling, mapping result sets, lazy-loading associations manually, caching, etc.

**JPA (Jakarta Persistence API)** is a *specification* that defines a standard ORM API for Java. It says: "here's how you should annotate entities, here's the interface of an `EntityManager`, here's the query language (JPQL), here's the transaction abstraction." JPA itself is just interfaces and rules.

**Hibernate** is an *implementation* of JPA. Other implementations exist (EclipseLink, OpenJPA) but Hibernate has won the ecosystem. When Spring Boot pulls in `spring-boot-starter-data-jpa`, you get Hibernate as the implementation by default.

**Spring Data JPA** sits *on top of* JPA. It adds the repository abstraction — you write interfaces, Spring generates implementations. Without Spring Data, you'd inject an `EntityManager` and write queries by hand.

### The three layers — keep them straight in your head

```
┌─────────────────────────────────────────────┐
│  Spring Data JPA                            │  Repositories, derived queries
│  (interfaces + code generation)             │  @Query, paging
├─────────────────────────────────────────────┤
│  JPA (Jakarta Persistence API)              │  Specification: @Entity, EntityManager,
│  (specification — interfaces only)          │  JPQL, transactions
├─────────────────────────────────────────────┤
│  Hibernate                                  │  Implementation: actually generates SQL,
│  (the implementation)                       │  manages sessions, caches, dirty checking
├─────────────────────────────────────────────┤
│  JDBC                                       │  Java's low-level DB API
└─────────────────────────────────────────────┘
```

**Interview probe**: *"Spring Data, JPA, Hibernate — what's the difference?"*

> JPA is the specification, Hibernate is the implementation, Spring Data is the repository abstraction on top. JPA defines what an `EntityManager` looks like; Hibernate provides a working one; Spring Data wraps it with auto-generated repositories.

### Mental model: who does what when you call `bookRepo.save(book)`

1. **Spring Data** receives the call, delegates to the generic implementation it generated for your interface.
2. The implementation calls `EntityManager.persist(book)` — this is the **JPA** API.
3. Hibernate (the JPA implementation) registers the entity in the persistence context, queues an INSERT.
4. At transaction commit, Hibernate flushes — generates SQL `INSERT INTO books (...) VALUES (...)`, sends it down to **JDBC**.
5. JDBC sends bytes to the Postgres driver, which sends them over TCP to the DB.

Four layers. Each one a real abstraction with a real purpose.

---

## 2. Entities — Java classes mapped to tables

An entity is a class annotated `@Entity`. Each instance corresponds to one row.

```java
@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "isbn", unique = true, length = 13)
    private String isbn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Book() {}  // JPA needs a no-arg constructor

    // getters / setters
}
```

### The non-negotiable rules

- **`@Entity`** + a **no-arg constructor**. Hibernate uses reflection to instantiate, then fills fields. If the only constructor takes args, Hibernate cannot create the object. You can make the no-arg constructor `protected` to discourage direct use from app code.
- **`@Id`** field, exactly one. Maps to the primary key.
- **The class must not be `final`**. Hibernate creates runtime proxies (subclasses) for lazy loading — it can't subclass a final class.
- **Fields should not be `final`** for the same reason — Hibernate sets them via reflection.

### `@GeneratedValue` strategies

| Strategy | What it does | When to use |
|---|---|---|
| `IDENTITY` | DB column is auto-increment / SERIAL. PK assigned on INSERT. | Default for Postgres / MySQL. Simple. **Breaks JDBC batch insert** because each INSERT must return its PK before the next can run. |
| `SEQUENCE` | Uses a DB sequence object (`CREATE SEQUENCE`). Hibernate fetches a value, then INSERTs. | Postgres-friendly. Allows batching. Hibernate can pre-fetch ranges of IDs (allocationSize) for performance. |
| `UUID` | Generates a UUID in the JVM. | Distributed systems, no DB round-trip for IDs. Larger key, can hurt index performance. |
| `AUTO` | Let Hibernate decide based on the DB dialect. | Avoid — too implicit. Pick one explicitly. |

For Postgres, the canonical choice in 2026 is `SEQUENCE` with a tuned `allocationSize` for high-write tables, or `IDENTITY` for simpler cases. `UUID` for distributed write systems where you don't want a central counter.

**Sequence example:**

```java
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "book_seq")
@SequenceGenerator(name = "book_seq", sequenceName = "books_id_seq", allocationSize = 50)
private Long id;
```

`allocationSize = 50` means Hibernate fetches 50 IDs from the sequence at a time and hands them out in memory. Saves 49 round-trips per 50 inserts.

### `@Column` — the common knobs

- `name` — column name. Skip if you let Hibernate derive from the field name (snake_case via naming strategy).
- `nullable = false` — DB constraint.
- `unique = true` — DB unique constraint (often you set this in the migration instead).
- `length = N` — for VARCHAR sizing.
- `updatable = false` — Hibernate excludes the column from UPDATE statements. Useful for `created_at`.
- `insertable = false, updatable = false` — read-only mapping, useful for computed columns.

### Common field type mappings (Postgres)

| Java type | Postgres column type | Notes |
|---|---|---|
| `Long` | `BIGINT` / `BIGSERIAL` | Default for IDs. |
| `Integer` | `INTEGER` | |
| `String` | `VARCHAR(n)` | Set `length` on `@Column`. For arbitrary text, use `@Column(columnDefinition = "TEXT")`. |
| `Instant` / `OffsetDateTime` | `TIMESTAMPTZ` | Always prefer the time-zoned variant for stored timestamps. |
| `LocalDate` | `DATE` | |
| `BigDecimal` | `NUMERIC(p, s)` | Use for money. **Never use `double` for currency** — floating-point rounding. |
| `boolean` | `BOOLEAN` | |
| Enum | `VARCHAR` (with `@Enumerated(EnumType.STRING)`) | Never use `ORDINAL` — adding an enum value reorders persisted ints. |
| `byte[]` | `BYTEA` | Binary data. |

**Enum example:**

```java
public enum OrderStatus { PENDING, PAID, SHIPPED, CANCELLED }

@Entity
public class Order {
    @Enumerated(EnumType.STRING)   // stored as 'PENDING', 'PAID', etc.
    @Column(nullable = false)
    private OrderStatus status;
}
```

If you used `EnumType.ORDINAL`, the DB would store `0,1,2,3`. The day someone inserts a new value `REFUNDED` between `PAID` and `SHIPPED`, every existing row gets a silently different meaning. **Always `EnumType.STRING`.**

### Relationships — the four cardinalities

Each cardinality has a "many side" and a "one side":

#### `@ManyToOne` — the "child" side

```java
@Entity
public class Book {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private Author author;
}
```

The foreign key column is on the **owning side** — the side that holds the FK. For `@ManyToOne`, that's always the many side.

#### `@OneToMany(mappedBy = ...)` — the "parent" side (inverse)

```java
@Entity
public class Author {
    @OneToMany(mappedBy = "author")
    private List<Book> books;
}
```

`mappedBy = "author"` tells Hibernate: "the `Book.author` field owns this relationship; I'm just the inverse view." No FK on the author's table — the FK is `books.author_id`.

**Important:** Without `mappedBy`, Hibernate assumes you want a *separate* join table for the relationship. Always set `mappedBy` on the inverse side of a one-to-many.

#### `@OneToOne`

```java
@Entity
public class Customer {
    @OneToOne(mappedBy = "customer")
    private CustomerProfile profile;
}

@Entity
public class CustomerProfile {
    @OneToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;
}
```

Common for splitting a heavy "details" table off a lean primary table — e.g., `User` (lean, hot-path queries) and `UserProfile` (bio, avatar URL, settings).

#### `@ManyToMany`

```java
@Entity
public class Book {
    @ManyToMany
    @JoinTable(
        name = "book_tags",
        joinColumns = @JoinColumn(name = "book_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags;
}
```

Requires a join table. **In production, most teams replace `@ManyToMany` with an explicit join entity** (`BookTag`) because real-world join tables almost always grow extra columns (timestamps, who added the tag, etc.).

**Explicit join entity pattern:**

```java
@Entity
public class BookTag {
    @Id @GeneratedValue Long id;

    @ManyToOne Book book;
    @ManyToOne Tag tag;

    @Column(nullable = false) Instant addedAt;
    @Column(nullable = false) String addedBy;
}
```

Now `Book.tags` becomes `@OneToMany(mappedBy = "book") Set<BookTag>` and you can attach metadata to the association.

### `equals` / `hashCode` — the trap

The naïve "generate equals/hashCode based on `id`" breaks JPA. Why:

1. You create `new Book()` → `id` is null.
2. You add it to a `Set<Book>`. `hashCode()` returns 0 (based on null id).
3. You save it. Hibernate sets `id = 42`.
4. The set now contains an object whose hashCode is **different from where it's bucketed** in the set. `set.contains(book)` returns false even though the book is in the set.

Two acceptable solutions:

- **Business key**: equals/hashCode based on a natural key like `isbn` that doesn't change.
- **Constant hashCode** for new entities: `return 31;` always, plus equals based on `id` with null handling. Inefficient for big sets, but correct.

For most projects, **just don't put entities in `Set`s** and don't override equals/hashCode at all. It avoids the whole class of bugs.

### Entity lifecycle states (Hibernate calls them this)

| State | Meaning |
|---|---|
| **Transient** | New `new Book()`. Not in DB, no `id`, no session knows about it. |
| **Managed / Persistent** | Inside a transaction, attached to a session. Hibernate tracks changes (dirty checking). |
| **Detached** | Was managed, but the session closed. Has an `id`, but Hibernate no longer tracks it. |
| **Removed** | Marked for deletion. Will be DELETEd on flush. |

```java
Book b = new Book();          // TRANSIENT
b.setTitle("Foo");

em.persist(b);                // MANAGED — Hibernate tracks it. id assigned.
b.setTitle("Bar");            // dirty checking will UPDATE this on commit.

tx.commit();                  // SQL fires.
// Session closes here.
                              // DETACHED — id still set, but changes won't sync.

em.remove(b);                 // REMOVED (if reattached first via merge)
```

### Dirty checking — auto-UPDATE without calling `save()`

While an entity is **managed**, Hibernate keeps a snapshot of its field values from when it was loaded. At flush time, it compares each field to the snapshot. If anything changed, it generates an UPDATE.

```java
@Transactional
public void renameBook(Long id, String newTitle) {
    Book b = bookRepo.findById(id).orElseThrow();
    b.setTitle(newTitle);
    // No save() call. Dirty checking will UPDATE at commit.
}
```

This surprises people coming from raw JDBC. You mutate the object; Hibernate translates that into SQL automatically.

**Common gotcha:** people call `bookRepo.save(b)` in the middle of a transaction, thinking it's required. It isn't, but it doesn't hurt either — for a managed entity, `save()` returns the same entity and dirty checking still does the work.

**Interviewer probe:** *"What's the difference between detached and transient?"*

> Transient has never been persisted. Detached has been persisted but the session that managed it has closed.

---

## 3. The persistence context — Hibernate's working memory

The **persistence context** is a cache of all managed entities within a session/transaction. Every entity you `find()` or `persist()` goes into it. While it's there:

- **Identity guarantee**: `em.find(Book.class, 42L) == em.find(Book.class, 42L)` returns the *same* object instance. The persistence context guarantees one in-memory representation per (entity type, id).
- **Dirty checking**: mutations are tracked.
- **Write-behind**: SQL doesn't fire immediately on `persist()` — it's queued and flushed at commit (or before a query that needs current state).
- **First-level cache**: a second `find` for the same id doesn't hit the DB.

This is *per-transaction*. When the transaction commits, the persistence context is cleared.

### Flush vs commit

- **Flush**: Hibernate writes queued SQL to the DB. Can be triggered explicitly with `em.flush()`, automatically before queries that need consistency, or implicitly on commit.
- **Commit**: ends the transaction, makes changes visible to other connections.

You can flush without committing (forcing the SQL to run for testing) and you can commit without explicitly flushing (it's done for you).

### Write-behind in action

```java
@Transactional
public void doStuff() {
    Book b1 = new Book("A");
    em.persist(b1);     // No SQL yet. Just queued.

    Book b2 = new Book("B");
    em.persist(b2);     // Still no SQL.

    b1.setTitle("A!");  // Still no SQL.

    // Method returns → commit → flush → batch INSERT for both,
    // then UPDATE for b1's title change (or Hibernate merges if smart).
}
```

This is why Hibernate can do batch inserts. It collects changes, then sends them in one go.

**Caveat:** `IDENTITY` PK generation breaks this. Because the PK is assigned by the DB on INSERT, Hibernate has to INSERT immediately to know the ID. That's why `SEQUENCE` is preferred for high-write tables.

### Second-level cache (not used in this project, but interviewable)

The persistence context is per-transaction (first-level cache). Hibernate also supports a **second-level cache** that lives across transactions and even across nodes (with Ehcache, Hazelcast, Infinispan).

You opt in per-entity:

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Country { ... }
```

Use for read-mostly, slowly-changing reference data (currencies, country codes, configuration). Don't use for hot, frequently-written tables — cache invalidation gets painful.

---

## 4. Spring Data repositories

You define an interface:

```java
public interface BookRepository extends JpaRepository<Book, Long> {
    Optional<Book> findByIsbn(String isbn);
    List<Book> findByAuthorId(Long authorId);
    List<Book> findByTitleContainingIgnoreCase(String fragment);
    long countByAuthorId(Long authorId);
    boolean existsByIsbn(String isbn);
    void deleteByIsbn(String isbn);
}
```

At startup, Spring scans for repository interfaces. For each one, it:

1. Generates a proxy class implementing the interface.
2. For each method, parses the method name (`findBy` + property names + operators) into a JPQL query.
3. Validates the JPQL at startup — invalid method names fail fast, before any HTTP request.

### The repository hierarchy

```
Repository<T, ID>          ← marker interface, no methods
    └── CrudRepository<T, ID>           ← save, findById, delete, count, existsById
            └── PagingAndSortingRepository<T, ID>   ← + paging
                    └── JpaRepository<T, ID>       ← + flush, batch, save-all
```

In modern Spring Data, you extend `JpaRepository`. Older code sometimes uses `CrudRepository` directly when paging isn't needed.

### Method-name keywords (cheat sheet)

| Keyword | SQL equivalent | Example |
|---|---|---|
| `And`, `Or` | `AND`, `OR` | `findByTitleAndAuthorId` |
| `Is`, `Equals` | `=` | `findByTitleEquals` (same as `findByTitle`) |
| `Not` | `<>` | `findByStatusNot` |
| `LessThan`, `GreaterThan` | `<`, `>` | `findByPriceLessThan` |
| `Between` | `BETWEEN` | `findByCreatedAtBetween(start, end)` |
| `Like`, `Containing`, `StartingWith`, `EndingWith` | `LIKE` | `findByTitleContaining("Foo")` → `%Foo%` |
| `IgnoreCase` | `LOWER(...)` | `findByEmailIgnoreCase` |
| `OrderBy...Asc`/`Desc` | `ORDER BY` | `findByAuthorIdOrderByCreatedAtDesc` |
| `In` | `IN (...)` | `findByIdIn(List<Long> ids)` |
| `IsNull`, `IsNotNull` | `IS NULL` | `findByDeletedAtIsNull` |
| `True`, `False` | `= true` | `findByActiveTrue` |
| `Top`, `First` | `LIMIT` | `findFirst10ByOrderByCreatedAtDesc` |
| `Distinct` | `DISTINCT` | `findDistinctByAuthorId` |

### Query approaches, from most to least magic

**Derived queries** — Spring parses the name:
```java
List<Book> findByTitleAndAuthorIdOrderByCreatedAtDesc(String title, Long authorId);
```
Fine for simple cases. Becomes unreadable beyond 3-4 conditions.

**`@Query` with JPQL** — JPA's object-oriented query language:
```java
@Query("SELECT b FROM Book b WHERE b.author.name = :name")
List<Book> findByAuthorName(@Param("name") String name);
```
JPQL works on entity field names, not column names. Hibernate translates to SQL.

**`@Query(nativeQuery = true)`** — raw SQL:
```java
@Query(value = "SELECT * FROM books WHERE LOWER(title) = LOWER(?1)", nativeQuery = true)
Optional<Book> findByTitleCaseInsensitive(String title);
```
Use when JPQL can't express what you need (DB-specific functions, window functions, CTEs).

**Modifying queries** — for UPDATE/DELETE:
```java
@Modifying
@Query("UPDATE Book b SET b.title = :title WHERE b.id = :id")
int renameBook(@Param("id") Long id, @Param("title") String title);
```
`@Modifying` is required for non-SELECT queries. Returns affected row count.

**Projections — return DTOs, not entities:**

Interface projection (Spring proxies it):
```java
public interface BookSummary {
    String getTitle();
    String getIsbn();
}

@Query("SELECT b.title AS title, b.isbn AS isbn FROM Book b")
List<BookSummary> findAllSummaries();
```

Class-based projection (constructor expression):
```java
public record BookSummary(String title, String isbn) {}

@Query("SELECT new com.foo.BookSummary(b.title, b.isbn) FROM Book b")
List<BookSummary> findAllSummaries();
```

Projections are lighter — Hibernate loads only the columns you need, doesn't manage them in the persistence context.

### Paging and sorting

```java
Page<Book> findByAuthorId(Long authorId, Pageable pageable);
```

Calling code:
```java
Pageable page = PageRequest.of(0, 20, Sort.by("createdAt").descending());
Page<Book> result = bookRepo.findByAuthorId(authorId, page);

result.getContent();        // List<Book>
result.getTotalElements();  // long, total matching rows
result.getTotalPages();     // int
result.hasNext();
```

Spring Data fires two queries: one for the page contents (with LIMIT/OFFSET) and one for the total count. If you don't need the count, return `Slice<Book>` instead — skips the count query.

### When NOT to use Spring Data

For **highly dynamic queries** (search forms where any of 10 fields might be present), the method-name approach explodes. Use Criteria API or QueryDSL or just hand-write `EntityManager` queries.

**Specifications** (a Spring Data feature) let you compose conditions dynamically:
```java
Specification<Book> spec = Specification
    .where(hasTitle(title))
    .and(hasAuthor(authorId))
    .and(createdAfter(date));
List<Book> results = bookRepo.findAll(spec);
```
Useful for filter/search endpoints. Niche but interviewable.

---

## 5. Transactions — the core mental model

A transaction = a unit of DB work that either all commits or all rolls back. Properties (the ACID acronym):

- **Atomicity** — all or nothing.
- **Consistency** — DB constraints aren't violated by a successful tx.
- **Isolation** — concurrent transactions don't see each other's intermediate state (subject to isolation level).
- **Durability** — once committed, data survives crashes.

### `@Transactional` in Spring

Put it on a service method:

```java
@Service
public class OrderService {

    @Transactional
    public Order placeOrder(Long customerId, List<Long> productIds) {
        Customer c = customerRepo.findById(customerId).orElseThrow();
        Order o = new Order(c);
        for (Long pid : productIds) {
            Product p = productRepo.findById(pid).orElseThrow();
            o.addLine(p);
            p.decrementStock();
        }
        return orderRepo.save(o);
    }
}
```

Spring wraps the method in a transactional proxy. The flow:

1. Method called → Spring starts a tx (begins DB transaction, opens persistence context).
2. Method body runs.
3. Normal return → tx commits → persistence context flushes → SQL fires → DB commits.
4. `RuntimeException` thrown → tx rolls back → no SQL fires.

### How the proxy works mechanically

Spring's `@Transactional` is implemented via **AOP proxies**. When the container instantiates your service, it creates a runtime subclass (CGLIB) or interface proxy (JDK dynamic) that wraps every public `@Transactional` method with:

```
beforeMethod:
    TransactionStatus tx = transactionManager.getTransaction(definition)
try:
    invoke real method
    transactionManager.commit(tx)
catch RuntimeException e:
    transactionManager.rollback(tx)
    rethrow
```

The proxy is what callers get when they `@Autowired OrderService`. Direct method calls on the bean don't go through the proxy — and that's the source of the famous self-invocation bug.

### Five things every Spring dev gets wrong about `@Transactional`

#### 1. Self-invocation bypasses the proxy

```java
@Service
public class FooService {

    public void outer() {
        this.inner();  // ← bypasses proxy! No transaction!
    }

    @Transactional
    public void inner() { ... }
}
```

`@Transactional` is implemented by a proxy that wraps the bean. When `outer()` calls `this.inner()`, the call goes directly on the bean, not through the proxy. The proxy never sees `inner()` being invoked, so no transaction is started.

Fix options:
- Split into two beans.
- Inject self via `@Autowired private FooService self;` then call `self.inner()`.
- Refactor to put `@Transactional` on `outer()` instead.

#### 2. Default rollback is `RuntimeException` only

```java
@Transactional
public void doWork() throws IOException {  // checked exception
    // ... if this throws IOException, the transaction COMMITS, not rolls back
}
```

To roll back on checked exceptions: `@Transactional(rollbackFor = IOException.class)` or `rollbackFor = Exception.class`.

This rule comes from JPA itself, not Spring. JPA says: unchecked exceptions are programming errors (roll back); checked exceptions are recoverable business conditions (commit). In practice this surprises everyone the first time.

#### 3. Read-only flag exists and matters

```java
@Transactional(readOnly = true)
public List<Book> listBooks() { ... }
```

Hibernate skips dirty checking (no snapshots of loaded entities). Faster for query methods. Also gives the DB driver a hint it can sometimes optimize on (Postgres doesn't, but the practice is standard).

#### 4. Propagation defines what happens when a transactional method calls another transactional method

Defaults to `REQUIRED`: "if a tx exists, join it; otherwise start a new one."

| Propagation | Behavior |
|---|---|
| `REQUIRED` (default) | Join existing tx, or start new. 95% case. |
| `REQUIRES_NEW` | Suspend outer tx, start new one. Inner commits independently. Useful for audit logs. |
| `NESTED` | Savepoint in outer tx. Inner rolls back without rolling outer. |
| `MANDATORY` | Must be called inside a tx, otherwise throw. |
| `SUPPORTS` | Join if exists, run non-transactionally otherwise. |
| `NOT_SUPPORTED` | Suspend any tx, run non-transactionally. |
| `NEVER` | Throw if a tx exists. |

**Audit log example with REQUIRES_NEW:**

```java
@Service
class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String event) {
        auditRepo.save(new AuditEntry(event));
    }
}
```

Even if the calling service's transaction rolls back, the audit entry was committed in its own separate transaction. Useful for "I want to record that this failure happened."

#### 5. Isolation level (default = DB default; Postgres = READ COMMITTED)

| Level | What you can see | What you can't do |
|---|---|---|
| READ UNCOMMITTED | Other txs' uncommitted writes (dirty reads) | — Postgres treats this as READ COMMITTED. |
| READ COMMITTED | Only committed data | Repeated reads in same tx may differ (non-repeatable read) |
| REPEATABLE READ | Within a tx, repeated reads return same value | Phantom reads (Postgres prevents these too) |
| SERIALIZABLE | As if transactions ran one-by-one | Highest contention, can fail with serialization conflicts |

Most apps run on READ COMMITTED and accept the implications.

```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public void doVeryImportantThing() { ... }
```

### Concurrency: lost updates and how to prevent them

**Scenario:** Two users edit the same product simultaneously.

1. T1 reads stock = 10.
2. T2 reads stock = 10.
3. T1 writes stock = 9 (sold one).
4. T2 writes stock = 9 (also sold one).
5. Result: 1 sale recorded, 1 sale lost.

Three ways to fix:

#### Optimistic locking with `@Version`

```java
@Entity
public class Product {
    @Id Long id;
    String name;
    int stock;

    @Version
    Long version;
}
```

Hibernate adds `WHERE id = ? AND version = ?` to every UPDATE and bumps the version. If two transactions race, one of them gets `OptimisticLockException`. Cheap, no DB locks, but requires retry logic.

```sql
UPDATE products SET name = ?, stock = ?, version = version + 1
WHERE id = ? AND version = ?
```

If `version` in the DB no longer matches what was read, the row count is 0 → Hibernate throws.

#### Pessimistic locking — `SELECT ... FOR UPDATE`

```java
Product p = em.find(Product.class, id, LockModeType.PESSIMISTIC_WRITE);
```

Other readers block until this transaction commits. Stronger guarantee, but holds locks longer = more contention.

#### Atomic SQL

```java
@Modifying
@Query("UPDATE Product p SET p.stock = p.stock - 1 WHERE p.id = :id AND p.stock > 0")
int decrementStock(@Param("id") Long id);
```

Returns row count. If 0, you know stock was already 0 or the row was gone. No race possible — DB does it atomically.

The third one is usually the cleanest for counters.

**Interviewer probe:** *"How do you handle two users trying to place the last item in stock at the same time?"* — pick one of the three above and explain why it fits the scenario.

---

## 6. Fetch types, lazy/eager, OSIV, N+1

### The four association annotations and their defaults

| Annotation | Default fetch |
|---|---|
| `@OneToOne` | **EAGER** |
| `@ManyToOne` | **EAGER** |
| `@OneToMany` | **LAZY** |
| `@ManyToMany` | **LAZY** |

The "to-one" defaults to eager because one extra row is "cheap." But in real apps you almost always want `@ManyToOne(fetch = LAZY)` too, because eager `@ManyToOne` causes problems:

- Loading 1000 books with eager author = 1001 queries (1 + 1000 author lookups), or one query with a join you may not want.
- Eager is **non-overridable per query** — you're stuck with it.

**The professional default in 2026: make all associations LAZY explicitly, and fetch what you need per query via fetch joins or entity graphs.**

### Lazy loading mechanics, in painful detail

When Hibernate loads `Book` with a LAZY `@ManyToOne` author, it does NOT put a real `Author` in the field. It puts a **proxy** — a runtime-generated subclass of `Author`. The proxy's fields are uninitialized. Only the proxy's `id` is populated (Hibernate already has it from the FK column).

```java
Book book = bookRepo.findById(42L).orElseThrow();
// At this point: 1 query has run. book.author is a proxy.

Long aid = book.getAuthor().getId();
// Still 0 queries. The id was set when the proxy was built.

String name = book.getAuthor().getName();
// THIS triggers: SELECT * FROM authors WHERE id = ?
```

Same idea for `@OneToMany` collections — they're replaced with `PersistentBag` or `PersistentSet` which fetch their contents on first access.

The proxy holds a reference to the Hibernate session. If the session closes before the proxy is touched, you get `LazyInitializationException` on access.

### N+1 in full ugliness

```java
@Transactional(readOnly = true)
public List<String> reportAuthorOfEachBook() {
    List<Book> books = bookRepo.findAll();           // 1 query: SELECT * FROM books
    return books.stream()
        .map(b -> b.getAuthor().getName())           // N queries: one per book
        .toList();
}
```

If you have 1000 books, you fire 1001 queries. On a remote DB at 5ms per query that's 5 seconds. This is the **single most common Hibernate performance bug**.

It also happens in the JSON layer: returning `List<Book>` from a controller with a lazy `author`, when serialized to JSON, walks each book → triggers a fetch per author. Invisible in code, devastating in production.

### How to fix N+1 — four ways

**1. Fetch join in JPQL** — explicit, per-query:

```java
@Query("SELECT b FROM Book b JOIN FETCH b.author")
List<Book> findAllWithAuthor();
```

One query, returns books with author already hydrated.

**Combining with WHERE:**

```java
@Query("SELECT b FROM Book b JOIN FETCH b.author a WHERE a.country = :country")
List<Book> findAllByAuthorCountry(@Param("country") String country);
```

**2. `@EntityGraph`** — declarative version of the same:

```java
@EntityGraph(attributePaths = {"author", "tags"})
List<Book> findAll();
```

Spring Data picks this up and applies a fetch join. Cleaner for simple cases.

**3. Batch fetching** — `@BatchSize(size = 100)` on the association:

```java
@Entity
public class Book {
    @ManyToOne(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    private Author author;
}
```

When Hibernate is about to lazy-load 1000 authors one by one, it sees the batch size and fires `WHERE id IN (?, ?, ..., 100 ?s)` instead. Reduces 1000 queries to 10. Less surgical than fetch join but a useful safety net.

**4. Projections / DTOs** — don't load entities at all:

```java
@Query("SELECT new com.foo.BookWithAuthor(b.title, b.author.name) FROM Book b")
List<BookWithAuthor> findAllSummaries();
```

This is the cleanest approach for read endpoints — entities are for write paths, DTOs for reads. Many teams enforce this.

### The fetch join pitfall: pagination

```java
@Query("SELECT b FROM Book b JOIN FETCH b.tags")
Page<Book> findAll(Pageable pageable);   // BROKEN
```

Hibernate logs a warning: "firstResult/maxResults specified with collection fetch; applying in memory." Translation: it loads **all** matching rows into memory, then slices the page from memory. Defeats pagination entirely.

Fix: paginate the parent first, then load associations:

```java
// Step 1: paginate without join fetch
Page<Long> ids = bookRepo.findIds(pageable);

// Step 2: fetch full entities with associations for just those IDs
List<Book> books = bookRepo.findByIdInWithTags(ids.getContent());
```

Or use `@EntityGraph` with `Pageable` — but only for `@ManyToOne` (no Cartesian explosion). For `@OneToMany` collections, the in-memory pagination warning applies.

### MultipleBagFetchException

If you `JOIN FETCH` two `@OneToMany` collections in one query:

```java
@Query("SELECT b FROM Book b JOIN FETCH b.tags JOIN FETCH b.reviews")
```

Hibernate throws `MultipleBagFetchException`. Reason: joining two collections produces a Cartesian product. 10 tags × 20 reviews = 200 rows for one book, and Hibernate can't deduplicate without help.

Fixes:
- Change `List` to `Set` for one of them — `Set` deduplicates.
- Two separate queries.
- `@BatchSize` instead of fetch join for one of them.

### Open Session in View — full revisit

Spring Boot defaults `spring.jpa.open-in-view: true`. Mechanism:

1. A servlet filter (`OpenEntityManagerInViewFilter`) opens a persistence context at the start of every HTTP request.
2. The session stays bound to the thread for the entire request lifecycle.
3. Controllers, view renderers (Thymeleaf, JSON serializers) can all trigger lazy loading.

Why this was introduced: in the early Spring days, templates (JSP/Thymeleaf) frequently navigated entity associations during rendering. Without OSIV, every template hit a `LazyInitializationException`. Turning it on "fixed" the experience for newcomers.

**Why it's an anti-pattern in 2026:**

- Modern apps return JSON, not server-rendered HTML. Lazy loading from the serializer is invisible — you can't see in code review where queries fire.
- N+1 problems leak into serialization. Returning a `List<Book>` from a controller with a lazy `author` triggers N queries when Jackson serializes.
- Transaction boundaries become fuzzy. The OSIV session isn't transactional — queries from controllers run *outside* any `@Transactional` scope.
- Connection held longer. The session pins a connection (or part of the pool's resource accounting) until the response is fully written. Under load this matters.
- Spring Boot logs a warning about it on startup with default settings.

**Best practice:** `spring.jpa.open-in-view: false`. Force discipline:

- All DB work inside `@Transactional` service methods.
- Services return DTOs or fully-loaded entities.
- Controllers never touch lazy associations.

When you switch it off and your existing code breaks with `LazyInitializationException`, those breakages are **diagnostic** — they reveal places that were doing accidental DB work. Fix each one with a fetch join or DTO projection.

**Interviewer probe:** *"What's OSIV, and why is it controversial?"*

> It keeps the Hibernate session open for the whole request lifecycle so views and controllers can lazily load. It's controversial because it hides where queries fire, allows N+1s to escape detection during reviews, blurs transaction boundaries, and holds connections longer than needed. Most teams turn it off and load explicitly inside transactional services.

---

## 7. Flyway — schema as code

### The problem Flyway solves

Without versioned migrations:
- Dev A adds a column manually. Dev B doesn't know.
- Prod migration is run by SSHing into a box and pasting SQL. Errors aren't caught.
- Rolling back to an earlier version means... what?

Flyway treats schema changes as code: files committed to Git, applied in order, tracked.

### How it works mechanically

You drop SQL files in `src/main/resources/db/migration/`:

```
V1__create_books_table.sql
V2__create_authors_table.sql
V3__add_isbn_index.sql
V4__add_published_at_column.sql
```

Naming format: `V<version>__<description>.sql` (two underscores between version and description).

On startup, Flyway:

1. Connects to the DB.
2. Looks for table `flyway_schema_history`. Creates it if missing.
3. Reads which migrations have already been applied (with their checksums).
4. For each migration file not in the history:
   - Computes checksum.
   - Runs the SQL inside a transaction (if the DB supports DDL in transactions — Postgres does).
   - Inserts a row in `flyway_schema_history` with the checksum.
5. For migrations already in history, validates that the file checksum matches the stored one. If different → error: "you modified an applied migration."

### What `flyway_schema_history` looks like

| installed_rank | version | description | type | script | checksum | installed_by | installed_on | execution_time | success |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | create books table | SQL | V1__create_books_table.sql | -1573098321 | postgres | 2026-05-13 10:00 | 47 | true |
| 2 | 2 | create authors table | SQL | V2__create_authors_table.sql | 209837421 | postgres | 2026-05-13 10:00 | 22 | true |

That's how Flyway knows what's applied and detects tampering.

### Key rules

- **Never edit an applied migration.** Once it's run anywhere, it's frozen. Need to fix? Write `V5__fix_V4_mistake.sql`.
- **Don't reuse a version number.** `V3__foo.sql` and `V3__bar.sql` is an error.
- **Use semantic versioning or timestamps.** `V1`, `V2`, `V2_1` work. Many teams prefer `V20260513120000__...` (datetime-prefixed) to avoid merge conflicts on version numbers.
- **One logical change per file.** Helps reviews and rollbacks.
- **Repeatable migrations** — files named `R__create_views.sql` re-run whenever their checksum changes. Useful for views, stored procs, seed data.

### Example migrations

**Creating a table:**

```sql
-- V1__create_books_table.sql
CREATE TABLE books (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    isbn VARCHAR(13) NOT NULL UNIQUE,
    author_id BIGINT NOT NULL REFERENCES authors(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_books_author_id ON books(author_id);
```

**Adding a column with a default:**

```sql
-- V4__add_published_at_to_books.sql
ALTER TABLE books ADD COLUMN published_at DATE;
```

If the table already has data and the column is NOT NULL, this is a two-step migration: first add nullable, backfill, then alter to NOT NULL.

**Backfill in a separate migration:**

```sql
-- V5__backfill_published_at.sql
UPDATE books SET published_at = '1970-01-01' WHERE published_at IS NULL;
ALTER TABLE books ALTER COLUMN published_at SET NOT NULL;
```

**Adding an index without locking the table (Postgres):**

```sql
-- V6__index_books_created_at.sql
CREATE INDEX CONCURRENTLY idx_books_created_at ON books(created_at);
```

`CONCURRENTLY` lets the index build without locking writes. Hibernate's `ddl-auto` cannot do this. Real production schema work requires this kind of control.

### Flyway vs Liquibase

| | Flyway | Liquibase |
|---|---|---|
| DSL | Plain SQL | XML/YAML/JSON/SQL |
| Rollbacks | Not built-in (write a reverse migration) | Built-in `rollback` blocks |
| DB-agnostic | Less so | More so — same Liquibase XML can run on Postgres or Oracle |
| Learning curve | Tiny | Higher |
| Popularity in Spring | Higher | Lower (but used in big enterprises) |

For 95% of projects, Flyway wins on simplicity. Pick Liquibase when you need cross-DB compatibility or formal rollback machinery.

### Why `ddl-auto=update` is banned (recap with the deeper "why")

`ddl-auto=update` makes Hibernate the source of schema truth. Problems:

1. **Only adds, never drops.** Renames produce ghost columns.
2. **No review.** Schema changes happen at app startup invisibly.
3. **No portability.** What runs on dev might not match prod (different Hibernate version → different DDL).
4. **Can't express what real DBs need.** No `CREATE INDEX CONCURRENTLY`, no partial indexes, no triggers, no partitioning.
5. **Race condition on startup.** Multiple app instances starting simultaneously can stomp each other's `ALTER TABLE`s.

Flyway forces explicit migrations, all reviewed in PRs. Production-grade.

### Zero-downtime migration pattern (interview gold)

When you need to rename a column in a hot production system:

1. **Expand**: Add the new column. Both columns exist. App writes both.
2. **Backfill**: Migration script copies old → new for existing rows.
3. **Switch reads**: App reads from new column.
4. **Stop writing old**: App writes only new column.
5. **Contract**: Drop the old column.

Each step is a separate deployable migration. Never a single big-bang change. This pattern is called **expand-migrate-contract**. Knowing the name is worth interview points.

Same pattern applies to:
- Renaming a column.
- Changing a column type.
- Splitting a table.
- Moving data to a new structure.

---

## 8. Common errors and what they mean

A reference for when stack traces look scary.

### `LazyInitializationException: could not initialize proxy - no Session`

**Cause:** You touched a lazy-loaded association after the session closed.

**Common origins:**
- Returning an entity with lazy associations from a `@Transactional` service, then accessing those associations in the controller.
- Serializing entities to JSON with OSIV off.
- Storing entities across HTTP requests (DON'T).

**Fix:** Load the data inside the transaction. `JOIN FETCH`, `@EntityGraph`, or return a DTO.

### `MultipleBagFetchException: cannot simultaneously fetch multiple bags`

**Cause:** Two `@OneToMany` collections joined in one query with `JOIN FETCH`.

**Fix:** Use `Set` instead of `List` for one of them, or two separate queries, or `@BatchSize`.

### `OptimisticLockException` / `StaleObjectStateException`

**Cause:** Another transaction modified the entity between your read and your write. The `@Version` check failed.

**Fix:** Retry the operation, or surface the conflict to the user ("someone else modified this — refresh and try again").

### `ConstraintViolationException` (DB-level)

**Cause:** Hit a DB constraint — unique, not-null, foreign key.

**Fix:** Catch and translate to a domain exception in your service. Spring's `DataIntegrityViolationException` is the JPA wrapper.

### `TransactionRequiredException: Executing an update/delete query`

**Cause:** Called a `@Modifying` query without a transaction.

**Fix:** Wrap the calling method with `@Transactional`.

### `No EntityManager with actual transaction available for current thread`

**Cause:** Trying to do JPA work outside any transaction (and OSIV is off).

**Fix:** Add `@Transactional` to the calling method.

### `JpaSystemException: could not execute statement`

**Cause:** A SQL error from the DB. The real error is nested in the cause chain.

**Fix:** Look at the cause — usually a constraint violation or invalid syntax.

### `BeanCreationException: Could not autowire field of type Repository`

**Cause:** Spring Boot didn't find your repository interface. Usually because it's outside the scanned package.

**Fix:** Put repositories in a subpackage of where `@SpringBootApplication` lives, or add `@EnableJpaRepositories("com.foo.repos")`.

### Flyway `FlywayValidateException: Validate failed: Migration checksum mismatch`

**Cause:** You edited an applied migration file.

**Fix:** Revert the edit and add a new migration with the change. As a last resort in dev: `mvn flyway:repair` to update the checksum, but never do this on prod data.

---

## 9. Interview probes you should be ready for

- "Explain N+1 and how to fix it."
- "What's the difference between `JOIN` and `JOIN FETCH` in JPQL?"
- "How does `@Transactional` work mechanically?"
- "Why doesn't `@Transactional` work when calling a method from inside the same class?"
- "Default fetch type for each relationship — what and why?"
- "What's the persistence context? What does dirty checking mean?"
- "Why not `ddl-auto=update`?"
- "How would you do a zero-downtime column rename?"
- "Pessimistic vs optimistic locking — when to use which?"
- "Detached vs transient vs managed — what's each?"
- "Why is `equals`/`hashCode` tricky on JPA entities?"
- "What's the default isolation level in Postgres?"
- "What does `@Version` do?"
- "When would you choose Spring Data repositories vs raw EntityManager?"
- "What's OSIV and why turn it off?"
- "What happens if a migration fails halfway?" (Flyway marks it failed in history; subsequent runs error until repaired.)
- "What's a fetch join pitfall with pagination?"
- "Why store enums as STRING not ORDINAL?"
- "What is `MultipleBagFetchException`?"
- "How does Hibernate know which fields changed for an UPDATE?" (snapshot in persistence context + dirty checking at flush time)
- "What's the difference between `EntityManager.persist` and `EntityManager.merge`?"
  - `persist` requires a transient entity (no ID). Throws if the entity is already managed or has a conflicting ID.
  - `merge` takes a detached entity and copies its state onto a managed version, returning the managed copy. Use when you're given an entity from outside the persistence context.

If you can answer each of those in 2-3 sentences without hedging, you have Phase 1 nailed.

---

## 10. Glossary

| Term | Meaning |
|---|---|
| **Entity** | A class mapped to a DB table via `@Entity`. |
| **Persistence context** | Hibernate's per-transaction cache of managed entities. |
| **EntityManager** | The JPA-level interface for persistence operations. In Hibernate, backed by a `Session`. |
| **Session** | Hibernate's equivalent of `EntityManager`. Lifetime = transaction (usually). |
| **Managed / Persistent** | Entity is in the persistence context. Changes auto-tracked. |
| **Detached** | Entity has an ID but is no longer in any session. |
| **Transient** | Entity that has never been persisted. |
| **Dirty checking** | Hibernate snapshotting loaded entities and auto-generating UPDATEs at flush. |
| **Flush** | Writing queued SQL to the DB. |
| **Commit** | Ending the transaction; making changes visible. |
| **JPQL** | JPA Query Language — works on entity names, not table names. |
| **HQL** | Hibernate Query Language — JPQL + Hibernate extensions. |
| **Native query** | Raw SQL passed through. |
| **Proxy** | Hibernate-generated subclass standing in for a lazy association. |
| **Lazy fetch** | Don't load the association until touched. |
| **Eager fetch** | Load immediately with the parent. |
| **Fetch join** | JPQL `JOIN FETCH` — load association in the same query. |
| **`@EntityGraph`** | Declarative way to specify which associations to fetch. |
| **N+1** | Loading N entities triggers N additional queries for their associations. Performance disaster. |
| **OSIV** | Open Session in View — keeps session open through HTTP request. |
| **Persistence context cache** | First-level cache. Per-transaction. Identity guarantee. |
| **Second-level cache** | Cross-transaction cache. Opt-in per entity. |
| **`@Version`** | Field that enables optimistic locking. |
| **Optimistic lock** | Check version on UPDATE; conflict = retry. |
| **Pessimistic lock** | `SELECT ... FOR UPDATE`; readers block. |
| **`@Transactional`** | Spring annotation marking a method as transactional. Implemented via proxy. |
| **Propagation** | What happens when one tx-method calls another. Default `REQUIRED`. |
| **Isolation level** | How much one tx sees of others' uncommitted state. Postgres default = READ COMMITTED. |
| **Flyway** | Versioned, file-based schema migration tool. |
| **`flyway_schema_history`** | Table Flyway maintains to track applied migrations. |
| **Checksum mismatch** | You edited an already-applied migration file. Forbidden. |
| **`ddl-auto`** | Hibernate property controlling schema management. Use `validate` in production. |
| **Expand-migrate-contract** | Zero-downtime schema change pattern. |
| **DTO** | Data Transfer Object — plain class with no JPA annotations, used at API boundaries or for projections. |
| **Projection** | Loading only a subset of columns into a DTO instead of a full entity. |

---

When you're back, ask for the approach to Task 1.1.
