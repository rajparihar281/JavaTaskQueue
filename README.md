<div align="center">

# JavaTaskQueue

**A distributed-style Task Queue and Job Scheduler in pure Java 17**

*Inspired by Celery and Sidekiq*

[![CI](https://github.com/rajparihar281/JavaTaskQueue/actions/workflows/ci.yml/badge.svg)](https://github.com/rajparihar281/JavaTaskQueue/actions)
![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)
![Maven](https://img.shields.io/badge/Maven-3.8+-blue?logo=apachemaven)
![License](https://img.shields.io/badge/License-MIT-green)

</div>

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            JavaTaskQueue                                │
│                                                                         │
│  ┌──────────────┐      ┌──────────────┐      ┌──────────────────────┐  │
│  │  TCP Server  │─────▶│  TaskBroker  │─────▶│     WorkerPool       │  │
│  │  (port 9090) │      │  (Priority   │      │     (4 threads)      │  │
│  │              │      │   Queue)     │      │                      │  │
│  └──────┬───────┘      └──────┬───────┘      └──────────┬───────────┘  │
│         │                     │                         │              │
│         │                     │                         ▼              │
│         │              ┌──────┴────────┐      ┌──────────────────────┐  │
│         │              │   Delayed     │      │  TaskHandler         │  │
│         │              │   Scheduler   │      │  Registry            │  │
│         │              └───────────────┘      │   ├─ EchoHandler     │  │
│         │                                     │   ├─ SleepHandler    │  │
│         ▼                                     │   └─ ComputeHandler  │  │
│  ┌──────────────┐                             └──────────────────────┘  │
│  │   SQLite     │                                                       │
│  │ Persistence  │                                                       │
│  │ (taskqueue   │                                                       │
│  │    .db)      │                                                       │
│  └──────────────┘                                                       │
│                                                                         │
│  ┌──────────────┐                                                       │
│  │  TaskQueue   │  (Separate process — interactive REPL client)        │
│  │  Client      │                                                       │
│  └──────────────┘                                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

> **Prerequisites:** Java 17+ · Maven 3.8+

```bash
# 1. Build
mvn clean package

# 2. Start the server
java -jar target/JavaTaskQueue-jar-with-dependencies.jar

# 3. Start the client (separate terminal)
java -cp target/JavaTaskQueue-jar-with-dependencies.jar com.taskqueue.client.TaskQueueClient

# 4. Run tests
mvn test
```

---

## Command Reference

### Server Commands

| Command    | Syntax                                                                    | Description                            |
|:-----------|:--------------------------------------------------------------------------|:---------------------------------------|
| `SUBMIT`   | `SUBMIT {"type":"...","payload":"...","priority":"HIGH\|NORMAL\|LOW"}`    | Submit a new task to the queue         |
| `STATUS`   | `STATUS <taskId>`                                                         | Check the current status of a task     |
| `LIST`     | `LIST`                                                                    | List all tasks (JSON array)            |
| `CANCEL`   | `CANCEL <taskId>`                                                         | Cancel a pending task                  |
| `SHUTDOWN` | `SHUTDOWN`                                                                | Gracefully shut down the server        |

### Built-in Task Types

| Type      | Payload Format                | Description                              |
|:----------|:------------------------------|:-----------------------------------------|
| `ECHO`    | Any string                    | Echoes the payload back                  |
| `SLEEP`   | Integer (ms)                  | Sleeps for the specified duration        |
| `COMPUTE` | `FACTORIAL:N` or `ISPRIME:N`  | Computes factorial or checks primality   |

---

## Example Session

**Terminal 1 — Start the server:**

```console
$ java -jar target/JavaTaskQueue-jar-with-dependencies.jar
╔═══════════════════════════════════════════════════════╗
║              JavaTaskQueue v1.0.0                    ║
║         Distributed Task Queue & Job Scheduler       ║
╚═══════════════════════════════════════════════════════╝

  JavaTaskQueue started on port 9090
```

**Terminal 2 — Start the client:**

```console
$ java -cp target/JavaTaskQueue-jar-with-dependencies.jar com.taskqueue.client.TaskQueueClient
Connecting to JavaTaskQueue server at localhost:9090...
Connected! Type 'help' for available commands.

jtq> LIST
[]

jtq> SUBMIT {"type":"COMPUTE","payload":"FACTORIAL:10","priority":"HIGH"}
{
  "status": "ok",
  "taskId": "a1b2c3d4-e5f6-..."
}

jtq> STATUS a1b2c3d4-e5f6-...
{
  "taskId": "a1b2c3d4-e5f6-...",
  "type": "COMPUTE",
  "status": "DONE",
  "payload": "FACTORIAL:10",
  "priority": "HIGH"
}

jtq> SUBMIT {"type":"ECHO","payload":"Hello, World!","priority":"NORMAL"}
{
  "status": "ok",
  "taskId": "f7e8d9c0-..."
}

jtq> SUBMIT {"type":"SLEEP","payload":"2000","priority":"LOW"}
{
  "status": "ok",
  "taskId": "1a2b3c4d-..."
}

jtq> LIST
[
  { "taskId": "a1b2c3d4-...", "type": "COMPUTE", "status": "DONE",    ... },
  { "taskId": "f7e8d9c0-...", "type": "ECHO",    "status": "DONE",    ... },
  { "taskId": "1a2b3c4d-...", "type": "SLEEP",   "status": "RUNNING", ... }
]

jtq> exit
Goodbye!
```

---

## Design Decisions

<details>
<summary><strong>Why <code>PriorityBlockingQueue</code>?</strong></summary>

The `PriorityBlockingQueue` from `java.util.concurrent` provides a thread-safe, unbounded queue that automatically orders elements by their natural ordering (or a comparator). This is perfect for a task queue where:

- **Priority matters** — HIGH-priority tasks are dequeued before LOW-priority tasks.
- **Blocking semantics** — Worker threads can block on `poll()` without busy-waiting.
- **Thread safety** — No external synchronization needed for queue operations.

</details>

<details>
<summary><strong>Why SQLite for Persistence?</strong></summary>

SQLite provides durable, ACID-compliant persistence with zero configuration:

- **No server required** — The database is a single file (`taskqueue.db`) — no setup needed.
- **Crash recovery** — On restart, pending and running tasks are recovered from the database and resubmitted to the broker.
- **Minimal footprint** — The `xerial/sqlite-jdbc` driver is a single JAR dependency.
- **Atomic operations** — Transactions ensure data integrity even during unexpected shutdowns.

</details>

<details>
<summary><strong>Why the Strategy Pattern for Handlers?</strong></summary>

The `TaskHandler` interface and `TaskHandlerRegistry` implement the Strategy pattern:

- **Open/Closed Principle** — New task types can be added by implementing a new handler and registering it — no existing code needs to change.
- **Testability** — Handlers are isolated units that can be tested independently.
- **Runtime flexibility** — Handlers can be registered/replaced at runtime.
- **Default fallback** — The registry returns an `EchoHandler` for unknown types, ensuring the system never crashes due to a missing handler.

</details>

---

## How to Add a New Task Type

Adding a new task type requires just **3 steps**:

**Step 1 — Create a Handler**

```java
package com.taskqueue.handler;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;

public class MyCustomHandler implements TaskHandler {
    @Override
    public TaskResult execute(Task task) {
        long start = System.currentTimeMillis();

        // Your logic here
        String result = "Processed: " + task.getPayload();

        long duration = System.currentTimeMillis() - start;
        return new TaskResult(task.getTaskId(), TaskStatus.DONE,
                result, "", duration);
    }
}
```

**Step 2 — Register the Handler**

In `Main.java`, add:

```java
registry.register("MY_CUSTOM", new MyCustomHandler());
```

**Step 3 — Submit Tasks**

```console
jtq> SUBMIT {"type":"MY_CUSTOM","payload":"your data here","priority":"NORMAL"}
```

---

## Project Structure

```
JavaTaskQueue/
├── pom.xml                           # Maven build configuration
├── .github/workflows/ci.yml         # GitHub Actions CI
├── README.md                         # This file
└── src/
    ├── main/java/com/taskqueue/
    │   ├── Main.java                 # Application entry point
    │   ├── model/                    # Data models (Task, TaskStatus, etc.)
    │   ├── broker/                   # Task queue broker
    │   ├── worker/                   # Worker pool and threads
    │   ├── handler/                  # Task execution handlers
    │   ├── server/                   # TCP server and client handler
    │   ├── client/                   # Interactive REPL client
    │   ├── persistence/              # SQLite persistence layer
    │   └── scheduler/                # Delayed task scheduler
    └── test/java/com/taskqueue/      # Unit tests
```

---

## License

This project is licensed under the [MIT License](LICENSE).
