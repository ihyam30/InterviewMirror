package local.interviewmirror.backend.interviews;

import java.sql.SQLException;
import java.util.LinkedList;
import java.util.UUID;
import javax.sql.DataSource;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fence checkpoint writes with the same interview row lock used by resume deletion. */
public final class ResumeAwarePostgresSaver extends PostgresSaver {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public ResumeAwarePostgresSaver(DataSource source, PlatformTransactionManager manager) throws SQLException {
        super(PostgresSaver.builder().datasource(new TransactionAwareDataSourceProxy(source))
                .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new)).createTables(true));
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(manager);
    }
    @Override protected java.sql.Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return connection;
        // The saver normally commits its own JDBC connection. Within the fencing transaction
        // Spring must own commit/rollback, or the interview row lock would be released early.
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                    if (java.util.Set.of("commit", "rollback", "setAutoCommit").contains(method.getName())) return null;
                    try { return method.invoke(connection, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    public void erase(UUID interviewId) {
        transactions.executeWithoutResult(status -> {
            // release() only marks a thread released; delete all its retained checkpoints too.
            jdbc.update("DELETE FROM LG4JThread WHERE thread_name=?", interviewId.toString());
        });
    }
    private void requireActive(RunnableConfig config) {
        var rows = jdbc.query("SELECT delete_requested FROM interviews WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getBoolean(1), UUID.fromString(config.threadId().orElseThrow()));
        if (rows.isEmpty() || rows.getFirst()) throw new IllegalStateException("Interview was deleted");
    }
    @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        transactions.executeWithoutResult(status -> {
            requireActive(config);
            try { super.insertedCheckpoint(config, checkpoints, checkpoint); }
            catch (Exception failure) { throw new IllegalStateException("Checkpoint insert failed", failure); }
        });
    }
    @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
        transactions.executeWithoutResult(status -> {
            requireActive(config);
            try { super.updatedCheckpoint(config, checkpoints, checkpoint); }
            catch (Exception failure) { throw new IllegalStateException("Checkpoint update failed", failure); }
        });
    }
}
