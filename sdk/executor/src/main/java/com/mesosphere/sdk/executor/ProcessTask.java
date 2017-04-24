package com.mesosphere.sdk.executor;

import org.apache.mesos.ExecutorDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.TaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mesosphere.sdk.offer.taskdata.EnvUtils;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * Generic process task, that can be spawned using {@code CustomExecutor}.
 */
public class ProcessTask implements ExecutorTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessTask.class);
    private final ProcessBuilder processBuilder;
    private final ExecutorDriver driver;
    private final Protos.TaskInfo taskInfo;
    private final CompletableFuture<Boolean> initialized =
            new CompletableFuture<>();
    private final CompletableFuture<Integer> exit =
            new CompletableFuture<>();
    private volatile Process process;

    private boolean exitOnTermination;

    // TODO(mohit): Remove this when KillPolicy is available.
    private static final Duration TERMINATE_TIMEOUT = Duration.ofSeconds(10);

    public static ProcessTask create(ExecutorDriver executorDriver, Protos.TaskInfo taskInfo) {
        return create(executorDriver, taskInfo, true);
    }

    public static ProcessTask create(
            ExecutorDriver executorDriver,
            Protos.TaskInfo taskInfo,
            boolean exitOnTermination) {
        return create(executorDriver, taskInfo, getProcess(taskInfo), exitOnTermination);
    }

    public static ProcessBuilder getProcess(TaskInfo taskInfo) {
        CommandInfo commandInfo = taskInfo.getCommand();
        String cmd = commandInfo.getValue();

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", cmd);
        builder.inheritIO();
        builder.environment().putAll(EnvUtils.fromEnvironmentToMap(commandInfo.getEnvironment()));

        return builder;
    }

    public static ProcessTask create(
            ExecutorDriver executorDriver,
            Protos.TaskInfo taskInfo,
            ProcessBuilder processBuilder,
            boolean exitOnTermination) {
        return new ProcessTask(executorDriver, taskInfo, processBuilder, exitOnTermination);
    }

    public ProcessBuilder getProcessBuilder() {
        return processBuilder;
    }

    protected ProcessTask(
            ExecutorDriver executorDriver,
            Protos.TaskInfo taskInfo,
            ProcessBuilder processBuilder,
            boolean exitOnTermination) {
        this.driver = executorDriver;
        this.taskInfo = taskInfo;
        this.processBuilder = processBuilder;
        this.exitOnTermination = exitOnTermination;
    }

    public void preStart() {
        // NOOP
    }

    @Override
    public void run() {
        try {

            preStart();

            LOGGER.info("Executing command: {}", processBuilder.command());
            LOGGER.info("With Environment: {}", processBuilder.environment());

            if (processBuilder.command().isEmpty()) {
                final String errorMessage = "Empty command found for: " + taskInfo.getName();
                TaskStatusUtils.sendStatus(
                        driver,
                        Protos.TaskState.TASK_FAILED,
                        taskInfo.getTaskId(),
                        taskInfo.getSlaveId(),
                        taskInfo.getExecutor().getExecutorId(),
                        errorMessage,
                        false);
                return;
            }

            this.process = processBuilder.start();

            final String startMessage = "Launching Task: " + taskInfo.getName();
            TaskStatusUtils.sendStatus(
                    driver,
                    Protos.TaskState.TASK_RUNNING,
                    taskInfo.getTaskId(),
                    taskInfo.getSlaveId(),
                    taskInfo.getExecutor().getExecutorId(),
                    startMessage,
                    true);
            initialized.complete(true);

            LOGGER.info(startMessage);
            waitUninterruptably(process);
            final int exitValue = process.exitValue();
            String exitMessage = String.format("Task: %s exited with code: %s", taskInfo.getTaskId(), exitValue);
            exit.complete(exitValue);
            Protos.TaskState taskState;

            boolean isHealthy = true;
            if (exitValue == 0) {
                taskState = Protos.TaskState.TASK_FINISHED;
                exitMessage += exitValue;
            } else if (exitValue > 128) {
                taskState = Protos.TaskState.TASK_KILLED;
                exitMessage += (exitValue - 128);
                isHealthy = false;
            } else {
                taskState = Protos.TaskState.TASK_FAILED;
                exitMessage += exitValue;
                isHealthy = false;
            }

            TaskStatusUtils.sendStatus(
                    driver,
                    taskState,
                    taskInfo.getTaskId(),
                    taskInfo.getSlaveId(),
                    taskInfo.getExecutor().getExecutorId(),
                    exitMessage,
                    isHealthy);

            LOGGER.info(exitMessage);
            if (exitOnTermination) {
                LOGGER.info("Executor is exiting with code {} because exitOnTermination: {}",
                        exitValue, exitOnTermination);
                System.exit(exitValue);
            }
        } catch (Throwable e) {
            LOGGER.error("Process task failed.", e);
            initialized.complete(false);
            exit.complete(1);
            TaskStatusUtils.sendStatus(
                    driver,
                    Protos.TaskState.TASK_FAILED,
                    taskInfo.getTaskId(),
                    taskInfo.getSlaveId(),
                    taskInfo.getExecutor().getExecutorId(),
                    e.getMessage(),
                    false);
            if (exitOnTermination) {
                driver.abort();
            }
        }
    }

    @Override
    public void stop(Future<?> future) {
        if (process != null) {
            LOGGER.info("Terminating process: task = {}", taskInfo);

            if (terminate(TERMINATE_TIMEOUT)) {
                LOGGER.info("Terminated process: task = {}", taskInfo.getTaskId());
            } else {
                LOGGER.warn("Failed to terminate process: task = {}",
                        taskInfo.getTaskId());
                LOGGER.info("Killing process task = {}", taskInfo.getTaskId());
                kill();
            }
        }
    }

    protected static void waitUninterruptably(final Process process) {
        while (true) {
            try {
                process.waitFor();
                return;
            } catch (InterruptedException ex) {
            }
        }
    }

    protected boolean isAlive() {
        return process != null && process.isAlive();
    }

    protected void sigTerm() {
        if (isAlive()) {
            LOGGER.info("Sending SIGTERM");
            process.destroy();
        }
    }

    protected void sigKill() {
        if (isAlive()) {
            LOGGER.info("Sending SIGKILL");
            process.destroyForcibly();
        }
    }

    private boolean waitInit() {
        while (true) {
            try {
                return initialized.get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.warn("Not yet initialized.", e);
            }
        }
    }

    public boolean terminate(Duration timeout) {
        LOGGER.info("Terminating process");
        if (waitInit() && isAlive()) {
            sigTerm();
        }
        return waitExit(timeout);
    }

    public int kill() {
        LOGGER.info("Killing process: name = {}", taskInfo.getName());
        if (waitInit() && isAlive()) {
            sigKill();
        }
        return waitExit();
    }

    private int waitExit() {
        while (true) {
            try {
                return exit.get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.warn("Not exited yet.", e);
            }
        }
    }

    private boolean waitExit(final Duration timeout) {
        while (true) {
            try {
                exit.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.warn("Not exited yet and timeout didn't expire.", e);
            } catch (TimeoutException e) {
                return false;
            }
        }
    }
}
