# Phase 1 Theory: JPA, Hibernate, Spring Data, Transactions, Flyway

A standalone deep-dive. All examples use generic domains (Book/Author, Customer/Order, Product, Employee/Department) so the concepts stay separated from any specific project.

Each chapter ends with **10 self-quiz questions** with short answers. Cover the answer, ask yourself the question, then check. By the end of all chapters you should be able to answer ~80 questions without hedging.

---

## Table of contents

1. [The big picture: why JPA exists](#1-the-big-picture-why-jpa-exists)
2. [Entities — Java classes mapped to tables](#2-entities--java-classes-mapped-to-tables)
3. [The persistence context — Hibernate's working memory](#3-the-persistence-context--hibernates-working-memory)
4. [Spring Data repositories](#4-spring-data-repositories)
5. [DTOs and DAOs](#5-dtos-and-daos)
6. [Transactions](#6-transactions--the-core-mental-model)
7. [Fetch types, lazy/eager, OSIV, N+1](#7-fetch-types-lazyeager-osiv-n1)
8. [Flyway — schema as code](#8-flyway--schema-as-code)
9. [Common errors and what they mean](#9-common-errors-and-what-they-mean)
10. [Interview probes you should be ready for](#10-interview-probes-you-should-be-ready-for)
11. [Glossary](#11-glossary)

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

### Mental model: who does what when you call `bookRepo.save(book)`

1. **Spring Data** receives the call, delegates to the generic implementation it generated for your interface.
2. The implementation calls `EntityManager.persist(book)` — this is the **JPA** API.
3. Hibernate (the JPA implementation) registers the entity in the persistence context, queues an INSERT.
4. At transaction commit, Hibernate flushes — generates SQL `INSERT INTO books (...) VALUES (...)`, sends it down to **JDBC**.
5. JDBC sends bytes to the Postgres driver, which sends them over TCP to the DB.

Four layers. Each one a real abstraction with a real purpose.

### Why this layering matters

Each layer can be swapped without rewriting the layers above:

- Switch from Postgres to MySQL → change the JDBC driver. Above stays the same.
- Switch from Hibernate to EclipseLink → change one Maven dep. Your `@Entity` classes don't change because they use JPA annotations, not Hibernate-specific ones.
- Stop using Spring Data → write `EntityManager` code directly. Your entities and queries still work.

Stability of the **interface** (JPA) is what lets the **implementations** (Hibernate, EclipseLink) compete.

### Chapter 1 — 10 self-quiz questions

1. **What is JPA, in one sentence?**
   The Jakarta Persistence API — a Java specification for ORM, defining annotations, the `EntityManager`, JPQL, and transactions. It's interfaces only, no implementation.

2. **What is Hibernate?**
   A library that *implements* the JPA specification. The default ORM in Spring Boot.

3. **What is Spring Data JPA?**
   A library that sits on top of JPA and lets you declare repository interfaces; Spring generates the implementations.

4. **List the four layers from your code down to the database.**
   Spring Data JPA → JPA (spec, with Hibernate as implementation) → JDBC → DB driver/network.

5. **Name two JPA implementations other than Hibernate.**
   EclipseLink and OpenJPA. (Both rarely used in modern Spring projects.)

6. **Why does JPA exist as a spec instead of just `org.hibernate.*`?**
   Stability of the interface lets implementations be swapped without rewriting application code, and prevents vendor lock-in.

7. **What does `spring-boot-starter-data-jpa` actually bring in?**
   Hibernate (the implementation), JPA API (the spec), Spring Data JPA (repositories), HikariCP (connection pool), JDBC support, and Spring's transaction management.

8. **Trace what happens when you call `repository.save(entity)` for a new entity.**
   Spring Data delegates to `EntityManager.persist()` → Hibernate registers it in the persistence context → at commit Hibernate generates an INSERT → JDBC sends it to the DB.

9. **Why is JDBC at the bottom?**
   It's Java's lowest-level DB API. Hibernate generates SQL strings and uses JDBC to execute them. You can use JDBC alone (`JdbcTemplate`) without any ORM if you want.

10. **What's `EntityManager`?**
    The main JPA interface. Methods include `persist`, `merge`, `find`, `remove`, `createQuery`. Hibernate provides the actual implementation, called `Session`.

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
- **`@Id`** field, exactly one (or composite — see below). Maps to the primary key.
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

**Enum example — the ORDINAL trap:**

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

### Embedded value objects — `@Embeddable`

Sometimes a group of fields logically belongs together but doesn't deserve its own table. Example: an address.

```java
@Embeddable
public class Address {
    @Column(name = "street", nullable = false)
    private String street;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "country", length = 2)
    private String country;

    protected Address() {}
    public Address(String street, String city, String country) { ... }
}

@Entity
public class Customer {
    @Id @GeneratedValue Long id;
    String name;

    @Embedded
    private Address address;   // flattens into customer table
}
```

The resulting `customers` table has columns: `id, name, street, city, country`. The `Address` is just a Java grouping — no separate table, no FK, no JOIN.

Use this for value objects that don't have identity of their own (an address belongs to *this* customer; if you change it, you don't update other customers' addresses).

### Composite keys — `@EmbeddedId`

When a table's primary key is multiple columns:

```java
@Embeddable
public class EnrollmentId implements Serializable {
    private Long studentId;
    private Long courseId;

    // equals & hashCode REQUIRED (composite keys must implement them)
}

@Entity
public class Enrollment {
    @EmbeddedId
    private EnrollmentId id;

    private LocalDate enrolledAt;
}
```

The PK is the pair `(student_id, course_id)`. Use for many-to-many join tables (and the alternative `@IdClass` exists but `@EmbeddedId` is preferred).

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

Requires a join table. **In production, most teams replace `@ManyToMany` with an explicit join entity** because real-world join tables almost always grow extra columns (timestamps, who added the tag, etc.).

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

### Cascade types — what happens to associated entities

```java
@OneToMany(mappedBy = "author", cascade = CascadeType.PERSIST)
private List<Book> books;
```

Cascade tells Hibernate: "when I do operation X on the parent, also do it on the children."

| Cascade | Effect |
|---|---|
| `PERSIST` | Saving parent saves new children. |
| `MERGE` | Merging parent merges children. |
| `REMOVE` | Deleting parent deletes children. **Dangerous on broad relationships.** |
| `REFRESH` | Refreshing parent refreshes children. |
| `DETACH` | Detaching parent detaches children. |
| `ALL` | All of the above. |

Plus `orphanRemoval = true` — if you remove a child from the parent's collection, delete it from the DB.

**Realistic example:**

```java
@OneToMany(mappedBy = "order", cascade = {PERSIST, REMOVE}, orphanRemoval = true)
private List<OrderLine> lines;
```

"When I save an Order, save its lines. When I delete an Order, delete its lines. If I remove a line from the list, delete it from the DB."

Use cascade for tight parent-child relationships (Order → OrderLines). Don't use it across loose associations (Book → Author — deleting a book shouldn't delete the author).

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

### Entity lifecycle states

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

### Chapter 2 — 10 self-quiz questions

1. **What two things does every JPA entity need at minimum?**
   `@Entity` annotation and a no-arg constructor (can be `protected`).

2. **Why can't an entity be `final`?**
   Hibernate generates runtime subclasses as proxies for lazy loading. Subclassing a final class isn't possible.

3. **Difference between `IDENTITY` and `SEQUENCE` PK generation?**
   `IDENTITY` uses an auto-increment column (DB assigns PK on INSERT, breaks batching). `SEQUENCE` uses a DB sequence (Hibernate gets the next value before INSERT, allows batching).

4. **What's the problem with `EnumType.ORDINAL`?**
   It stores enum values as ints based on declaration order. Inserting a new enum value mid-list silently changes the meaning of all stored rows.

5. **What does `mappedBy` mean on `@OneToMany`?**
   "I'm the inverse side; the other side (named here) owns the FK." Without it, Hibernate assumes you want a separate join table.

6. **Which side owns the foreign key in a one-to-many relationship?**
   The "many" side. The FK column lives on the child's table.

7. **What's `@Embeddable` for?**
   Grouping fields into a Java value object that's stored in the parent's table (no separate table or FK). Example: `Address` embedded in `Customer`.

8. **What's `CascadeType.REMOVE` plus `orphanRemoval = true`?**
   `REMOVE` deletes children when the parent is deleted. `orphanRemoval` deletes a child the moment you remove it from the parent's collection.

9. **The four lifecycle states?**
   Transient (never persisted), Managed (in session, tracked), Detached (was in session, session closed), Removed (marked for delete).

10. **What is dirty checking?**
    Hibernate keeps a snapshot of each managed entity's field values when loaded; at flush time it generates UPDATEs for fields that changed. You don't need to call `save()`.

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

### Flush modes

| Mode | When Hibernate flushes |
|---|---|
| `AUTO` (default) | Before each query (to ensure query sees pending changes) and at commit. |
| `COMMIT` | Only at commit. Queries may not see your pending changes. |
| `MANUAL` | Only when you call `em.flush()` explicitly. |

You almost always want `AUTO`. The other modes exist for niche optimization scenarios.

### Identity guarantee — what it really means

```java
@Transactional
public void demo() {
    Book b1 = bookRepo.findById(42L).get();
    Book b2 = bookRepo.findById(42L).get();
    System.out.println(b1 == b2);     // true (same object reference!)
    System.out.println(b1.equals(b2));  // true
}
```

Inside one persistence context, you can't have two `Book` objects with id 42. The first `find` puts it in the cache; the second returns the cached one. No second DB query fires.

**Outside** the persistence context, this guarantee is gone — two separate transactions loading book 42 get two separate objects.

### Second-level cache (interview-relevant but not used here)

The persistence context is per-transaction (first-level cache). Hibernate also supports a **second-level cache** that lives across transactions and even across nodes (with Ehcache, Hazelcast, Infinispan).

You opt in per-entity:

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Country { ... }
```

Use for read-mostly, slowly-changing reference data (currencies, country codes, configuration). Don't use for hot, frequently-written tables — cache invalidation gets painful.

### `persist` vs `merge`

```java
em.persist(entity);   // Entity must be TRANSIENT. Throws if already managed.
em.merge(entity);     // Entity can be DETACHED. Returns a MANAGED copy.
```

**`persist`** attaches a brand-new entity to the persistence context.

**`merge`** is for detached entities — you got an entity from somewhere else (HTTP request, cache, whatever) and want to apply its state to a managed copy.

```java
@Transactional
public Book updateBook(Book detachedBook) {
    Book managed = em.merge(detachedBook);    // copies state, returns managed version
    // 'detachedBook' is still detached after this!
    // 'managed' is what you modify.
    return managed;
}
```

In Spring Data, `repository.save(entity)`:
- If entity has no ID, calls `persist`.
- If entity has an ID and isn't managed, calls `merge`.

This is why `save()` "just works" for both new and updated entities.

### Chapter 3 — 10 self-quiz questions

1. **What is the persistence context?**
   Hibernate's per-transaction cache of managed entities, with identity guarantee, dirty checking, write-behind, and first-level cache.

2. **What does "identity guarantee" mean?**
   Inside one persistence context, the same row is always represented by the same Java object instance — `==` comparison works.

3. **Flush vs commit?**
   Flush writes queued SQL to the DB. Commit ends the transaction. Flush can happen multiple times within a tx; commit only once.

4. **When does Hibernate auto-flush?**
   Before queries (so the query sees pending changes) and at commit. This is `FlushMode.AUTO`, the default.

5. **What's write-behind?**
   Hibernate queues SQL statements rather than firing them immediately, so they can be batched at flush time.

6. **What breaks Hibernate batching for inserts?**
   `IDENTITY` PK strategy — the DB assigns the PK on INSERT, so Hibernate must fire each INSERT separately to get the ID before queuing the next.

7. **First-level vs second-level cache?**
   First-level = persistence context, per-transaction, always on. Second-level = cross-transaction cache, opt-in per entity, requires a provider.

8. **What's `persist` vs `merge`?**
   `persist` attaches a new (transient) entity. `merge` copies a detached entity's state onto a managed copy and returns the managed one.

9. **How long does the persistence context live?**
   For the duration of the transaction. On commit, it's cleared and entities become detached (or garbage-collected).

10. **Why does `em.find(Book.class, 42L)` twice in a transaction only hit the DB once?**
    The first `find` loads the row and stores the entity in the persistence context. The second finds it in the cache and returns the same instance without a query.

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

**Projections** — return DTOs, not entities. See chapter 5 for the full DTO/DAO discussion.

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

| Return type | What it gives you | Cost |
|---|---|---|
| `List<T>` | Just the page contents | 1 query |
| `Slice<T>` | Page contents + `hasNext()` | 1 query (fetches `pageSize + 1` to know if there's more) |
| `Page<T>` | Page contents + total count + total pages | 2 queries |

Use `Page` only when the UI shows "page X of Y." Use `Slice` for infinite scroll. Use `List` when no pagination semantics needed.

### Specifications — dynamic queries

For search/filter endpoints where any field might be present:

```java
public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {}
```

Define reusable conditions:

```java
public class BookSpecs {
    public static Specification<Book> hasAuthor(Long authorId) {
        return (root, query, cb) ->
            authorId == null ? null : cb.equal(root.get("author").get("id"), authorId);
    }

    public static Specification<Book> titleContains(String fragment) {
        return (root, query, cb) ->
            fragment == null ? null : cb.like(cb.lower(root.get("title")), "%" + fragment.toLowerCase() + "%");
    }

    public static Specification<Book> publishedAfter(LocalDate date) {
        return (root, query, cb) ->
            date == null ? null : cb.greaterThan(root.get("publishedAt"), date);
    }
}
```

Compose at the call site:

```java
List<Book> results = bookRepo.findAll(
    Specification.where(BookSpecs.hasAuthor(authorId))
                 .and(BookSpecs.titleContains(title))
                 .and(BookSpecs.publishedAfter(after))
);
```

Each Spec returns `null` if its param is null → the WHERE clause skips that condition. Net result: one query with exactly the conditions the user asked for. Beats hand-writing 8 versions of the query.

### When NOT to use Spring Data

For **truly dynamic** queries (10 optional join paths, group-bys, having clauses) where Specifications get unwieldy, drop to `EntityManager.createQuery()` directly or use QueryDSL. Repositories are not a hammer for every nail.

### Chapter 4 — 10 self-quiz questions

1. **How does Spring Data implement the `findByIsbn` method on a repository interface?**
   At startup, Spring generates a proxy class. It parses the method name, builds a JPQL query (`SELECT b FROM Book b WHERE b.isbn = ?1`), and the generated method runs it.

2. **What's the difference between `CrudRepository` and `JpaRepository`?**
   `CrudRepository` is generic JPA-agnostic CRUD. `JpaRepository` extends it with JPA-specific methods (`flush`, `saveAndFlush`, batch operations).

3. **When are bad method names caught?**
   At startup. Spring parses each repository method name and validates it against the entity model. Typos in property names fail before the app accepts traffic.

4. **What's `@Modifying` for?**
   Marks `@Query` methods that aren't SELECT (UPDATE/DELETE). Without it, Spring tries to treat the query as a SELECT and fails. Returns affected row count.

5. **What's the difference between `findByName` and `getByName`?**
   None functionally — both prefixes work (and `readBy`, `queryBy`, `searchBy`). Pick one convention and stick with it.

6. **Difference between `Page<T>` and `Slice<T>`?**
   `Page` includes total count (fires a count query). `Slice` only knows if there's a next page (no count query). Use `Slice` for infinite scroll, `Page` for page X of Y UIs.

7. **What does `@Query(nativeQuery = true)` mean?**
   The string is raw SQL, not JPQL. Used when you need DB-specific features (CTEs, window functions, dialect-specific syntax).

8. **What's a `Specification`?**
   A reusable JPA Criteria predicate. Compose them with `and`/`or` to build dynamic queries at call time. Useful for search endpoints.

9. **Can you do joins in a derived query name?**
   Indirectly — `findByAuthor_Name` walks the `author.name` path. But for explicit `JOIN` (or `JOIN FETCH`) you need `@Query`.

10. **When should you NOT use Spring Data repositories?**
    When queries are so dynamic that Specifications get unreadable. Drop to `EntityManager`, QueryDSL, or jOOQ for that small percentage of cases.

---

## 5. DTOs and DAOs

Two of the most-confused terms in backend Java. Worth nailing.

### DAO — Data Access Object

A **DAO** is a class whose responsibility is *talking to the database*. It hides the persistence details from the rest of the app. Pre-Spring-Data, you wrote DAOs by hand:

```java
public class BookDao {
    private final EntityManager em;

    public BookDao(EntityManager em) { this.em = em; }

    public Optional<Book> findById(Long id) {
        return Optional.ofNullable(em.find(Book.class, id));
    }

    public List<Book> findByAuthor(Long authorId) {
        return em.createQuery(
            "SELECT b FROM Book b WHERE b.author.id = :a", Book.class)
          .setParameter("a", authorId)
          .getResultList();
    }

    public void save(Book b) { em.persist(b); }
    public void delete(Book b) { em.remove(b); }
}
```

That's a DAO: it encapsulates database access. The rest of the app calls `bookDao.findById(42L)` and doesn't know about `EntityManager` or JPQL.

**In modern Spring Data, the repository interface IS the DAO.**

```java
public interface BookRepository extends JpaRepository<Book, Long> {
    Optional<Book> findByIsbn(String isbn);
}
```

Same responsibility — hide DB details, expose domain operations. The class you no longer write by hand because Spring generates it.

So when someone asks "where are the DAOs?" in a Spring Boot project, the answer is usually "we use Spring Data repositories — those play the DAO role."

**One sentence:** *DAO = the layer that knows how to read/write data.*

### DTO — Data Transfer Object

A **DTO** is a dumb object whose job is to **move data across boundaries** — between layers, across HTTP, across the wire. No business logic, no JPA annotations, no Hibernate connection. Just fields, getters, and (optionally) constructors.

**Why DTOs exist:**

You don't want to send your `@Entity` `Book` straight out over HTTP. Reasons:

1. **It might have associations that lazy-load.** Jackson serializing `book.getAuthor().getName()` while the session is closed = `LazyInitializationException`.
2. **It exposes internal fields you didn't want public.** Internal flags, soft-delete markers, audit columns.
3. **It couples your API to your DB schema.** Rename a column → break clients.
4. **The DB model doesn't match what clients want.** Often you join multiple entities, transform, compute fields.

So you define a separate class:

```java
public record BookResponse(
    Long id,
    String title,
    String isbn,
    String authorName,
    LocalDate publishedAt
) {}
```

That's a DTO. A Java record (or a plain class with fields, getters, constructor). No annotations beyond maybe Jackson's. No mention of Hibernate.

### A complete DTO/Entity/DAO example

**The entity** (DB-shape, internal):

```java
@Entity
@Table(name = "books")
public class Book {
    @Id @GeneratedValue Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true)
    private String isbn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private Author author;

    @Column
    private LocalDate publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;   // internal soft-delete marker

    @Version
    private Long version;        // internal optimistic-lock field
}
```

**The DAO** (repository):

```java
public interface BookRepository extends JpaRepository<Book, Long> {
    Optional<Book> findByIsbnAndDeletedAtIsNull(String isbn);
}
```

**The request DTO** (what clients send to create a book):

```java
public record CreateBookRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Pattern(regexp = "\\d{13}") String isbn,
    @NotNull Long authorId,
    LocalDate publishedAt
) {}
```

**The response DTO** (what clients get back):

```java
public record BookResponse(
    Long id,
    String title,
    String isbn,
    String authorName,
    LocalDate publishedAt
) {}
```

**The service** (translates between them):

```java
@Service
@Transactional
public class BookService {

    private final BookRepository bookRepo;
    private final AuthorRepository authorRepo;

    public BookResponse create(CreateBookRequest req) {
        Author author = authorRepo.findById(req.authorId()).orElseThrow();
        Book book = new Book();
        book.setTitle(req.title());
        book.setIsbn(req.isbn());
        book.setAuthor(author);
        book.setPublishedAt(req.publishedAt());
        bookRepo.save(book);
        return toResponse(book);
    }

    @Transactional(readOnly = true)
    public BookResponse get(Long id) {
        Book b = bookRepo.findById(id).orElseThrow();
        return toResponse(b);
    }

    private BookResponse toResponse(Book b) {
        return new BookResponse(
            b.getId(),
            b.getTitle(),
            b.getIsbn(),
            b.getAuthor().getName(),
            b.getPublishedAt()
        );
    }
}
```

**The controller**:

```java
@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    @PostMapping
    public ResponseEntity<BookResponse> create(@Valid @RequestBody CreateBookRequest req) {
        BookResponse body = bookService.create(req);
        return ResponseEntity.status(201).body(body);
    }

    @GetMapping("/{id}")
    public BookResponse get(@PathVariable Long id) {
        return bookService.get(id);
    }
}
```

Notice what's *not* in the controller:
- No `Book` entity. Entities never leave the service layer.
- No `@Transactional`. Controllers don't manage transactions.
- No DB queries. Just request → service → response.

### DTO patterns — variations you'll see

#### Request DTO with validation

```java
public record CreateOrderRequest(
    @NotNull Long customerId,
    @NotEmpty List<@Valid OrderLineDto> lines
) {}

public record OrderLineDto(
    @NotNull Long productId,
    @Min(1) int quantity
) {}
```

`@Valid` cascades validation into nested objects. Combined with `@Valid` in the controller method signature, all validation runs before your service method is called.

#### Response DTO — flatten/transform

```java
public record OrderSummary(
    Long id,
    String customerName,      // joined from Customer entity
    int lineCount,            // computed from collection
    BigDecimal totalAmount,   // computed
    String status
) {}
```

Often a response DTO doesn't 1:1 match any entity — it's a view tailored to a use case.

#### Multiple DTOs for the same entity

It's normal to have:
- `BookSummary` — id + title (for list views)
- `BookResponse` — full single-book view
- `CreateBookRequest` — incoming new book
- `UpdateBookRequest` — incoming changes

The DB has one `Book` table. The API has four representations.

### Mapping: by hand vs MapStruct

The toResponse-style method works for small projects. For larger ones, **MapStruct** generates these mappers at compile time:

```java
@Mapper(componentModel = "spring")
public interface BookMapper {
    BookResponse toResponse(Book book);
}
```

MapStruct reads the method signature and generates a class that copies fields by name. Zero runtime cost. Worth knowing the name even if you don't use it.

Alternatives: ModelMapper (reflection-based, slower), or just hand-written mappers (best for small projects).

### Why a clean DTO boundary matters

Without DTOs, every API change becomes risky:

> Bad scenario: Controller returns `Book` entity. Frontend depends on its JSON shape. Someone renames a column. The migration also requires renaming the entity field. Now the API contract silently changed for every client.

With DTOs:

> Migration renames a column → entity field renamed → mapper updated to map new entity field to same response DTO field. **API contract unchanged.** Clients see no difference.

DTOs let the API contract evolve independently of the DB schema. Same principle in reverse: schema can change without rippling out to client breakage.

### Rule of thumb

> **Never let an `@Entity` cross a layer boundary you don't control.**

- Entity → repository: fine.
- Entity → service: fine.
- Entity → controller: fine (controller passes to mapper, which produces DTO).
- Entity → HTTP response: **no**. Wrap in DTO.
- Entity → message queue: **no**. Use a DTO/event class.
- Entity stored in HTTP session / cache: **no**. Detaches it, lazy loading dies.

### Chapter 5 — 10 self-quiz questions

1. **What's a DAO?**
   Data Access Object — the class/layer that talks to the database, hiding persistence details from callers. In Spring Data, the repository interface plays this role.

2. **What's a DTO?**
   Data Transfer Object — a dumb object with fields and getters, used to move data across boundaries (especially API boundaries). No JPA, no business logic.

3. **Why not return JPA entities directly from controllers?**
   Lazy-loading risks (`LazyInitializationException` during serialization), exposes internal fields, couples API shape to DB schema, makes schema changes break clients.

4. **What's the difference between a request DTO and a response DTO?**
   Request DTOs carry input from the client (validated with Bean Validation annotations). Response DTOs carry output to the client (often flatten or compute fields from entities).

5. **Why do request DTOs have validation annotations and entities usually don't?**
   Validation is an input-boundary concern. Once data is in the DB, it's already valid. Validating again on every load is wasteful.

6. **Is a repository the same thing as a DAO?**
   Conceptually yes — both are the data-access layer. "DAO" is the older pattern name; "repository" is the modern Spring Data term.

7. **What does `@Valid` on a controller parameter do?**
   Triggers Bean Validation on the request DTO. If validation fails, Spring throws `MethodArgumentNotValidException` before the controller body runs.

8. **Where does the conversion entity → DTO happen?**
   In the service layer (cleanest) or a dedicated mapper class. Never in the controller or repository.

9. **What does MapStruct do?**
   Generates entity ↔ DTO mapper implementations at compile time. Faster than reflection-based mappers, type-safe.

10. **Why is "never let an `@Entity` cross a layer boundary you don't control" a useful rule?**
    Entities have lifecycle, lazy state, version columns, and DB coupling. The further they travel, the more places those concerns leak. DTOs are inert and safe to send anywhere.

---

## 6. Transactions — the core mental model

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

@Service
class OrderService {
    @Autowired AuditService auditService;

    @Transactional
    public void placeOrder(...) {
        try {
            // ... do order work ...
            auditService.log("Order placed");   // commits in its own tx
        } catch (Exception e) {
            auditService.log("Order failed");   // ALSO commits, even though
                                                 // outer tx will rollback
            throw e;
        }
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

### Chapter 6 — 10 self-quiz questions

1. **ACID — what does each letter mean?**
   Atomicity (all-or-nothing), Consistency (constraints hold), Isolation (txs don't see each other's intermediate state), Durability (committed data survives).

2. **Why put `@Transactional` on services not controllers or repositories?**
   Controllers are HTTP-shaped; transactions are DB-shaped. Repositories are too granular — every save would be a separate tx. The service is the natural business-operation boundary.

3. **What's the self-invocation problem?**
   Calling a `@Transactional` method on `this` (same class) bypasses the Spring proxy that implements the annotation. The transaction isn't started.

4. **Which exceptions roll back the transaction by default?**
   `RuntimeException` and `Error`. Checked exceptions commit unless `rollbackFor` says otherwise.

5. **What does `@Transactional(readOnly = true)` do?**
   Tells Hibernate to skip dirty checking and the DB driver to optimize for reads. Use on query methods.

6. **What's the default propagation, and what does it do?**
   `REQUIRED`: join the existing transaction if one exists; otherwise start a new one.

7. **When would you use `REQUIRES_NEW`?**
   When you need the inner method to commit even if the outer transaction rolls back — classic case is audit logging.

8. **Postgres default isolation level?**
   READ COMMITTED. You can only see other transactions' committed data.

9. **Difference between optimistic and pessimistic locking?**
   Optimistic: no DB locks; check `@Version` at write time, fail if mismatch, retry. Pessimistic: `SELECT ... FOR UPDATE` locks the row; other writers wait.

10. **What does `@Version` do mechanically?**
    Hibernate adds `WHERE version = ?` to UPDATEs and bumps the version. Row count 0 means another tx modified it — throws `OptimisticLockException`.

---

## 7. Fetch types, lazy/eager, OSIV, N+1

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

### Chapter 7 — 10 self-quiz questions

1. **Default fetch type for `@OneToMany`?**
   LAZY. The collection is loaded only when first touched.

2. **Default fetch type for `@ManyToOne`?**
   EAGER (surprising). Most teams override to LAZY in practice.

3. **What's a Hibernate proxy?**
   A runtime-generated subclass of an entity that stands in for the real one until you touch it. Holds the session reference and triggers a SELECT on first field access.

4. **What causes `LazyInitializationException`?**
   Touching a lazy association after its session/persistence context has closed. The proxy can't fetch without a live session.

5. **What's the N+1 problem in one sentence?**
   You load N parent rows, and accessing a lazy association on each fires one additional query per parent — total queries = 1 + N.

6. **Four ways to fix N+1?**
   `JOIN FETCH` in JPQL, `@EntityGraph`, `@BatchSize`, or DTO projections that select only needed fields.

7. **What's `MultipleBagFetchException`?**
   Hibernate refuses to JOIN FETCH two `@OneToMany` `List` collections at once because the Cartesian product is ambiguous. Fix: use `Set`, or split into separate queries.

8. **What's wrong with `JOIN FETCH` plus `Page<T>`?**
   When fetching a collection, Hibernate can't apply LIMIT/OFFSET correctly — it loads everything into memory and paginates there. Disaster on large datasets.

9. **What's OSIV and why is it controversial?**
   Open Session in View — keeps the persistence context open for the whole HTTP request. Hides where queries fire, encourages N+1, breaks transactional clarity. Most teams turn it off.

10. **Difference between `@BatchSize` and fetch join?**
    Fetch join loads associations in the same query (one SQL statement). `@BatchSize` batches many lazy-loads into IN-queries (multiple statements but far fewer than N+1). Fetch join is more precise; batch is a safety net.

---

## 8. Flyway — schema as code

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

**Repeatable migration for a view:**

```sql
-- R__books_with_authors_view.sql
CREATE OR REPLACE VIEW books_with_authors AS
SELECT b.id, b.title, a.name AS author_name
FROM books b JOIN authors a ON a.id = b.author_id;
```

Filename starts with `R__`. Flyway reruns this whenever its checksum changes (so editing the view triggers a re-run, unlike versioned migrations).

### Baseline — adopting Flyway on an existing DB

When you start using Flyway on a DB that already has tables, you don't want Flyway to try to recreate everything. You **baseline**:

```yaml
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: 0
```

Flyway records a baseline row in `flyway_schema_history` at version 0, and only applies migrations with version > 0. Your existing schema is treated as "already there."

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

### Chapter 8 — 10 self-quiz questions

1. **What does Flyway do at app startup?**
   Reads `flyway_schema_history`, compares to migration files on classpath, applies any not yet run in version order, records each successful run.

2. **What's the filename format for a versioned migration?**
   `V<version>__<description>.sql` with two underscores between version and description.

3. **What's a repeatable migration?**
   A file named `R__<description>.sql` that re-runs whenever its checksum changes. For views, stored procs, seed data.

4. **What happens if you edit an applied migration?**
   On next startup, Flyway computes a different checksum from what's recorded → throws a validation error → app refuses to start.

5. **How do you recover from a failed migration?**
   Manually fix the DB (often Flyway has rolled the SQL back already, but the row in history is marked failed); delete the failed row from `flyway_schema_history` or use `mvn flyway:repair`; restart.

6. **Why is `ddl-auto=update` banned in production-grade projects?**
   It only adds (never drops), runs invisibly at app startup, can't express advanced DDL (concurrent indexes, partial indexes, triggers), and races with itself across multi-node deployments.

7. **What's expand-migrate-contract?**
   The zero-downtime schema change pattern: add new alongside old, backfill, switch reads, stop writes to old, drop old. Multiple deploys instead of one big-bang change.

8. **Flyway vs Liquibase — when would you pick Liquibase?**
   When you need cross-DB compatibility (same migrations on Postgres and Oracle), built-in rollback machinery, or richer change set expression (XML/YAML).

9. **What does `baseline-on-migrate` do?**
   Lets Flyway adopt an existing DB by treating its current schema as the baseline. Migrations with versions ≤ baseline are skipped.

10. **What does `CREATE INDEX CONCURRENTLY` give you over a plain `CREATE INDEX`?**
    Builds the index without taking a write lock on the table. Crucial in production where you can't pause writes during a long index build.

---

## 9. Common errors and what they mean

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

### Flyway `FlywayValidateException: Migration checksum mismatch`

**Cause:** You edited an applied migration file.

**Fix:** Revert the edit and add a new migration with the change. As a last resort in dev: `mvn flyway:repair` to update the checksum, but never do this on prod data.

### `MethodArgumentNotValidException`

**Cause:** A request DTO with `@Valid` failed Bean Validation. Default Spring response is 400 with validation details.

**Fix:** In a `@RestControllerAdvice`, handle this and produce a structured error response (Phase 2 covers this).

---

## 10. Interview probes you should be ready for

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
- "Why use DTOs at the API boundary?"
- "What is a DAO in modern Spring?"

If you can answer each of those in 2-3 sentences without hedging, you have Phase 1 nailed.

---

## 11. Glossary

| Term | Meaning |
|---|---|
| **Entity** | A class mapped to a DB table via `@Entity`. |
| **DAO** | Data Access Object — the layer that talks to the DB. In modern Spring, the repository interface plays this role. |
| **DTO** | Data Transfer Object — a dumb object carrying data across layer/API boundaries. No JPA annotations, no business logic. |
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
| **`@Embeddable` / `@Embedded`** | Group of fields flattened into the parent's table. Value object with no own table. |
| **`@EmbeddedId`** | Composite primary key represented by an embeddable. |
| **Cascade** | Propagate persistence operations from parent to children (PERSIST, MERGE, REMOVE, etc.). |
| **`orphanRemoval`** | Auto-delete child rows when removed from the parent's collection. |
| **MapStruct** | Annotation processor that generates entity ↔ DTO mappers at compile time. |
| **Specification** | Reusable JPA Criteria predicate; compose for dynamic queries. |
| **Projection** | Loading only a subset of columns into a DTO instead of a full entity. |

---

When you're back, ask for the approach to Task 1.1.
