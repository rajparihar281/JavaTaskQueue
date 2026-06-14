# JavaTaskQueue Instructions

Welcome to the **JavaTaskQueue** repository!

This project implements a robust, distributed-style Task Queue and Job Scheduler built purely with Java 17, without relying on any external frameworks like Spring or Web Application servers. It uses SQLite for lightweight, reliable persistence.

Below you'll find comprehensive instructions on how to use and interact with the project.

## Prerequisites

Before running the application, make sure you have:
- **Java 17 (or newer)** installed and available in your `PATH`.
- **Maven** (Apache Maven 3.8+ recommended) installed.

## 1. Building the Application

You need to compile the Java files and package them along with the required dependencies (SQLite JDBC and JSON library) into an executable JAR.

Open your terminal or command prompt in the project root directory and run:

```bash
mvn clean package
```

This command will:
1. Clean previous build artifacts.
2. Compile the source code.
3. Run the automated test suite.
4. Generate an Uber/Fat JAR inside the `target` directory: `target/JavaTaskQueue-jar-with-dependencies.jar`.

> **Note**: If you want to skip tests (not recommended), you can use `mvn clean package -DskipTests`.

## 2. Running the Server

The Task Broker acts as the core of the task queue. It orchestrates task scheduling, delegation to worker threads, and saving states to the SQLite database.

In a terminal, start the server using:

```bash
java -jar target/JavaTaskQueue-jar-with-dependencies.jar
```

You should see a banner along with initialization logs confirming that the SQLite repository and TCPServer (on port 9090) have successfully started.

## 3. Running the Client

The application comes with an interactive command-line interface (CLI) to communicate with the TaskQueue server.

**Open a new, separate terminal window** and run the client:

```bash
java -cp target/JavaTaskQueue-jar-with-dependencies.jar com.taskqueue.client.TaskQueueClient
```

If your server is running on another machine or port, you can specify them:
```bash
java -cp target/JavaTaskQueue-jar-with-dependencies.jar com.taskqueue.client.TaskQueueClient --host 192.168.1.10 --port 9091
```

Once connected, you will see a `jtq>` prompt.

## 4. Using the Client CLI

From the `jtq>` prompt, you can type several commands. 

### Submitting Tasks
Submit tasks using JSON strings. The queue currently supports three types of tasks: `ECHO`, `COMPUTE`, and `SLEEP`.

- **Echo Task**:
  ```text
  SUBMIT {"type":"ECHO","payload":"Hello, JavaTaskQueue!","priority":"HIGH"}
  ```
- **Compute Task (Factorial)**:
  ```text
  SUBMIT {"type":"COMPUTE","payload":"FACTORIAL:15","priority":"MEDIUM"}
  ```
- **Sleep Task (Delay simulation)**:
  ```text
  SUBMIT {"type":"SLEEP","payload":"3000","priority":"LOW"}
  ```

### Checking Queue and Task Status

- **Check specific task status**:
  ```text
  STATUS <task-id-returned-from-submit>
  ```
- **List all tasks**:
  ```text
  LIST
  ```

### Managing Tasks and Server
- **Cancel a task**:
  ```text
  CANCEL <task-id>
  ```
- **Shutdown Server**:
  ```text
  SHUTDOWN
  ```
- **Exit the Client**:
  ```text
  exit
  ```

## 5. Running Tests

To just run the tests and verify component integrity (Broker, Workers, Compute Handlers, etc.):

```bash
mvn test
```

## Adding New Task Types
If you want to extend the application to support new task logic:
1. Create a class implementing `com.taskqueue.handler.TaskHandler`.
2. Register it in `Main.java` before starting the server:
   ```java
   TaskHandlerRegistry.register("NEW_TYPE", new MyCustomHandler());
   ```

Enjoy using JavaTaskQueue!
