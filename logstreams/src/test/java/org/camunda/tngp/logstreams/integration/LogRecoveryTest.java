package org.camunda.tngp.logstreams.integration;

import org.camunda.tngp.logstreams.LogStreams;
import org.camunda.tngp.logstreams.integration.util.LogIntegrationTestUtil;
import org.camunda.tngp.logstreams.log.BufferedLogStreamReader;
import org.camunda.tngp.logstreams.log.LogStream;
import org.camunda.tngp.logstreams.log.LogStreamReader;
import org.camunda.tngp.util.agent.AgentRunnerService;
import org.camunda.tngp.util.agent.SharedAgentRunnerService;
import org.camunda.tngp.util.agent.SimpleAgentRunnerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.tngp.logstreams.integration.util.LogIntegrationTestUtil.waitUntilWrittenKey;
import static org.camunda.tngp.logstreams.integration.util.LogIntegrationTestUtil.writeLogEvents;

public class LogRecoveryTest
{
    // ~ overall message size 46 MB
    private static final int MSG_SIZE = 911;
    private static final int WORK_COUNT = 50_000;

    private static final int LOG_SEGMENT_SIZE = 1024 * 1024 * 8;
    private static final int INDEX_BLOCK_SIZE = 1024 * 1024 * 2;

    private static final String LOG_NAME = "foo";
    private static final int LOG_ID = 0;

    @Rule
    public TemporaryFolder temFolder = new TemporaryFolder();

    private String logPath;

    private AgentRunnerService agentRunnerService;

    @Before
    public void setup()
    {
        logPath = temFolder.getRoot().getAbsolutePath();

        agentRunnerService = new SharedAgentRunnerService(new SimpleAgentRunnerFactory(), "test");
    }

    @After
    public void destroy() throws Exception
    {
        agentRunnerService.close();
    }

    @Test
    public void shouldRecoverIndexWithLogBlockIndexController() throws InterruptedException, ExecutionException
    {
        final LogStream log = LogStreams.createFsLogStream(LOG_NAME, LOG_ID)
            .logRootPath(logPath)
            .deleteOnClose(false)
            .logSegmentSize(LOG_SEGMENT_SIZE)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .agentRunnerService(agentRunnerService)
            .snapshotPolicy(pos -> false)
            .writeBufferAgentRunnerService(agentRunnerService)
            .build();

        log.open();

        writeLogEvents(log, WORK_COUNT, MSG_SIZE, 0);
        waitUntilWrittenKey(log, WORK_COUNT);

        log.close();

        final int indexSize = log.getLogBlockIndex().size();
        final int calculatesIndexSize = calculateIndexSize(WORK_COUNT);
        assertThat(indexSize).isGreaterThan(calculatesIndexSize);
        readLogAndAssertEvents(WORK_COUNT, calculatesIndexSize);
    }

    @Test
    public void shouldRecoverIndexFromSnapshot() throws InterruptedException, ExecutionException
    {
        final AtomicBoolean isLastLogEntry = new AtomicBoolean(false);

        final LogStream log = LogStreams.createFsLogStream(LOG_NAME, 0)
            .logRootPath(logPath)
            .deleteOnClose(false)
            .logSegmentSize(LOG_SEGMENT_SIZE)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .agentRunnerService(agentRunnerService)
            .snapshotPolicy(pos -> isLastLogEntry.getAndSet(false))
            .writeBufferAgentRunnerService(agentRunnerService)
            .build();

        log.open();

        writeLogEvents(log, WORK_COUNT, MSG_SIZE, 0);
        waitUntilWrittenKey(log, WORK_COUNT);

        isLastLogEntry.set(true);

        // write more events to create the snapshot
        writeLogEvents(log, 1000, MSG_SIZE, WORK_COUNT);
        waitUntilWrittenKey(log, WORK_COUNT + 1000);

        log.close();

        final int indexSize = log.getLogBlockIndex().size();
        final int calculatesIndexSize = calculateIndexSize(WORK_COUNT + 1000);
        assertThat(indexSize).isGreaterThan(calculatesIndexSize);
        readLogAndAssertEvents(WORK_COUNT + 1000, calculatesIndexSize);
    }

    @Test
    public void shouldRecoverIndexFromSnapshotAndLogStorage() throws InterruptedException, ExecutionException
    {
        final AtomicBoolean isSnapshotPoint = new AtomicBoolean(false);

        final LogStream log = LogStreams.createFsLogStream(LOG_NAME, 0)
            .logRootPath(logPath)
            .deleteOnClose(false)
            .logSegmentSize(LOG_SEGMENT_SIZE)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .agentRunnerService(agentRunnerService)
            .snapshotPolicy(pos -> isSnapshotPoint.getAndSet(false))
            .writeBufferAgentRunnerService(agentRunnerService)
            .build();

        log.open();

        writeLogEvents(log, WORK_COUNT / 2, MSG_SIZE, 0);
        waitUntilWrittenKey(log, WORK_COUNT / 2);

        // enables creation of snapshot to store half of the block indices
        isSnapshotPoint.set(true);

        writeLogEvents(log, WORK_COUNT / 2, MSG_SIZE, WORK_COUNT / 2);
        waitUntilWrittenKey(log, WORK_COUNT);

        log.close();

        final int indexSize = log.getLogBlockIndex().size();
        final int calculatesIndexSize = calculateIndexSize(WORK_COUNT);
        assertThat(indexSize).isGreaterThan(calculatesIndexSize);
        readLogAndAssertEvents(WORK_COUNT, calculatesIndexSize);
    }

    @Test
    public void shouldRecoverIndexFromPeriodicallyCreatedSnapshot() throws InterruptedException, ExecutionException
    {
        final int snapshotInterval = 10;

        final AtomicInteger snapshotCount = new AtomicInteger(0);

        final LogStream log = LogStreams.createFsLogStream(LOG_NAME, 0)
            .logRootPath(logPath)
            .deleteOnClose(false)
            .logSegmentSize(LOG_SEGMENT_SIZE)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .snapshotPolicy(pos -> (snapshotCount.incrementAndGet() % snapshotInterval) == 0)
            .agentRunnerService(agentRunnerService)
            .writeBufferAgentRunnerService(agentRunnerService)
            .build();

        log.open();

        writeLogEvents(log, WORK_COUNT, MSG_SIZE, 0);
        waitUntilWrittenKey(log, WORK_COUNT);

        log.close();

        final int indexSize = log.getLogBlockIndex().size();
        final int calculatesIndexSize = calculateIndexSize(WORK_COUNT);
        assertThat(indexSize).isGreaterThan(calculatesIndexSize);
        readLogAndAssertEvents(WORK_COUNT, calculatesIndexSize);
    }


    @Test
    public void shouldRecoverEventPosition() throws InterruptedException, ExecutionException
    {
        final LogStream log = LogStreams.createFsLogStream(LOG_NAME, LOG_ID)
            .logRootPath(logPath)
            .deleteOnClose(false)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .snapshotPolicy(pos -> false)
            .agentRunnerService(agentRunnerService)
            .writeBufferAgentRunnerService(agentRunnerService).build();
        log.open();

        // write events
        writeLogEvents(log, 1500, MSG_SIZE, 0);
        waitUntilWrittenKey(log, 1500);

        log.close();
        int indexSize = log.getLogBlockIndex().size();
        assertThat(indexSize).isGreaterThan(0);

        // re-open the log
        final LogStream newLog = LogStreams.createFsLogStream(LOG_NAME, LOG_ID)
            .logRootPath(logPath)
            .deleteOnClose(false)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .snapshotPolicy(pos -> false)
            .agentRunnerService(agentRunnerService)
            .writeBufferAgentRunnerService(agentRunnerService).build();
        newLog.open();

        // check if indices are equal
        final int newIndexSize = newLog.getLogBlockIndex().size();
        assertThat(indexSize).isEqualTo(newIndexSize);

        // write more events
        writeLogEvents(newLog, 1500, MSG_SIZE, 1500);
        waitUntilWrittenKey(newLog, 3000);

        newLog.close();
        indexSize = newLog.getLogBlockIndex().size();
        final int calculatesIndexSize = calculateIndexSize(3000);
        assertThat(indexSize).isGreaterThanOrEqualTo(calculatesIndexSize);

        // assert that the event position is recovered after re-open and continues after the last event
        readLogAndAssertEvents(3000, calculatesIndexSize);
    }

    @Test
    public void shouldResumeLogStream() throws InterruptedException, ExecutionException
    {
        final LogStream log = LogStreams.createFsLogStream(LOG_NAME, LOG_ID)
            .logRootPath(logPath)
            .deleteOnClose(false)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .snapshotPolicy(pos -> false)
            .agentRunnerService(agentRunnerService)
            .writeBufferAgentRunnerService(agentRunnerService)
            .build();

        log.open();

        // write events
        writeLogEvents(log, 1000, MSG_SIZE, 0);
        waitUntilWrittenKey(log, 1000);

        log.close();
        int indexSize = log.getLogBlockIndex().size();
        assertThat(indexSize).isEqualTo(0);

        // resume the log
        log.open();

        // write more events
        writeLogEvents(log, 500, MSG_SIZE, 1000);
        waitUntilWrittenKey(log, 1500);

        log.close();

        // after resume an index was written
        final int calculatesIndexSize = calculateIndexSize(1500);
        assertThat(log.getLogBlockIndex().size()).isGreaterThan(indexSize);
        indexSize = log.getLogBlockIndex().size();
        assertThat(indexSize).isGreaterThan(calculatesIndexSize);

        readLogAndAssertEvents(1500, calculatesIndexSize);
    }

    protected void readLogAndAssertEvents(int workCount, int indexSize)
    {
        final LogStream newLog = LogStreams.createFsLogStream(LOG_NAME, LOG_ID)
            .logRootPath(logPath)
            .deleteOnClose(true)
            .logSegmentSize(LOG_SEGMENT_SIZE)
            .indexBlockSize(INDEX_BLOCK_SIZE)
            .agentRunnerService(agentRunnerService)
            .logStreamControllerDisabled(true)
            .build();

        newLog.open();

        final LogStreamReader logReader = new BufferedLogStreamReader(newLog);

        LogIntegrationTestUtil.readLogAndAssertEvents(logReader, workCount, MSG_SIZE);

        newLog.close();

        final int newIndexSize = newLog.getLogBlockIndex().size();
        assertThat(newIndexSize).isGreaterThan(indexSize);
    }

    private int calculateIndexSize(int workCount)
    {
        return (int) (Math.floor(workCount * MSG_SIZE) / INDEX_BLOCK_SIZE);
    }
}
